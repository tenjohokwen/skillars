# `PessimisticLockRetryer`: bounded-wait pessimistic locking on Postgres

> **Status:** shipped by `skillars-deferred-62` (2026-08-24), hardened by its own code review the same
> day. Fixes a gap diagnosed by `skillars-deferred-23`'s code review (2026-08-14) and deliberately left
> unfixed by three subsequent stories pending a project-owner decision on the right fix.

This document explains why `findByIdForUpdate` contention now fails fast and retries instead of
blocking forever, why that needs a purpose-built helper rather than an off-the-shelf Spring feature,
which alternatives were tried and rejected, and how to wire a new locked call site correctly.

---

## The problem: a lock-timeout hint that Postgres silently ignores

Four repositories take a row lock before reading, so that two requests racing to read-then-write the
same row serialize instead of one silently clobbering the other:

```java
// CoachProfileRepository — one of four repositories with this exact shape
@Lock(LockModeType.PESSIMISTIC_WRITE)
@QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "5000"))  // (the old value)
@Query("SELECT c FROM CoachProfile c WHERE c.id = :id")
Optional<CoachProfile> findByIdForUpdate(@Param("id") UUID id);
```

Every comment near these four methods claimed contention would fail after roughly 5 seconds with a
clean `409`, mapped by `ApiAdvice.pessimisticLockExceptionHandler`. That claim was never true on this
stack. `org.hibernate.dialect.PostgreSQLDialect.withTimeout(String, int)` only special-cases two
sentinel values:

```java
// org.hibernate.dialect.PostgreSQLDialect (decompiled, hibernate-core-6.6.53.Final)
private String withTimeout(String lockString, int timeout) {
    switch (timeout) {
        case LockOptions.SKIP_LOCKED: return supportsSkipLocked() ? lockString + " skip locked" : lockString;
        case LockOptions.NO_WAIT:     return supportsNoWait()     ? lockString + " nowait"       : lockString;
        default:                      return lockString;   // <- every finite millisecond value lands here
    }
}
```

Any other value — including every value this codebase used, `"5000"` — falls through to `default` and
the lock clause comes back **completely unchanged**. No `WAIT n` (Postgres's `FOR UPDATE` syntax has no
such clause), no `SET LOCAL lock_timeout`. `PostgreSQLDialect.supportsWait()` also returns `false`
unconditionally, confirming this isn't an edge case — finite `lock.timeout` values simply do nothing on
Postgres in this Hibernate version.

This was confirmed empirically before it was fixed, not just by reading the dialect source: a throwaway
IT held a competing row lock for 12 seconds against a `findByIdForUpdate` call carrying the "5000" hint.
The contended call blocked for the full 12 seconds and then completed normally — no
`PessimisticLockingFailureException` ever thrown. `ApiAdvice`'s 409 mapping was effectively dead code
for this failure mode on all four repositories.

## The fix has two independent parts

**Part 1 — make Postgres actually bound the wait.** `LockOptions.NO_WAIT` is the sentinel value `0`,
and it *is* one of the two cases `withTimeout` special-cases. Changing the hint's value from `"5000"` to
`"0"` makes Hibernate emit `for no key update nowait`, and a contended attempt now fails immediately
with:

```
org.springframework.dao.PessimisticLockingFailureException
  caused by: org.hibernate.PessimisticLockException
    caused by: org.postgresql.util.PSQLException: ERROR: could not obtain lock on row in relation "..."
    (SQLState 55P03)
```

— confirmed the same way: a throwaway IT, not just reading the dialect switch statement.

**Part 2 — don't let "fail immediately" mean "fail on any fleeting overlap."** `NO_WAIT` alone would
turn every legitimate near-simultaneous request (two parents booking the same coach a few milliseconds
apart, say) into an immediate 409, where before — in principle, per the now-corrected comments — they'd
have waited briefly and both succeeded. `PessimisticLockRetryer` is that "wait briefly," implemented as
a bounded retry rather than a real wait, since Postgres no longer offers a real wait to lean on.

One subtlety surfaced while diagnosing part 1: each of the four repositories' call sites does the
locked read *twice* — once via the JPQL query itself, and again via a follow-up
`entityManager.refresh(entity, LockModeType.PESSIMISTIC_WRITE)` (needed because the entity is usually
already managed from an earlier unlocked read, and a JPQL query returns the existing in-memory instance
without refreshing its fields). It would have been easy to assume the query-level hint needed fixing and
leave the refresh call alone, or vice versa. A dedicated throwaway IT settled it: with a competing lock
held, the JPQL query blocked for the full hold time while the follow-up `refresh()` — run in the same
transaction, once the query itself had already secured the lock — returned in under a second regardless
of contention. **The JPQL query is where the lock is genuinely (re-)acquired; the refresh is a same-
transaction re-lock that Postgres treats as free.** Only the query-level hint needed to change.

## Why a straightforward retry loop doesn't work here

The obvious next step — catch `PessimisticLockingFailureException` and just call the repository method
again — compiles, looks correct, and is wrong. PostgreSQL aborts the **entire transaction** on any
statement error, `NO_WAIT` lock failures included. Every later statement on that connection fails with
`ERROR: current transaction is aborted, commands ignored until end of transaction block` until the
transaction either rolls back completely or resumes from a savepoint taken *before* the failing
statement.

Verified directly, not assumed: a throwaway IT that caught the first `PessimisticLockingFailureException`
and immediately retried the same repository call, inside the same `TransactionTemplate.execute(...)`
block, got `UnexpectedRollbackException: Transaction silently rolled back because it has been marked as
rollback-only` — the second attempt never even had a chance; Spring's JPA exception translation had
already doomed the transaction the moment the first attempt failed.

### Options considered for the retry mechanism

| Option | Verdict | Why |
|---|---|---|
| **Naive catch-and-retry, same transaction, no savepoint** | ❌ Rejected | Proven broken above — the transaction is already aborted by the second attempt. |
| **Spring `@Transactional(propagation = Propagation.NESTED)`** | ❌ Rejected | Throws `NestedTransactionNotSupportedException: JpaDialect does not support savepoints`. Spring's `DefaultJpaDialect` — what this project uses, with no custom override — doesn't implement savepoint support for plain JPA. Adding one would mean introducing new transaction-manager infrastructure, out of scope for a locking fix. |
| **`REQUIRES_NEW`: acquire the lock in its own sub-transaction, commit, continue in the outer one** | ❌ Rejected (design-time, not tested) | A `PESSIMISTIC_WRITE` lock is held only for the life of the transaction that took it. Committing the sub-transaction to "finish" the locked read would release the lock immediately — before the outer transaction does the writes the lock exists to protect. That reopens exactly the race these locks were added to close. |
| **Retry the *entire* enclosing `@Transactional` business method** (e.g. `@Retryable` on the outer boundary, transactional method called through a second bean) | Viable, not chosen | Would have worked — every one of the 16 call sites does only reads before the lock, so replaying the whole method on a fresh transaction is safe. Rejected because it needs restructuring 16 methods across 10 services into an outer retry wrapper + inner `@Transactional` worker (to guarantee the retry advice sits outside the transactional proxy, since stacking `@Retryable` and `@Transactional` on the *same* method has ambiguous, registration-order-dependent interceptor ordering) — a much larger, riskier diff than the AC called for, and a bigger blast radius on unrelated method logic (event publishing, validation) for no behavioral gain over the option below. |
| **Manually manage a JDBC savepoint** (via Hibernate's `Session.doWork`, bypassing Spring's `NESTED`-propagation abstraction entirely) | ✅ **Chosen** | Verified directly: take a savepoint before each attempt; on failure, roll back to it via the raw `Connection` and retry. The transaction recovers and keeps going. No transaction-manager changes, no method restructuring, and it retries exactly the unit the AC asked for — the locked read (+ refresh), nothing more. |

The chosen option isn't "the framework-blessed way" — it reaches past Spring's transaction abstraction
to Hibernate's `Session` and raw JDBC. That's a deliberate trade: it's the only option that satisfies
all three constraints at once (retry just the locked-read unit, no new transaction-manager
infrastructure, no restructuring of 16 call sites) once the framework-native paths (`NESTED`,
`REQUIRES_NEW`) were ruled out for this project's actual JPA configuration.

## How `PessimisticLockRetryer` works

This is the real implementation, after its own code review pass — the flush, `validateConfig()`,
`releaseSavepoint(...)`, jitter, and `WARN`-level give-up log below were all added by that review; the
"why" for each is covered inline and in the subsections that follow.

```java
@Component
public class PessimisticLockRetryer {

    @PersistenceContext
    private EntityManager entityManager;

    // app.locking.retry.{max-attempts,initial-backoff-ms,max-backoff-ms,backoff-multiplier}
    // — defaults only, not present in any application*.yaml. See "Configuration" below.
    private int maxAttempts;          // default 8
    private long initialBackoffMs;    // default 100
    private long maxBackoffMs;        // default 800
    private double backoffMultiplier; // default 1.6

    @PostConstruct
    void validateConfig() {
        // fails fast at startup on maxAttempts < 1, initialBackoffMs <= 0,
        // maxBackoffMs < initialBackoffMs, or backoffMultiplier < 1.0
    }

    public <T> T withBoundedRetry(Supplier<T> lockedOperation) {
        Session session = entityManager.unwrap(Session.class);
        long backoffMillis = initialBackoffMs;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            entityManager.flush();
            Savepoint[] savepointHolder = new Savepoint[1];
            session.doWork(connection -> savepointHolder[0] = connection.setSavepoint());
            try {
                T result = lockedOperation.get();
                session.doWork(connection -> connection.releaseSavepoint(savepointHolder[0]));
                return result;
            } catch (PessimisticLockingFailureException e) {
                if (attempt == maxAttempts) {
                    log.warn("Giving up on a pessimistic lock after {} attempts; surfacing contention", attempt);
                    throw e;
                }
                session.doWork(connection -> connection.rollback(savepointHolder[0]));
                sleep(jitter(backoffMillis));
                backoffMillis = Math.min((long) (backoffMillis * backoffMultiplier), maxBackoffMs);
            }
        }
        throw new IllegalStateException("unreachable");
    }
}
```

Per attempt:

1. **`entityManager.flush()`**, first, before anything else. See "The persistence-context desync bug"
   below — this is not optional housekeeping, it's what keeps the savepoint rollback from ever
   discarding a write the caller made earlier in the same transaction.
2. **`entityManager.unwrap(Session.class)`** drops from the JPA API to Hibernate's native `Session`,
   which exposes `doWork(Work)` — a callback that hands you the live JDBC `Connection` backing the
   *current* Spring-managed transaction. No new transaction is opened; this is the same connection the
   caller's `@Transactional` method is already using.
3. **`connection.setSavepoint()`** marks a point Postgres can roll back to without discarding the whole
   transaction — this is what `NESTED` propagation would have given us declaratively, taken manually
   instead.
4. **`lockedOperation.get()`** runs the caller's `Supplier` — typically
   `repo.findByIdForUpdate(id).orElseThrow(...)`, sometimes followed by
   `entityManager.refresh(entity, PESSIMISTIC_WRITE)`.
5. **On success:** explicitly `releaseSavepoint(...)` before returning. Postgres would clean an
   unreleased savepoint up at commit/rollback anyway, but under this helper's retry loop a single
   `@Transactional` method can accumulate several savepoints per attempt across contended calls — an
   explicit release keeps the transaction's savepoint stack from growing unbounded on a hot,
   frequently-contended row.
6. **On `PessimisticLockingFailureException`, not the last attempt:** roll back to the savepoint (undoes
   the abort, the connection is usable again), sleep for a *jittered* backoff, grow the base backoff,
   loop.
7. **On `PessimisticLockingFailureException`, last attempt:** log at `WARN` (this is the moment
   contention was meant to "surface," so it belongs above `DEBUG`) and rethrow unchanged. It propagates
   to `ApiAdvice.pessimisticLockExceptionHandler` exactly as before this story — no code change was
   needed there, since it already caught `PessimisticLockingFailureException` unconditionally.
8. **Any other exception** (a genuine not-found from `orElseThrow`, for instance) isn't caught here at
   all — it propagates immediately, unretried, and the loop never gets a chance to swallow a real error.

### The persistence-context desync bug — why every attempt flushes first

The code review's one decision-needed finding, resolved the same day: rolling back to a raw JDBC
savepoint is invisible to Hibernate's own bookkeeping. If some *other*, unrelated entity in the same
`@Transactional` method already had a pending change before the locked read ran, Hibernate's default
auto-flush behavior can flush that pending write to the database **as a side effect of executing the
locked query itself** — before the lock attempt fails. If the attempt then fails and the retry rolls
back to a savepoint taken *before* that auto-flush, the database-level write is undone, but Hibernate's
session still believes the entity is clean and will not re-flush it at commit. The write silently
vanishes, with no exception anywhere to catch.

The fix is one line: call `entityManager.flush()` **before** taking the savepoint, on every attempt, not
just the first. This forces any pending writes to land before the savepoint boundary exists, so a
rollback-to-savepoint can only ever undo that attempt's own locked-read statement — never a write the
caller made earlier in the method. This is why the flush is unconditional and inside the loop, not a
one-time call before the loop starts: a later attempt (attempt 2, 3, ...) could just as easily follow a
fresh pending write made between attempts if `lockedOperation` itself does more than a pure read (it
generally shouldn't, per "Why retrying from a savepoint is safe here" below, but the flush makes the
helper correct even if that assumption is ever violated at a future call site).

### Backoff schedule

Base schedule (before jitter) at the default configuration:

| Attempt | Base backoff before next attempt |
|---|---|
| 1 | 100 ms |
| 2 | 160 ms |
| 3 | 256 ms |
| 4 | ~410 ms |
| 5 | ~655 ms |
| 6 | 800 ms (capped) |
| 7 | 800 ms (capped) |
| 8 | *(last attempt — failure surfaces, no further sleep)* |

The **actual** sleep at each step is randomized to **[50%, 100%]** of that base value (a `jitter(...)`
helper, added by code review). Two threads contending for the same row typically wake from
`CountDownLatch`/business logic at nearly the same instant and would otherwise retry in lockstep —
attempt 2 colliding with attempt 2, attempt 3 with attempt 3, indefinitely, in the worst case. Jitter
breaks that resonance so the two threads' schedules drift apart after the first collision.

Total budget ≈ 1.6–3.2 seconds of sleeping across 8 attempts (jitter makes the real total
non-deterministic), plus the near-instant `NO_WAIT` query time each attempt — comfortably in the same
rough ballpark as the ~5 seconds the (now-corrected) comments always claimed, without hard-coding an
exact number. AC2 was explicit that "bounded and short, not indefinite" is the contract, not an exact
millisecond figure.

The four `@Value` fields' defaults and startup validation are covered together under
["Configuration"](#configuration) below.

### Why retrying from a savepoint is safe here

The savepoint only undoes the *failed locked-read statement itself* — and, since every attempt now
flushes first (see above), it never has a chance to undo anything else either. That's additionally safe
by convention because every one of the 16 wired call sites does only unlocked reads and validation
before the locked read — never a write. If a future call site needs to wrap a locked read that
*follows* a write earlier in the same transaction, the flush-then-savepoint ordering already protects
that write from being silently dropped; re-verify anyway before reusing the helper as-is, since the
`Supplier<T>` passed to `withBoundedRetry` is documented, but not enforced, to be safely retriable
(pure reads plus the locked read/refresh) — see "Known limitations" below.

## Wiring a new locked call site

If you're adding a `findByIdForUpdate`-shaped locked read against one of the four `NO_WAIT` repositories
(or extending `NO_WAIT` + retry to a new one — see "Repositories not covered" below), inject
`PessimisticLockRetryer` and wrap the entire locked-read-plus-refresh sequence, not just the repository
call:

```java
@Service
@RequiredArgsConstructor
public class SomeService {

    private final SomeRepository someRepository;
    private final PessimisticLockRetryer lockRetryer;   // add this
    private final EntityManager entityManager;           // only if you also need refresh()

    @Transactional
    public void someMethod(UUID id) {
        // No refresh needed:
        SomeEntity locked = lockRetryer.withBoundedRetry(() ->
            someRepository.findByIdForUpdate(id)
                .orElseThrow(() -> new ResourceNotFoundException("...", "...")));

        // Refresh needed (entity already managed from an earlier unlocked read):
        SomeEntity locked = lockRetryer.withBoundedRetry(() -> {
            SomeEntity e = someRepository.findByIdForUpdate(id)
                .orElseThrow(() -> new ResourceNotFoundException("...", "..."));
            entityManager.refresh(e, LockModeType.PESSIMISTIC_WRITE);
            return e;
        });
    }
}
```

**Do not** retry only the repository call while leaving a follow-up `refresh()` outside the lambda — a
retried-and-succeeded query followed by an unretried `refresh()` call defeats the point, and in practice
the `refresh()` won't need its own retry anyway (see "why the query is where the lock lives," above) but
it must still run *inside* the same successful attempt, not after the helper returns.

**Do not** write a second, independent retry loop. One shared helper, one place to tune the budget, one
place a future contention-tuning story needs to touch.

## Testing the helper itself: `PessimisticLockRetryerTest`

The concurrency ITs below prove the end-to-end behavior against a real Postgres, but only exercise
`PessimisticLockRetryer`'s attempt-counting, savepoint sequencing, and give-up logic *indirectly*,
through whatever contention timing actually happens on one repository. `PessimisticLockRetryerTest`
(added by code review) tests the helper directly and deterministically, with a mocked
`EntityManager`/`Session`/`Connection` and a controllable failing `Supplier`:

- first-attempt success — releases the savepoint, never rolls back, `flush()` called once;
- fails once then succeeds — rolls back exactly once, releases the savepoint on the eventual success,
  `flush()` called once per attempt (twice total);
- exhausts the full retry budget — rolls back `maxAttempts - 1` times, never releases a savepoint, and
  rethrows the *same* exception instance after exactly `maxAttempts` calls to the `Supplier`;
- a non-`PessimisticLockingFailureException` from the `Supplier` propagates on the first attempt,
  unretried, with no rollback and no release;
- all four `validateConfig()` rejection paths (bad `maxAttempts`, bad `initialBackoffMs`, `maxBackoffMs`
  below `initialBackoffMs`, `backoffMultiplier` below `1.0`), plus one test confirming the shipped
  defaults themselves pass validation.

If you're changing `withBoundedRetry`'s control flow, this is the fast, deterministic place to add a
case — no Testcontainers, no timing-based assertions, no real contention needed.

## Testing a locked call site end-to-end

Mirror `SessionPackPurchaseLockContentionIT`'s two bounded-wait tests
(`deductSession_briefContention_succeedsAfterBoundedRetry`,
`deductSession_prolongedContention_failsWithBounded409AfterRetryBudgetExhausted`):

1. A holder thread opens its own transaction, takes `SELECT ... FOR UPDATE` on the target row via raw
   `jdbcTemplate`, signals a `CountDownLatch`, sleeps for a fixed duration, then lets the transaction
   commit (releasing the lock).
2. The contended call runs on a second thread, started only after the latch fires (guaranteeing it
   starts after the row is genuinely locked).
3. **Brief-contention case:** hold well inside the retry budget (1.2s against the ~3.2s default budget).
   Assert the call succeeds and — important — assert it took *at least* roughly the hold time
   (`holdMillis - 200`), not near-instantly, or the test could pass by accident without ever exercising
   contention.
4. **Prolonged-contention case:** hold past the budget (8s). Assert the call throws
   `PessimisticLockingFailureException` (or, through HTTP, a `409`), and that it failed in **bounded**
   time: code review tightened this from a loose `< 6000ms` to `< 4500ms` against the documented ~3.2s
   budget — tight enough to actually catch a regression toward unbounded waiting, not just "eventually."
   The lower bound (`> 1000ms`) proves it wasn't an instant, un-retried failure either.

### A trap this story hit: `pg_locks` polling no longer detects contention

Before this story, `BookingServiceConcurrencyIT` staged its three `findByIdForUpdate`-contention tests
by polling `pg_locks` for another session genuinely *blocked* (`granted = false`) on the row — the
textbook way to deterministically prove two sessions collided under the old, unbounded-wait locking.

Under `NO_WAIT`, a contended attempt never enters that blocked state; it fails immediately and leaves no
`pg_locks` trace to poll for. All three tests failed the first time the full suite ran against the fixed
repositories, each with `AssertionError: No other session was observed blocked on the coach_profiles row
lock`. The fix: hold the lock for a fixed duration comfortably inside the retry budget (long enough to
guarantee the first attempt collides, given the two threads are already synchronized by a latch — no
polling needed), and add a **not-near-instant** timing assertion on the contended call as the
replacement mutation-detection signal — a missing or broken lock would otherwise let the call complete
suspiciously fast, which the old polling helper would have caught but a naive fixed-sleep replacement
would not, without that extra assertion.

Code review hardened this staging twice more:

- The magic number itself: the original patch used a bare, unexplained `Thread.sleep(600)` inline at
  each of the 3 call sites — the exact kind of unexplained fixed-timing constant that makes concurrency
  tests flaky under CI load in the first place. It's now a single, javadoc'd
  `COACH_LOCK_HOLD_MILLIS = 2000` constant, documenting *why* that value (long enough to outlast
  wake-from-latch jitter under CI load, short enough to stay well inside the ~3.2s retry budget) and used
  identically at all 3 sites.
- The mutation-detection assertion: an initial `>= 80ms` bound was too loose to actually prove a real
  `NO_WAIT` collision happened — plenty of unrelated overhead could clear 80ms on its own. Tightened to
  `>= COACH_LOCK_HOLD_MILLIS - 300`, which can only pass if the contended call genuinely waited out most
  of the lock hold via retry.

If you're staging a **new** concurrency test against one of these four repositories: use a fixed,
documented (not bare-magic-number) hold comfortably inside the retry budget, assert a **lower** bound
close to that hold duration (not a token "not literally zero" check), and — for a failure-path test —
assert a **tight** upper bound against the documented budget, not a generously loose one. Don't poll
`pg_locks` for a blocked state; that's now a permanent false-negative risk against any `NO_WAIT` lock.

## Configuration

`maxAttempts`, `initialBackoffMs`, `maxBackoffMs`, and `backoffMultiplier` are `@Value`-injected with
inline defaults (`app.locking.retry.max-attempts:8`, etc., mirroring `S3StorageService`'s
`@Retryable`-based convention). **None of these properties are set in any `application*.yaml` today** —
the defaults are the only configuration in effect. If you need a different budget for a specific
environment, add the property; don't assume one already exists just because the `@Value` expression
looks like it belongs in a yaml file somewhere.

Whatever you set, it's checked once, at startup: a `@PostConstruct validateConfig()` (added by code
review) fails application startup immediately with a descriptive `IllegalStateException` if
`maxAttempts < 1`, `initialBackoffMs <= 0`, `maxBackoffMs < initialBackoffMs`, or
`backoffMultiplier < 1.0`. Before this existed, a typo'd or nonsensical property — a `backoffMultiplier`
under `1.0` that would shrink instead of grow, say — would have surfaced only as confusing runtime
behavior the first time real contention hit it. Now it's a deploy-time failure instead of a production
incident.

## Known limitations (accepted, not fixed)

The code review pass explicitly deferred five findings rather than patching them, each for a stated
reason. Worth knowing before you lean on this helper somewhere new:

- **`BookingService.cancelBookingAsParent`'s locked read has no `entityManager.refresh(...)`**, unlike
  its sibling call sites. Confirmed pre-existing — this story's diff only added the `lockRetryer` wrap
  here, the missing refresh predates it. Not this helper's responsibility to fix; flagged so it isn't
  mistaken for a new gap.
- **The retry loop sleeps while still holding the transaction's pooled JDBC connection**, up to the full
  ~3.2s budget under sustained contention. This is the direct cost of the "retry in place, same
  transaction, same connection" design chosen over `NESTED`/`REQUIRES_NEW` (see "Options considered"
  above) — a consciously accepted tradeoff, not an oversight. A connection pool sized for this contention
  pattern matters more than it would have under the old (broken) unbounded-wait behavior, which held
  connections far longer anyway.
- **Only `SessionPackPurchaseRepository` has full IT-level proof of the bounded-wait behavior end to
  end.** The other three repositories rely on unit coverage (`PessimisticLockRetryerTest`) plus their own
  services' targeted test suites, not a duplicated concurrency IT each — within AC3's own explicit
  discretion to cover "at least one of the four" at the IT layer.
- **`withBoundedRetry`'s `Supplier<T>` idempotency contract is documented, not enforced.** Nothing stops
  a future caller from passing a `Supplier` that writes as well as reads — the helper has no way to
  detect or reject that, and a write inside a retried `Supplier` would be replayed on every attempt.
  Speculative risk today: all 16 current call sites are read-only before the lock. If you add a call site
  whose locked-read-plus-refresh sequence does anything beyond reading, re-read "Why retrying from a
  savepoint is safe here" above and confirm the ordering explicitly.
- **A JDBC `setSavepoint()`/rollback-to-savepoint call itself failing propagates unretried, as an opaque
  500.** Accepted as reasonable behavior for what would be a genuine infrastructure failure (e.g. the
  connection itself dying mid-attempt) rather than the pessimistic-lock contention this helper exists to
  handle — not something a locking retry should paper over.

## Repositories not covered

`VideoQuotaRepository`, `MessageRepository`, and `CoachReviewRepository` (among others) also have
`findByIdForUpdate`-shaped locked reads, but carry **no** `jakarta.persistence.lock.timeout` hint at
all — they wait indefinitely today with no bounded-wait claim to fix, and were explicitly out of scope
for `skillars-deferred-62` (which only touched the four repositories that already *claimed* a bound).
That gap is arguably worse than the one this story fixed, since at least the four `NO_WAIT` repositories
now genuinely bound the wait — but extending `PessimisticLockRetryer` to those repositories is a
separate, unscoped decision, not a mechanical copy-paste of this pattern.

## Related

- [docs/persistence/readme.md](readme.md) — index of this doc set.
- [docs/testing/readme.md](../testing/readme.md) — general integration-test conventions
  (`AbstractIntegrationTest`, fixture id ranges, database reset between tests).
- `src/test/java/com/softropic/skillars/infrastructure/persistence/PessimisticLockRetryerTest.java` —
  direct unit coverage of the retry/backoff/give-up logic itself.
- `_bmad-output/implementation-artifacts/skillars-deferred-62-postgres-lock-timeout-bounded-wait-fix.md`
  — the story that shipped this, including the full empirical trail (throwaway ITs, exact exception
  types, the `BookingServiceConcurrencyIT` fix) in its Dev Agent Record, and the full code-review
  findings (1 decision-needed, 8 patch, 5 defer) in its Review Findings section.
