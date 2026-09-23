# Story: Lock Contention, Transactional Safety & Config-Bounds Hardening

**Story Key:** `skillars-deferred-132-lock-contention-transactional-safety-and-config-bounds-hardening`
**Epic:** Deferred Work
**Priority:** Medium (one genuine unbounded-wait risk carried across four prior stories, a real
marketplace-tier reconciliation gap, a rollback-only transaction trap, plus five lower-severity
lock/config/test hygiene items and one empirical CI-deadlock verification pass).
**Status:** ready-for-dev
**Created:** 2026-09-24

---

## Context

Sourced from a full front-to-back audit of `deferred-work.md` (all dated sections, oldest to
newest) plus `story-review.md`, run immediately after `skillars-deferred-131` (PR #219) merged to
master at `2842df52`. Scope requested: **Genuine one-off bugs & gaps, Lock contention & deadlock
risks, Transactional safety, Config bounds & enforcement, Pre-existing design issues** — explicitly
bundled into one non-small story per this project's standing convention.

Every candidate below was cross-checked against the ledger's own `[CLOSED by ...]` /
`[DECIDED: accepted risk ...]` / `[Review][Patch] IMPLEMENTED ...` markers and excluded if already
resolved — including all 8 `code review of skillars-deferred-130` bullets and both
`story review of skillars-deferred-130` bullets (closed by `skillars-deferred-131`), the
deferred-126/127 accepted-risk items, and the deferred-123/125 items blocked on a production deploy
that hasn't happened. All remaining line citations below were re-verified directly against
`HEAD = 2842df52` while drafting this story (several had drifted from the ledger's own citations,
corrected inline).

**Owner decisions taken live (AskUserQuestion) before drafting:**

1. `ReviewFlagService.flag()`'s blocking `FOR UPDATE` lock (3rd time raised) — **convert to
   NOWAIT+retry**, matching the module's established `PessimisticLockRetryer` convention, not a 4th
   decline.
2. `syncMarketplaceTier`'s residual stale-tier gap on the upgrade path (see fact-check below) —
   **add a reconciliation sweep**, not an inline retry or accepted-risk framing.
3. GDPR erasure's `REQUIRES_NEW` connection-acquisition wait (deferred 4 stories running, 128→131,
   fix shape now fully scoped) — **build the dedicated capacity fix now**, not a 5th decline.
4. `submitReview`'s rollback-only physical transaction under a hypothetical outer caller —
   **`REQUIRES_NEW` propagation**, not a test-only guard or continued documentation.
5. `ConfigBounds.BoundedKey`'s missing `default` field — **fix only the two review config keys'
   call sites** (see Fix 9 below — narrower than originally scoped once verified, see its Context),
   not a full `BoundedKey` record widening.
6. `PessimisticLockRetryer`'s JVM-wide cumulative retry counter — **add per-call-site attribution**
   (Micrometer `Tags`), not leave the new AC3 concurrency-IT assertions unable to prove what they
   claim.
7. Deferred-131's CI-deadlock fix shipped against a hypothesized, never-empirically-reproduced root
   cause — **attempt real reproduction now**, not accept the "mechanism closed, not exhaustively
   proven" framing permanently.
8. A cross-cutting Instancio convention sweep (new deferred-131 test classes hand-build entities) —
   **left out of this story**; cross-cutting drift, not itself a bug, would bloat an already-large
   story.

**Pre-implementation fact-check on Decision 2 (done before asking, not guessed):**
`SubscriptionService.persistCoachSubscription`'s Decision-3 catch-and-defer (shipped in
`skillars-deferred-131`, lines 168-182) prevents the *worst* outcome — losing the payment row after
Stripe has already charged — by catching `PessimisticLockingFailureException` around
`syncMarketplaceTier` and logging `COACH_SUBSCRIBE_TIER_SYNC_DEFERRED` instead of propagating. But
neither of the two scheduled jobs that also call `syncMarketplaceTier`
(`SubscriptionChangeApplicator.applyPendingChanges` at `SubscriptionService.java:473-497`,
`SubscriptionGracePeriodChecker`'s `checkPastDueGracePeriod` at `:536-560`) re-syncs a coach outside
its own narrow trigger (a *pending scheduled downgrade*, or *past-due* status). A coach whose
`subscribeCoach` upgrade hits lock contention at exactly the wrong moment is left reading a stale
`marketplace.coach_subscriptions` tier — defaulting to `SCOUT` per `getCoachSubscriptionTier`'s own
`skillars-deferred-131` fix — **indefinitely**, until they happen to trigger one of those two
schedulers' narrow conditions. This is a real, currently-unclosed gap, not fully resolved by
Decision 3 as the ledger's AC5 note implies — Fix 3 below closes it directly.

---

## AC1: Lock Contention & Deadlock Risks

### Fix 1 — `ReviewFlagService.flag()`'s lock-order inversion with `GdprErasureService.erase()` (document, not fix)

**Context:** `ReviewFlagService.java:92` (`reviewRepository.findByIdForUpdate` — locks
`coach_reviews` first) → `:135` (`coachRatingService.recompute(review.getCoachId())` — eventually
writes `coach_profiles`). `GdprErasureService.erase()` touches the opposite order within one
transaction: `:168` (`coachProfileRepository.findByUserId(userId)` + save — writes the erasing
user's own `coach_profiles` row) → `:182-183`
(`coachReviewRepository.deleteNonApprovedByAuthorId`/`anonymiseApprovedReviews` — writes
`coach_reviews` rows *authored by* that same user). Re-verified: line numbers drifted 1-2 lines from
the ledger's own citations (`:166`→`:168`, `:178-179`→`:182-183`), mechanism unchanged.

**Why still unreachable (verified, not assumed):** a genuine deadlock needs `erase(coachX)`'s own
profile row to be the same row `flag()`'s `recompute` call would write — i.e., the flagged review
must be authored by `coachX` **and** be a review of `coachX`'s own profile. A coach cannot review
their own profile (existing guard elsewhere in the reviews module), so the two transactions can
never actually contend on the same row pair. Confirmed still true at HEAD; no code change in this
story removes that guard.

**Decision (this story does not re-litigate the two prior "accepted, revisit trigger not met"
findings — record explicitly per AC5, consistent with how deferred-131 handled the identical
situation for its own M5 items):** continue to document as a latent, currently-unreachable design
issue rather than restructure either method's lock order. Reordering `erase()` to touch
`coach_reviews` before `coach_profiles` would itself require auditing every other table `erase()`
touches for the same risk — a much larger change than this latent, unreachable issue justifies today.

**Test:** none — no behavior change. Record in AC5 ledger closeout, 3rd consecutive story to
re-confirm "still open, still unreachable."

### Fix 2 — `CoachReviewRepository.findByIdForUpdate` converted to NOWAIT + `PessimisticLockRetryer`

**Context:** `CoachReviewRepository.java:23-25` — plain `@Lock(LockModeType.PESSIMISTIC_WRITE)` with
no `@QueryHints`, the reviews module's only remaining blocking lock (every other locked read in this
codebase, e.g. `CoachProfileRepository.findByIdForUpdate`, already carries
`@QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "0"))` + a
`PessimisticLockRetryer.withBoundedRetry` wrapper at its call site). Sole call site:
`ReviewFlagService.java:92`, inside `flag()`.

**Fix:**
1. Add the same `@QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "0"))`
   to `findByIdForUpdate` in `CoachReviewRepository.java`, making it NOWAIT like every other locked
   read in the codebase.
2. Inject `PessimisticLockRetryer` into `ReviewFlagService` (not currently a dependency — confirm no
   manual-constructor test instantiations before adding the field, `grep -rn "new
   ReviewFlagService(" src/test`).
3. Wrap the `findByIdForUpdate` call at `:92` in `lockRetryer.withBoundedRetry(...)`, mirroring
   every other NOWAIT lock site's exact shape (lock acquisition alone inside the retried lambda;
   `flag()`'s existing writes — the insert at `:70-81`-equivalent post-Fix-5-restructure lines, and
   the auto-hold check/writes — stay exactly where they are today, after the lock call returns).
4. This is a **new** `.withBoundedRetry(` call site: `PessimisticLockRetryerCallSiteAuditTest
   .EXPECTED_CALL_SITE_COUNT` must move from `33` to `34`.

**Test:** extend `ReviewFlagServiceConcurrencyIT` — the existing blocking-`FOR UPDATE` contention
test (using `ConcurrencyLockWaitSupport.awaitBlockingLockWaiter`'s `pg_locks` poll) must be rewritten
to the NOWAIT+retry discipline instead (`awaitFirstLockAttempt` +
`assertGenuineLockRetryOccurred`/its Fix 6 replacement below — see Fix 6's own note on this test's
correct signal once that fix lands), since the underlying lock mechanism this story changes is
exactly what that test observes. Re-run live against Testcontainers Postgres to confirm the NOWAIT
path is genuinely exercised (mirrors `skillars-deferred-131`'s own live-verification precedent for
an identical claim on `SubscriptionServiceConcurrencyIT`).

### Fix 3 — Marketplace-tier reconciliation sweep for deferred `syncMarketplaceTier` syncs

**Context:** see the Context section's fact-check above. `SubscriptionService`'s two existing
scheduled jobs (`SubscriptionChangeApplicator.applyPendingChanges`,
`SubscriptionGracePeriodChecker.checkPastDueGracePeriod`, both in
`platform/payment/service/`, each already `@SchedulerLock`-guarded) only touch coaches matching
their own narrow trigger. There is no general "does `marketplace.coach_subscriptions.tier` still
match `payment.coach_subscriptions.tier`" sweep anywhere in this codebase.

**Fix:** add a third scheduled job, `SubscriptionTierReconciliationScheduler` (new file, same
package, same `@SchedulerLock` pattern as its two siblings), calling a new
`SubscriptionService.reconcileMarketplaceTiers()`:

```java
public void reconcileMarketplaceTiers() {
    List<PaymentCoachSubscription> active = transactionTemplate.execute(
        status -> paymentCoachSubscriptionRepository.findAllByStatusIn(List.of("ACTIVE", "TRIALLING")));
    if (active == null) active = List.of();
    for (PaymentCoachSubscription sub : active) {
        try {
            transactionTemplate.executeWithoutResult(status -> {
                Optional<CoachSubscription> marketplace =
                    coachSubscriptionRepository.findByCoachId(sub.getCoachId());
                if (marketplace.isEmpty() || !marketplace.get().getTier().equals(sub.getTier())) {
                    syncMarketplaceTier(sub.getCoachId(), sub.getTier());
                    log.warn("[COACH_TIER_RECONCILED coachId={} tier={}]", sub.getCoachId(), sub.getTier());
                }
            });
        } catch (Exception e) {
            log.error("Failed to reconcile marketplace tier for coach {}", sub.getCoachId(), e);
        }
    }
}
```

`findAllByStatusIn` is a new `PaymentCoachSubscriptionRepository` query method (the existing
`findByStatusAndPastDueSinceBefore` is a different, narrower query — do not reuse it). Reuses
`syncMarketplaceTier`'s already-locked write path (Fix 4 from `skillars-deferred-131`) — no new
lock discipline needed here, this job simply calls the same method the two siblings already call.
Confirm at implementation time whether the active-coach volume needs cursor-based paging (mirror
whichever of the two sibling schedulers' own sizing comments applies once the coach-subscription
row count is checked).

**Missing-profile note:** `syncMarketplaceTier` (post-`skillars-deferred-131` Fix 4) already throws
on a missing `coach_profiles` row rather than proceeding unlocked — this sweep's per-row `try/catch`
means one such coach logs and is skipped, not a batch-wide failure, consistent with the two sibling
schedulers' own per-row isolation.

**Test:** new `SubscriptionTierReconciliationSchedulerIT` (or extend
`SubscriptionSchedulerIsolationTest`'s existing pattern) — a coach with a `PaymentCoachSubscription`
at `INSTRUCTOR` and a stale/absent `marketplace.coach_subscriptions` row gets corrected by one sweep
pass; a coach whose tiers already match causes no write (assert no `COACH_TIER_RECONCILED` log line
for that coach).

### Fix 4 — Bound GDPR erasure's `REQUIRES_NEW` connection-acquisition wait

**Context:** `GdprErasureService.erase()` (`@Transactional(propagation = Propagation.REQUIRES_NEW)`)
and its internal `requiresNewTemplate` (`GdprErasureService.java:135-138`,
`PROPAGATION_REQUIRES_NEW` via a hand-built `TransactionTemplate`) both acquire a connection from
the single shared Hikari pool (`application.yaml:172-191` — `maximum-pool-size: 25`,
`connection-timeout: 30000`). Under sustained pool exhaustion, `erase()`'s attempt to open its own
new transaction can block for the full 30s Hikari `connection-timeout` with no bound of its own —
raised at `skillars-deferred-128` (Hazard 2), re-confirmed 3 times since (129, 130, 131) as
"accepted risk," this story's owner decision is to finally close it.

**Fix shape — two viable mechanisms, pick one at implementation time (this story does not
prescribe a single answer, since the tradeoff is genuinely architectural):**

- **(a) Dedicated secondary `HikariDataSource` + `EntityManagerFactory` + `PlatformTransactionManager`**
  scoped only to `GdprErasureService`'s two `REQUIRES_NEW` entry points — a small pool (2-3
  connections) with a short `connectionTimeout` (e.g. 5000ms), same JDBC URL/credentials as the
  primary pool. **Real wrinkle to weigh:** the repositories `GdprErasureService` calls inside those
  blocks (`playerProfileRepository`, `performanceReportRepository`, nine others per the method's own
  javadoc) are shared Spring Data repositories used elsewhere under the **primary** `EntityManagerFactory`
  — Spring Data JPA repositories bind to one `EntityManagerFactory` per `@EnableJpaRepositories` scan
  unless explicitly split by `entityManagerFactoryRef`, which this project does not currently do. A
  second EMF would need its own `@EnableJpaRepositories(entityManagerFactoryRef = ...,
  basePackages = ...)` scoped to a **duplicate** set of repository interfaces bound to the secondary
  pool, since a repository interface cannot be bound to two EMFs simultaneously — likely more
  invasive than this fix is worth unless done narrowly (only the ~11 repositories this one method
  touches).
- **(b) Bounded-wait guard without a second pool:** wrap just the connection/transaction-acquisition
  step in a short `Future`-based timeout (e.g. run `requiresNewTemplate.execute(...)`'s opening on a
  dedicated single-thread executor and `future.get(5, SECONDS)`, throwing a named
  `GdprEraseConnectionAcquisitionTimeoutException` on expiry, letting `erase()`'s own existing
  skip-and-alert semantics handle it the same way it already handles `PessimisticLockingFailureException`
  today). Smaller blast radius, no new datasource/EMF wiring, but the underlying Hikari pool
  exhaustion condition it was waiting on is not itself relieved — it just fails fast instead of
  hanging, which is still a real improvement over today's unbounded wait.

**Recommend (b) unless the dev agent's own investigation at implementation time finds (a)'s
repository-splitting cost lower than expected** — (b) directly targets the actual complaint ("no
bound of its own on this specific wait") without the multi-EMF architecture surgery, and is
consistent with this project's general preference for the narrowest fix that closes the named risk
(see `skillars-deferred-131`'s own Fix 4 precedent: "close the race at its source" was interpreted as
the minimal lock, not a broader redesign).

**Test:** an IT that saturates the pool (or mocks the acquisition path) to force the
timeout/guard to trip, asserting `erase()` surfaces a clear, alerted failure rather than hanging for
30s. Needs its own Testcontainers `@ServiceConnection` wiring if route (a) is chosen — confirm at
implementation time.

### Fix 5 — `PessimisticLockRetryer`'s retry counter gets per-call-site attribution

**Context:** `PessimisticLockRetryer.java:182-186` (`recordRetries`) increments a single JVM-wide
`persistence.lock_retry.retries` counter (line 184) shared across all 33 (34 after Fix 2)
`.withBoundedRetry(` call sites. `skillars-deferred-131`'s new `ConcurrencyLockWaitSupport
.assertGenuineLockRetryOccurred` polls this same counter to prove a *specific* lock site actually
retried — but any unrelated in-flight retry elsewhere in the same JVM (5+ other async pools are live
during the test suite) can satisfy it, so the assertion can pass without the intended site ever
retrying.

**Fix:** add a `String lockName` parameter to `PessimisticLockRetryer.withBoundedRetry` (or an
overload, to avoid touching all 33/34 existing call sites' signatures if this codebase's convention
favors overloads elsewhere — confirm at implementation time), and tag the counter/timer with it:

```java
meterRegistry.counter("persistence.lock_retry.retries", "lock", lockName).increment(retries);
```

Every existing call site passes a short, stable name identifying its lock (e.g.
`"CoachProfileRepository.findByIdForUpdate"`), and `ConcurrencyLockWaitSupport
.assertGenuineLockRetryOccurred` (and its `awaitFirstLockAttempt` companion) gain a `lockName`
parameter to poll only that tag. Additive — no behavior change to the retry loop itself, purely
instrumentation.

**Test:** update `ConcurrencyLockWaitSupport`'s own unit/IT coverage (if any exists) plus re-run
every concurrency IT that currently uses `assertGenuineLockRetryOccurred`
(`CoachProfileServiceConcurrencyIT`, `SubscriptionServiceConcurrencyIT`, this story's Fix 2 rewrite
of `ReviewFlagServiceConcurrencyIT`) to confirm each now asserts on its own tagged counter, not the
shared one — and deliberately trigger an unrelated concurrent retry during one such test run to
confirm the assertion no longer passes on a false signal (the regression this fix is meant to close).

### Fix 6 — Empirically verify the CI reset-deadlock's hypothesized root cause

**Context:** `skillars-deferred-131` AC4 shipped `DatabaseResetTestExecutionListener
.quiesceAsyncExecutors` (`:210` onward, draining every `ThreadPoolTaskExecutor` bean before the
reset transaction) against a **structural, not empirical** diagnosis — `pom.xml`'s single-fork
config plus a grep confirming `RadarCompositeCalculationService`/`ReportGenerationService` share
`reportExecutor` and both write `player_profiles`-adjacent state. The fix was never confirmed
against a real reproduction of the original deadlock (master CI run `35891248593`).

**Task:** attempt a real reproduction now that a fix has already shipped, both to validate it
targets the right actor and to rule out a second one:
1. Temporarily add diagnostic logging around `RadarCompositeCalculationService`'s
   `@Async("reportExecutor")` entry point (`:82`) and `ReportGenerationService`'s equivalent
   (`:201`) — log entry/exit with the test thread name and a timestamp.
2. Run the `platform.development.**` package (231 tests per `skillars-deferred-131`'s own count)
   repeatedly (aim for at least 20-30 consecutive runs, matching the "several reruns — this is a
   race" expectation already recorded in the ledger) watching for any overlap between a logged
   async task still running and the next test class's `beforeTestMethod` reset firing.
3. If reproduced: confirm `quiesceAsyncExecutors` actually closes the observed window (it should,
   since it drains before the reset transaction opens) and note the confirmed mechanism precisely in
   this story's Dev Agent Record, mirroring `skillars-deferred-123`'s AC5 precedent for stating a
   confirmed (not hypothesized) mechanism.
4. If **not** reproduced after a reasonable number of attempts: say so plainly, same honesty
   standard as before — this remains "mechanism closed by structural reasoning, not exhaustively
   reproduced," now with a documented, bounded reproduction attempt behind that statement rather
   than none at all.
5. Remove the diagnostic logging before this story's PR (temporary investigation aid only, not a
   permanent addition) unless the investigation finds it worth keeping in a reduced form — decide at
   implementation time.

**Test:** none beyond the repeated CI/local runs themselves — this AC is the verification, not a
new automated test (same framing `skillars-deferred-131` used for its own AC4).

---

## AC2: Transactional Safety

### Fix 7 — `submitReview`'s rollback-only transaction under a hypothetical outer caller

**Context:** `ReviewSubmissionService.java:31` (`@Transactional`, class-level) — `submitReview`
(`:41-85`) catches `DataIntegrityViolationException` at its `saveAndFlush` call (`:69-70`) and
returns a clean `ALREADY_SUBMITTED` `OperationNotAllowedException`. This is safe **only** because
`ReviewResource` (the REST layer) never opens its own transaction around this call — Spring's
default propagation (`REQUIRED`) makes `submitReview`'s `@Transactional` the outermost boundary
today. If any future caller wraps this in its own `@Transactional` method, the underlying Postgres
transaction — already marked rollback-only by the DIVE at flush time — surfaces as
`UnexpectedRollbackException` at the outer boundary instead of the clean 4xx this method's own catch
block promises.

**Fix:** change the class-level `@Transactional` to a method-level
`@Transactional(propagation = Propagation.REQUIRES_NEW)` on `submitReview` specifically (leave the
class-level `@Transactional` as-is for every other method in this file — `updateReview` and any
others have no such trap and don't need the isolation). This guarantees `submitReview` always runs
in its own physical transaction regardless of caller nesting, so a caught-and-translated
`DataIntegrityViolationException` can never propagate rollback-only state to an outer transaction.

**Test:** new test proving the fix: wrap a call to `submitReview` in an outer
`@Transactional`/`TransactionTemplate` block (simulating the hypothetical future caller the ledger
worried about) and a concurrent duplicate submission; assert the loser still gets a clean
`ALREADY_SUBMITTED`, not `UnexpectedRollbackException`, from *inside* that outer transaction. This is
the regression test the ledger noted was missing ("no current violator; constraint lives only in a
test javadoc") — this story adds the violator scenario as a real test rather than leaving the
constraint undocumented-but-untested.

### Fix 8 — `GdprErasureService` fully hydrates `PerformanceReport` rows to read one column

**Context:** `GdprErasureService.deletePlayerDevelopmentData` — `performanceReportRepository
.findByPlayerIdOrderByGeneratedAtDesc(playerId)` (currently at `:664`, drifted from the ledger's
`:621` after `skillars-deferred-128`/`130`'s own restructuring of this method) loads every
`PerformanceReport` entity in full just to read `getStorageKey()` (`:665-668`) before
`deleteAllByPlayerId` (`:671`) bulk-deletes them. Benign today (memory-proportional to a single
player's report count, not a correctness bug), but unnecessary entity hydration on a path this story
is already touching for Fix 4.

**Fix:** add a projection query to `PerformanceReportRepository`:
```java
@Query("SELECT p.storageKey FROM PerformanceReport p WHERE p.playerId = :playerId AND p.storageKey IS NOT NULL")
List<String> findStorageKeysByPlayerId(@Param("playerId") Long playerId);
```
Replace the `findByPlayerIdOrderByGeneratedAtDesc(...).forEach(...)` block with
`childBlobKeys.addAll(performanceReportRepository.findStorageKeysByPlayerId(playerId));` — the
`ORDER BY generatedAtDesc` was never load-bearing for this use (the keys are only added to a set for
later enqueue, order irrelevant), and the null-check moves into the query's `WHERE` clause instead of
the loop body.

**Test:** unit or IT assertion that a player with a mix of `PENDING_UPLOAD` (null `storageKey`) and
`READY` (non-null) reports enqueues only the non-null keys — same assertion the current code already
implicitly makes, now against the projection path.

### Fix 9 — GDPR erasure's `lock_timeout` config re-read once per child, on the caller's connection

**Context:** `GdprErasureService.deletePlayerDevelopmentData` (`:628` onward) reads
`configService.getBoundedLong(ConfigBounds.GDPR_ERASE_STATEMENT_LOCK_TIMEOUT_SECONDS.key(), 5L, 2L,
120L)` at `:630-631`, **before** `requiresNewTemplate.executeWithoutResult` opens — i.e., still on
the **outer** (`eraseParentChildren`'s) transaction's connection. `eraseParentChildren`'s loop
(`:415`) calls `deletePlayerDevelopmentData` once per child, so this read happens once per child, all
on the same outer connection that already holds the `main."user"`/`player_profiles` lock for that
parent. `ConfigService.refreshCache()` (`ConfigService.java:306`) is `private synchronized` and does
its own `configRepository.findAll()` (`:311`) — a cache-TTL-boundary refresh triggered by one child's
read can, in the worst case, force every other `ConfigService` caller in the JVM to wait behind that
one `synchronized` block while this thread is also holding a database lock and a pooled connection.

**Fix:** read `lockTimeoutSeconds` **once**, in `eraseParentChildren` (or `erase`, whichever already
has access to the full child list before the loop starts), and pass it as a parameter into
`deletePlayerDevelopmentData(Long playerId, long lockTimeoutSeconds)`. One config read per `erase()`
call instead of one per child — removes the per-child repetition while keeping the existing
"read before the lock acquisition, not after" placement this method's own javadoc already documents
as intentional (mirroring `recalculateComposite`'s convention, per that javadoc's own citation).

**Test:** unit test asserting `configService.getBoundedLong` is invoked exactly once per `erase()`
call regardless of child count (mock `ConfigService`, assert invocation count with 0, 1, and 3+
children).

---

## AC3: Config Bounds & Enforcement

### Fix 10 — Point `ReviewFlagService`/`ReviewSubmissionService` at their existing `ConfigBounds` constants

**Context (corrected from the ledger's framing — verified at HEAD, more precise and cheaper than
originally scoped):** `ConfigBounds.REVIEWS_SUBMISSION_WINDOW_DAYS` and
`ConfigBounds.REVIEWS_AUTO_HOLD_FLAG_THRESHOLD` **already exist** (`ConfigBounds.java:164-171`),
are already registered in `HAS_CODE_DEFAULT` (`:343-344`) and `ALL` (`:373-374`), and their
`[min, max]` bounds (`1L-365L`, `1L-1000L`) already exactly match the raw literals at both call
sites. The gap is narrower than "add BoundedKey constants" — it's that the two call sites never
switched to reference them:

- `ReviewFlagService.java:127` —
  `configService.getBoundedInt("reviews.autoHoldFlagThreshold", 3, 1, 1000)`
- `ReviewSubmissionService.java:180` —
  `configService.getBoundedInt("reviews.submissionWindowDays", 14, 1, 365)`

Both pass a **raw string literal key**, invisible to `ConfigBoundsEnumCoverageTest` and to
`ConfigStartupAssertion`'s `failFast` handling for `REVIEWS_SUBMISSION_WINDOW_DAYS` (`failFast =
true` in its `BoundedKey` — a typo in the literal today would silently create a phantom,
permanently-defaulted key with **no** boot-time protection despite the registry already declaring
this key fail-fast).

**Fix:** replace each raw string literal with the existing constant's `.key()`:
```java
int threshold = configService.getBoundedInt(
    ConfigBounds.REVIEWS_AUTO_HOLD_FLAG_THRESHOLD.key(), 3, 1, 1000);
```
```java
int windowDays = configService.getBoundedInt(
    ConfigBounds.REVIEWS_SUBMISSION_WINDOW_DAYS.key(), 14, 1, 365);
```
No production behavior change (the string values are identical) — purely closes the
drift-detection/coverage/fail-fast gap. `ConfigBounds.BoundedKey`'s missing `default` field
(the broader item the ledger also flagged) is **not** touched by this fix, per the owner decision
above — record that residual explicitly in AC5, not silently implied as resolved.

**Test:** none beyond existing coverage — `ConfigBoundsEnumCoverageTest` and
`ConfigStartupAssertionTest` (if it exists; confirm) already exercise these keys once they're
referenced by constant instead of literal. Add a quick unit assertion in each service's existing
test class that the correct key name is used (guards against a future accidental revert to a raw
literal).

---

## AC4: Genuine One-off Bugs & Gaps

### Fix 11 — `PessimisticLockRetryerCallSiteAuditTest` audits call sites, not lock sites

**Context:** the test (per its own javadoc, `PessimisticLockRetryerCallSiteAuditTest.java:20-59`)
enumerates every `.withBoundedRetry(` occurrence and regex-audits each lambda's raw source against a
DENYLIST — but it never enumerates `findByIdForUpdate(` occurrences directly. A brand-new
`findByIdForUpdate` call added **without** a `withBoundedRetry` wrapper is entirely invisible to
this test, since it only ever looks *inside* retry wrappers it already found, never asks "does every
lock call have one." All current `findByIdForUpdate` sites are correctly wrapped today (confirmed
during this story's own audit) — this is a latent test blind spot, not a live defect.

**Fix:** add a second assertion to the same test class: enumerate every `findByIdForUpdate(`
occurrence across `src/main/java` (same source-scanning approach the existing test already uses) and
assert each one's call site also matches a `.withBoundedRetry(` occurrence in the same method — a
simple "does this method's source contain both tokens" check is sufficient given the existing test's
own established rigor level; do not over-engineer a full AST match where the existing test doesn't
either.

**Test:** this fix *is* a test change. Prove it catches the regression it's meant to catch by
temporarily adding an unwrapped `findByIdForUpdate` call in a scratch location, confirming the new
assertion fails, then removing it before commit.

### Fix 12 — Two of four branch×reason combinations untested in GDPR erasure's lock-timeout handling

**Context:** `GdprErasureService`'s lock-timeout/contention error handling has two call sites
(`erase`'s own `PLAYER` branch, `eraseParentChildren`'s loop) each distinguishing two failure
causes (`CHILD_CONTENDED` vs. `CHILD_DELETE_LOCK_TIMEOUT`, per the constants at
`GdprErasureService.java:129-130`) — four branch×reason combinations total. Existing tests
(`GdprErasureServiceTest.java`, `GdprErasureIT.java`) cover only two.

**Fix:** no production code change — add the two missing test cases (one per call site, the reason
not yet covered) to whichever of the two existing test classes already covers that call site's other
reason, mirroring its existing test's exact setup/mocking shape.

**Test:** this fix *is* the test addition — confirm all four combinations are now exercised and
green.

---

## AC5: Ledger Closeout

Update `deferred-work.md`:

- Mark every item this story resolves (Fixes 2-12, i.e. all except Fix 1 which stays documented-only)
  `[CLOSED by skillars-deferred-132-... (PR #<number>)]`, following the established convention.
- **Fix 1** (lock-order inversion) and the **`BoundedKey` missing-default residual** (not touched by
  Fix 10) both stay explicitly **open** — record both plainly in a new
  `## Last audit: 2026-09-24 (skillars-deferred-132 dev-story completion)` section, mirroring
  `skillars-deferred-131`'s own AC5 precedent for triaging items a story deliberately leaves
  unresolved rather than letting AC5 imply blanket closure.
- If Fix 6's investigation reproduces (or fails to reproduce) the CI deadlock's root cause, record
  the actual finding precisely — not the hypothesis — mirroring `skillars-deferred-123`'s AC5
  precedent.
- If Fix 4 lands via approach (a) (secondary EntityManagerFactory) rather than (b) (bounded-wait
  guard), record which was chosen and why, since this story deliberately left that choice open.

---

## Tasks

- [ ] **Task 1 (AC1):** Diff-check every cited line against current `HEAD` immediately before
      touching each file (this story's citations may drift further if anything else lands on
      `master` between creation and implementation).
- [ ] **Task 2 (AC1):** Implement Fix 1 — documentation-only, no code change; confirm the
      self-review guard this fix's reachability argument depends on is still in place before writing
      the closeout note.
- [ ] **Task 3 (AC1):** Implement Fix 2 (`CoachReviewRepository.findByIdForUpdate` → NOWAIT +
      `PessimisticLockRetryer` in `ReviewFlagService.flag()`) + rewritten
      `ReviewFlagServiceConcurrencyIT` contention test; bump
      `PessimisticLockRetryerCallSiteAuditTest.EXPECTED_CALL_SITE_COUNT` 33→34 (35 if Fix 5's
      overload approach also adds a site — confirm at implementation time which fix lands first).
- [ ] **Task 4 (AC1):** Implement Fix 3 (`SubscriptionTierReconciliationScheduler` +
      `SubscriptionService.reconcileMarketplaceTiers`) + new IT.
- [ ] **Task 5 (AC1):** Implement Fix 4 (GDPR erasure `REQUIRES_NEW` connection-acquisition bound) —
      decide (a) vs. (b) per the story's own weighing, implement, add its IT.
- [ ] **Task 6 (AC1):** Implement Fix 5 (`PessimisticLockRetryer` per-call-site retry attribution) —
      update all existing `.withBoundedRetry(` call sites with a lock name if the parameter-not-overload
      route is chosen; update `ConcurrencyLockWaitSupport` and every concurrency IT that uses
      `assertGenuineLockRetryOccurred`.
- [ ] **Task 7 (AC1):** Implement Fix 6 (empirical CI-deadlock reproduction attempt) — temporary
      diagnostic logging, repeated `platform.development.**` runs, document the actual finding.
- [ ] **Task 8 (AC2):** Implement Fix 7 (`submitReview` → `REQUIRES_NEW`) + new outer-transaction
      regression test.
- [ ] **Task 9 (AC2):** Implement Fix 8 (`PerformanceReport` projection query) + test.
- [ ] **Task 10 (AC2):** Implement Fix 9 (single config read per `erase()` call, passed down) + test.
- [ ] **Task 11 (AC3):** Implement Fix 10 (point both review config call sites at their existing
      `ConfigBounds` constants) + quick key-name assertions.
- [ ] **Task 12 (AC4):** Implement Fix 11 (`PessimisticLockRetryerCallSiteAuditTest` lock-site
      coverage) — verify it catches a deliberately-introduced unwrapped call before removing the
      scratch test case.
- [ ] **Task 13 (AC4):** Implement Fix 12 (two missing branch×reason GDPR erasure tests).
- [ ] **Task 14 (AC5):** Ledger closeout in `deferred-work.md`, including Fix 1's and the
      `BoundedKey`-default residual's explicit still-open notes, plus Fix 6's actual finding.
- [ ] **Task 15:** Full targeted regression sweep (at minimum: `platform.reviews.**`,
      `platform.payment.**`, `platform.admin.**` [`GdprErasureService`],
      `platform.development.**` [Fix 6's investigation target],
      `PessimisticLockRetryerCallSiteAuditTest`, `ConfigBoundsEnumCoverageTest`). No `mvn verify`
      run locally per this project's standing convention (`docs/validation-strategy.md`) — GitHub
      CI is the sole full-verification gate.

## Dev Notes

- This story deliberately leaves two implementation-time design choices open rather than
  prescribing a single mechanism: Fix 4's datasource-vs-guard choice, and Fix 5's
  parameter-vs-overload choice for `withBoundedRetry`. Both are flagged inline with the tradeoffs
  already researched — decide and record the choice made, don't silently pick one without noting why.
- Fix 2 and Fix 5 both touch `PessimisticLockRetryerCallSiteAuditTest`'s expected call-site count —
  implement Fix 2 first (or track both bumps together) to avoid a transient miscount mid-implementation.
- Fix 3 adds a new scheduled job — mirror `SubscriptionChangeApplicator`/`SubscriptionGracePeriodChecker`'s
  exact `@SchedulerLock` naming and cadence conventions (check their annotations directly before
  choosing a schedule for the new job; don't guess a cron expression).
- No frontend changes anticipated — confirm via `git status --short` before opening the PR, per this
  project's established `frontend-tests` label convention.

## Dev Agent Record

_To be completed during `/bmad-dev-story`._

## Change Log

- 2026-09-24: Story created via `/bmad-create-story`, branched off `master` post-`skillars-deferred-131`-merge
  (PR #219, squash-merged as `2842df52`). Sourced from a full front-to-back audit of `deferred-work.md`
  (delegated to a research fork to keep the ledger's raw content out of the main session's context)
  covering Genuine one-off bugs & gaps, Lock contention & deadlock risks, Transactional safety,
  Config bounds & enforcement, and Pre-existing design issues, per this project's "do not create
  small stories" convention. All `[CLOSED by ...]`/`[DECIDED: accepted risk ...]` items excluded;
  12 open candidates identified, 7 requiring a real owner decision (resolved live via
  AskUserQuestion, recorded in Context above), 5 mechanical/well-scoped. One candidate (a
  cross-cutting Instancio test-convention sweep) was deliberately left out as out-of-theme scope
  creep. A pre-drafting fact-check on the marketplace-tier reconciliation candidate (Fix 3)
  corrected the ledger's implied "Decision 3 already covers this" framing — Decision 3 only
  prevents payment-row loss, it does not itself re-sync a stale tier on the upgrade path, which
  Fix 3 now closes directly. A second correction narrowed Fix 10's scope after verifying
  `ConfigBounds.java` directly: the two `BoundedKey` constants the ledger implied needed creating
  already exist and are already registered in `HAS_CODE_DEFAULT`/`ALL` — the actual gap is just
  that both call sites reference them by raw string literal instead of by constant, a cheaper and
  lower-risk fix than originally scoped. 5 ACs bundled: AC1 (6 lock-contention/deadlock items,
  including one documentation-only and one investigation-only), AC2 (3 transactional-safety fixes),
  AC3 (1 config-bounds fix), AC4 (2 genuine one-off test-coverage gaps), AC5 (standard ledger
  closeout). Story file:
  `skillars-deferred-132-lock-contention-transactional-safety-and-config-bounds-hardening.md`.
  Branch: `story/deferred-132-lock-safety-hardening`.
