# Story Deferred-62: Postgres Lock-Timeout Bounded-Wait Fix

Status: done

## Story

As an engineer operating this platform,
I want the four `findByIdForUpdate` repositories that claim a bounded ~5s lock wait
(`CoachProfileRepository`, `BookingRescheduleRequestRepository`, `BookingRepository`,
`SessionPackPurchaseRepository`) to actually enforce one,
so that contention on a coach profile, reschedule request, booking, or session-pack-purchase row
surfaces `ApiAdvice`'s existing clean 409 within a bounded time instead of blocking the requesting
connection indefinitely — matching what every one of these repositories' own comments has claimed
since they were written.

### Why this story exists

`_bmad-output/implementation-artifacts/deferred-work.md`'s `## Deferred from:
skillars-deferred-23-flaky-perf-test-dead-code-and-ops-hygiene-fixes (2026-08-14)` section (no "code
review of" prefix — a near-identically-named section one entry later, `## Deferred from: code review of
skillars-deferred-23-...`, is this story's own code-review pass and contains two unrelated bullets, not
this diagnosis) documents a
fully-diagnosed, empirically-confirmed gap: `jakarta.persistence.lock.timeout` has **zero effect on
PostgreSQL** under this project's Hibernate version (6.6.53.Final). `org.hibernate.dialect
.PostgreSQLDialect.withTimeout(String, int)` only special-cases the `LockOptions.NO_WAIT` and
`LockOptions.SKIP_LOCKED` sentinel values — any finite millisecond value (including every value this
codebase uses, `"5000"`) falls through to the dialect's `default` case and the lock string is returned
completely unchanged: no `WAIT n` clause (Postgres's `FOR UPDATE` syntax has none) and no `SET LOCAL
lock_timeout` statement is ever issued. `PostgreSQLDialect.supportsWait()` returns `false`
unconditionally. This was confirmed empirically, not just by reading source — a throwaway IT
(`SessionPackPurchaseLockTimeoutIT`, written then discarded once the mechanism was understood) held a
competing row lock for 12 seconds against a `findByIdForUpdate`-backed call carrying the 5000ms hint; the
contended call blocked for the full 12 seconds and then completed normally, with no
`PessimisticLockingFailureException` ever thrown.

This was deliberately left unfixed by the story that found it (`skillars-deferred-23`, user-directed:
add the annotation for AC-literal consistency with three siblings, don't expand scope to a
four-repository fix within a story whose AC was scoped to one file) and by two subsequent stories
(`skillars-deferred-49`/`-50`, `skillars-deferred-59`) that each explicitly considered and declined it as
"needs an architecture decision." The project owner has now made that decision (multi-round discussion,
2026-08-24): fix by switching all four repositories to `PESSIMISTIC_WRITE` + `NO_WAIT` (which Postgres
*does* honor — confirmed by the same diagnosis above) with an application-level retry/backoff loop, so
that contention still resolves the way every existing comment on these four repositories already claims
it does, rather than either (a) leaving the misleading comments in place with no behavior change, or (b)
switching to `NO_WAIT` with no retry wrapper, which would turn every fleeting, sub-millisecond overlap
between two legitimate requests into an immediate hard failure instead of a short, bounded wait.

**Split out as its own story** (2026-08-24, same discussion) rather than folded into the sibling bundle
story (`skillars-deferred-63`) that also came out of this discussion: this fix touches 16 call sites
across 10 services (enumerated below) and needs a new shared retry helper plus a new concurrency IT
proving the bounded-wait behavior actually holds — a full story's worth of surface on its own, too large
to bundle safely alongside seven unrelated smaller items.

## Acceptance Criteria

1. **`CoachProfileRepository.findByIdForUpdate`, `BookingRescheduleRequestRepository
   .findByIdForUpdate`, `BookingRepository.findByIdForUpdate`, and `SessionPackPurchaseRepository
   .findByIdForUpdate` are changed from the currently-ineffective `jakarta.persistence.lock.timeout`
   `@QueryHint` (value `"5000"`) to a lock configuration Postgres actually honors for immediate failure
   on contention (`NO_WAIT`), confirmed by a real test — not by reading Hibernate source — before the
   fix is considered proven.**
   - `[src/main/java/com/softropic/skillars/platform/marketplace/repo/CoachProfileRepository.java:28-34]`
   - `[src/main/java/com/softropic/skillars/platform/booking/repo/BookingRescheduleRequestRepository.java:23-31]`
   - `[src/main/java/com/softropic/skillars/platform/booking/repo/BookingRepository.java:188-193]`
   - `[src/main/java/com/softropic/skillars/platform/payment/repo/SessionPackPurchaseRepository.java:18-21]`
   - Before writing the fix: reproduce the current gap live (a throwaway IT holding a competing lock for
     several seconds against the current 5000ms-hint call, observing it still blocks for the full hold
     time) — the same empirical-first discipline `skillars-deferred-23`'s own diagnosis used, and the
     same discipline `SessionPackPurchaseLockContentionIT` already established as this codebase's
     practice for this exact class of finding. Do not trust the Hibernate source-level diagnosis alone.
   - Verify the specific Hibernate mechanism needed to make `NO_WAIT` actually apply, since two things
     touch the lock in each of these methods today: the JPQL `findByIdForUpdate` query itself (carries
     the `@QueryHints` hint today) **and** the `entityManager.refresh(entity, LockModeType
     .PESSIMISTIC_WRITE)` call that immediately follows it at every call site (needed because
     `findByIdForUpdate` returns an already-managed, stale in-memory instance rather than a freshly-read
     one — see the existing comments at each call site). Confirm empirically which of the two actually
     re-acquires the row lock under contention today, and apply the `NO_WAIT`-equivalent configuration
     wherever the lock is genuinely taken (the query hint, the `refresh(...)` call via its
     `Map<String, Object>` properties overload, or both) — do not assume the query-level hint alone is
     sufficient just because that is where the current (ineffective) hint lives.

2. **A shared retry/backoff helper wraps every one of the following 16 call sites**, catching the
   exception `NO_WAIT` throws on immediate contention (a `PessimisticLockingFailureException` or a cause
   Spring maps to it — confirm the exact type empirically per AC1) and retrying the locked read a bounded
   number of times with a short backoff between attempts, so that legitimate near-simultaneous requests
   still succeed the way they do today (waiting briefly for a lock that releases quickly) while
   contention that persists past the retry budget still surfaces `ApiAdvice.pessimisticLockExceptionHandler`
   `[src/main/java/com/softropic/skillars/platform/security/api/ApiAdvice.java:578-583]`'s existing 409 —
   no change needed there, since it already maps any `PessimisticLockingFailureException` regardless of
   cause. Size the retry budget (attempt count × backoff) to land in the same rough ballpark the
   misleading comments already promise (~5s total), not exactly 5000ms — exact timing is not the
   contract, "bounded and short, not indefinite" is.
   - `CoachProfileRepository.findByIdForUpdate` callers: `BookingBatchService.java:378`,
     `BookingDuplicationService.java:63`, `BookingService.java:233`, `BookingService.java:323`,
     `CoachProfileService.java:239`, `AdminCoachEnforcementService.java:105`,
     `RescheduleService.java:208`
   - `BookingRescheduleRequestRepository.findByIdForUpdate` callers: `RescheduleService.java:192`,
     `RescheduleService.java:278`
   - `BookingRepository.findByIdForUpdate` callers: `BookingService.java:637`,
     `BookingPaymentPersistenceService.java:75`, `PaymentPendingSweeper.java:139`
   - `SessionPackPurchaseRepository.findByIdForUpdate` callers: `SessionPackPaymentService.java:104`,
     `PackSessionService.java:53`, `PackSessionService.java:73`, `PackSessionService.java:112`
   - Each of these call sites currently does `repo.findByIdForUpdate(id).orElseThrow(...)` (sometimes
     followed by `entityManager.refresh(entity, PESSIMISTIC_WRITE)`, per AC1) inline in an existing
     `@Transactional` method. The retry loop must wrap the *entire* locked-read-plus-refresh sequence,
     not just the repository call alone — retrying only the repository call while skipping a stale
     `refresh()` would defeat the point.
   - Do not attempt this as 16 independent inline retry loops — extract one shared helper (its exact
     shape — a small `@Component` utility, a functional-interface-based retry method, or similar — is an
     implementation choice for the dev agent) used identically at all 16 sites, so the retry budget and
     behavior stay consistent and auditable in one place rather than drifting across services.

3. **A new or adapted concurrency IT proves the bounded-wait behavior empirically**, mirroring
   `SessionPackPurchaseLockContentionIT`'s pattern (referenced by the ledger item as "what replaced" the
   discarded `SessionPackPurchaseLockTimeoutIT"): one thread holds a competing lock on the same row for
   longer than the retry budget, and the contended call is asserted to fail with a mapped 409 within a
   bounded time (not immediately, and not after an unbounded wait) — reusing this codebase's established
   `BasePaymentIT`/`ShedLock`-adjacent test-timing conventions where applicable. Cover at least one of the
   four repositories end-to-end at the IT layer (the other three sharing the same helper only need unit
   or targeted coverage of the retry loop itself, not a duplicated full concurrency IT each, unless the
   dev agent judges the shared-helper coverage insufficient for the other three's specific call-site
   wiring).

4. **Every existing comment claiming "bounded lock.timeout hint" is corrected to describe the actual
   `NO_WAIT` + retry mechanism**, not the old (never-true) finite-timeout claim. Three of the four
   repositories carry such a comment (confirmed by direct read — `SessionPackPurchaseRepository` has no
   comment above its `findByIdForUpdate` at all, just the bare annotations, so there is nothing to correct
   there): `CoachProfileRepository.java:27-29` (the original claim, which `BookingRescheduleRequestRepository`
   and `BookingRepository` both describe themselves as mirroring — fix this one too, not just its two
   siblings), `BookingRescheduleRequestRepository.java:26-27`, and `BookingRepository.java:188-189`. Also
   fix `ApiAdvice.java:575-577`'s own doc comment, which makes the same claim.

5. **Ledger hygiene**: flip the `deferred-work.md` `## Deferred from:
   skillars-deferred-23-flaky-perf-test-dead-code-and-ops-hygiene-fixes (2026-08-14)` section's (no "code
   review of" prefix — see the "Why this story exists" note above about the near-identically-named sibling
   section) `jakarta.persistence.lock.timeout` item to `[CLOSED by skillars-deferred-62 AC1-AC4]` (or
   delete it outright per this file's own stated convention — "items are deleted outright once they are
   implemented" — since it is the section's only bullet and the section has no other content).

## Tasks / Subtasks

- [x] Task 1: Reproduce the current gap live (AC1)
  - [x] 1.1: Write a throwaway IT holding a competing lock for several seconds against one of the four
        `findByIdForUpdate`-backed call sites carrying the current (ineffective) `"5000"` hint; confirm
        the contended call still blocks for the full hold time rather than failing at ~5s.
  - [x] 1.2: Determine empirically whether the lock is genuinely re-acquired by the JPQL
        `findByIdForUpdate` query, by the subsequent `entityManager.refresh(..., PESSIMISTIC_WRITE)`
        call, or both, at a representative call site (e.g. `RescheduleService.acceptReschedule`'s coach
        lock at `:208-215`).
- [x] Task 2: Switch the four repositories to `NO_WAIT` (AC1)
  - [x] 2.1: `CoachProfileRepository.findByIdForUpdate`
  - [x] 2.2: `BookingRescheduleRequestRepository.findByIdForUpdate`
  - [x] 2.3: `BookingRepository.findByIdForUpdate`
  - [x] 2.4: `SessionPackPurchaseRepository.findByIdForUpdate`
  - [x] 2.5: Apply the same `NO_WAIT`-equivalent configuration to the `entityManager.refresh(...)` calls
        that follow, if Task 1.2 found the refresh call is where the lock is actually re-acquired.
        (Not needed — see Dev Agent Record: Task 1.2 found the query is where the lock is genuinely
        re-acquired; refresh() always runs after the transaction already holds the lock, so it never
        blocks under contention.)
- [x] Task 3: Build and wire the shared retry helper (AC2)
  - [x] 3.1: Implement one shared retry/backoff helper.
  - [x] 3.2: Wire all 16 call sites through it, replacing the inline `.orElseThrow(...)` (and, where
        present, the follow-up `refresh(...)`) with the helper-wrapped equivalent — no behavior change
        to what happens once the lock is successfully acquired, only to how contention is retried.
- [x] Task 4: Verify with a real concurrency IT (AC3)
  - [x] 4.1: New or adapted IT proving bounded-wait-then-409, covering at least one of the four
        repositories end-to-end.
  - [x] 4.2: Run the full targeted test suite for every touched service. Confirmed file names (verified
        at story-creation time — do not assume a `*ServiceTest.java` exists for every touched class, three
        don't): `BookingServiceTest`, `RescheduleServiceTest`, `BookingDuplicationServiceTest`,
        `BookingBatchServiceTest`, `SessionPackPaymentServiceTest`, `PaymentPendingSweeperTest`,
        `CaptureReservationTest`/`CaptureReservationIT` (covers `BookingPaymentPersistenceService`, not a
        `BookingPaymentPersistenceServiceTest`), `PackSessionServiceParityTest` +
        `PackSessionServicePauseTest` (covers `PackSessionService`, split across two files, not one
        `PackSessionServiceTest`), `CoachProfileBuilderIT` (covers `CoachProfileService.saveStep4`'s
        `findByIdForUpdate` call at IT level — no `CoachProfileServiceTest` unit file exists).
        `AdminCoachEnforcementService` has no dedicated test file found at story-creation time — confirm
        during implementation whether coverage exists elsewhere before assuming it's untested. Also run
        the relevant existing concurrency ITs (`BookingServiceConcurrencyIT`, `RescheduleResourceIT`'s
        reschedule-decline race, `PackExtensionIT`'s `extendPack_concurrentRequests_noDuplicateExtension`,
        `SessionPackPurchaseLockContentionIT`) to confirm none of the retry-loop wiring changed their
        existing pass/fail outcomes.
        (Confirmed during implementation: `AdminCoachEnforcementService.suspendCoach` IS covered, by
        `CoachSuspensionIT` in `platform.admin.api` — not caught by the story-creation search since it's
        an IT, not a `*Test.java`. All 12 targeted test classes above pass. `BookingServiceConcurrencyIT`
        needed updating — see Dev Agent Record: its 3 `findByIdForUpdate`-contention tests staged
        determinism by polling `pg_locks` for a genuine blocked-on-lock state, which no longer occurs
        under `NO_WAIT` — replaced with a fixed hold comfortably inside the retry budget plus a
        not-near-instant timing assertion.)
- [x] Task 5: Fix misleading comments (AC4)
- [x] Task 6: Ledger hygiene (AC5)

### Review Findings

- [x] [Review][Decision] Raw JDBC savepoint rollback in `PessimisticLockRetryer` can desync Hibernate's persistence context from the DB, silently losing writes to unrelated entities — On contention, `withBoundedRetry` rolls the JDBC connection back to a savepoint taken before the failed attempt. The JPQL `findByIdForUpdate` query's execution can trigger Hibernate's default auto-flush of any pending dirty entities elsewhere in the same `@Transactional` method before the lock attempt; if that attempt then fails, the retry's raw JDBC rollback undoes those already-flushed writes at the database level, but Hibernate's session is never told — it still considers those entities clean and will not re-flush them at commit. Net effect: a write made earlier in the same transaction as a retried lock attempt can be silently dropped. [`src/main/java/com/softropic/skillars/infrastructure/persistence/PessimisticLockRetryer.java:59-77`] — **Resolved 2026-08-24: fixed.** Added `entityManager.flush()` at the top of each retry attempt, before the savepoint is taken, so the savepoint boundary sits after any prior pending writes are already persisted — a rollback-to-savepoint can then only undo this attempt's own locked read, never an earlier unrelated write.

- [x] [Review][Patch] `maxAttempts`/backoff `@Value` fields are unvalidated [`src/main/java/com/softropic/skillars/infrastructure/persistence/PessimisticLockRetryer.java:40-77`] — fixed: added a `@PostConstruct validateConfig()` that fails fast on `maxAttempts < 1`, `initialBackoffMs <= 0`, `maxBackoffMs < initialBackoffMs`, or `backoffMultiplier < 1.0`.
- [x] [Review][Patch] No jitter in the exponential backoff lets contending threads retry in lockstep [`src/main/java/com/softropic/skillars/infrastructure/persistence/PessimisticLockRetryer.java:73-75`] — fixed: each sleep is randomized to [50%, 100%] of the computed backoff via a new `jitter(...)` helper.
- [x] [Review][Patch] Savepoints are taken every attempt but never explicitly released [`src/main/java/com/softropic/skillars/infrastructure/persistence/PessimisticLockRetryer.java:63-77`] — fixed: `connection.releaseSavepoint(...)` is now called on the success path immediately before returning.
- [x] [Review][Patch] Retry-exhaustion (the moment contention is meant to "surface") is logged at DEBUG, not WARN [`src/main/java/com/softropic/skillars/infrastructure/persistence/PessimisticLockRetryer.java:69`] — fixed: bumped to `log.warn(...)`.
- [x] [Review][Patch] New concurrency IT's upper-bound timing assertion is loose (`elapsedMillis < 6000` vs a documented ~3.2s budget) [`src/test/java/com/softropic/skillars/platform/payment/service/SessionPackPurchaseLockContentionIT.java`] — fixed: tightened to `< 4500`. Verified: `mvn test -Dtest=SessionPackPurchaseLockContentionIT` — 3/3 pass.
- [x] [Review][Patch] `BookingServiceConcurrencyIT`'s fixed `Thread.sleep(600)` reintroduces the flakiness class the deleted `pg_locks`-polling helper existed to eliminate, with no documented rationale for the constant [`src/test/java/com/softropic/skillars/platform/booking/service/BookingServiceConcurrencyIT.java`] — fixed: extracted a shared, javadoc'd `COACH_LOCK_HOLD_MILLIS = 2000` constant (up from 600) used at all 3 call sites, replacing the unexplained magic number with a documented, more CI-safe margin.
- [x] [Review][Patch] The compensating "proof of contention" assertion (`>= 80ms`) is weak — doesn't actually prove a real NO_WAIT collision occurred [`src/test/java/com/softropic/skillars/platform/booking/service/BookingServiceConcurrencyIT.java`] — fixed: both assertions now require the call to have taken at least `COACH_LOCK_HOLD_MILLIS - 300`, proving it genuinely waited out most of the lock hold via retry. Verified: `mvn test -Dtest=BookingServiceConcurrencyIT` — 5/5 pass.
- [x] [Review][Patch] No dedicated unit test for `PessimisticLockRetryer` itself — its retry/backoff/give-up logic is only exercised indirectly, via one IT for one of seven call sites [`src/main/java/com/softropic/skillars/infrastructure/persistence/PessimisticLockRetryer.java`] — fixed: added `PessimisticLockRetryerTest` (new file) with a mocked `EntityManager`/`Session`/`Connection`, covering first-attempt success, retry-then-succeed, retry-budget exhaustion, non-locking-exception passthrough, and all four `validateConfig()` rejection paths. Verified: `mvn test -Dtest=PessimisticLockRetryerTest` — 9/9 pass.

- [x] [Review][Defer] `BookingService.cancelBookingAsParent`'s locked `findByIdForUpdate` read has no `entityManager.refresh(...)`, unlike its sibling call sites [`src/main/java/com/softropic/skillars/platform/booking/service/BookingService.java:645`] — deferred, pre-existing (confirmed via the diff hunk: only the `lockRetryer` wrap was added here; the missing refresh predates this story)
- [x] [Review][Defer] Retry loop sleeps while still holding the transaction's pooled JDBC connection (up to ~3.2s of pool time under contention) [`src/main/java/com/softropic/skillars/infrastructure/persistence/PessimisticLockRetryer.java`] — deferred, consciously-chosen tradeoff of the savepoint retry-in-place design (declarative `NESTED` propagation unavailable per Dev Agent Record)
- [x] [Review][Defer] Only `SessionPackPurchaseRepository` has full IT-level proof of the bounded-wait behavior; the other three repositories rely on unit/targeted coverage only [`src/test/java/com/softropic/skillars/platform/payment/service/SessionPackPurchaseLockContentionIT.java`] — deferred, within AC3's own explicit discretion
- [x] [Review][Defer] `withBoundedRetry`'s `Supplier<T>` idempotency contract is documented in a javadoc comment only, not enforced [`src/main/java/com/softropic/skillars/infrastructure/persistence/PessimisticLockRetryer.java`] — deferred, speculative future-risk (all 16 current call sites are read-only)
- [x] [Review][Defer] A JDBC `setSavepoint`/rollback-to-savepoint call itself failing propagates unretried as an opaque 500 [`src/main/java/com/softropic/skillars/infrastructure/persistence/PessimisticLockRetryer.java:63-64,72`] — deferred, arguable as acceptable behavior for a genuine infrastructure failure

## Dev Notes

**This is an architecture-level behavior change, not a mechanical patch.** `NO_WAIT` fails immediately on
any contention, including a legitimate few-millisecond overlap between two unrelated requests that would
previously (in principle, per the comments) have waited briefly and both succeeded. The retry/backoff
wrapper is not optional polish — without it, this fix would make the system *more* fragile under load,
not less, by turning routine brief overlaps into user-visible 409s. Get the retry budget right before
calling this done; "roughly matches the ~5s the comments already promised" is the bar, not an exact
number.

**Existing precedent for this exact investigative pattern.** `skillars-deferred-23`'s own diagnosis wrote
a real IT (`SessionPackPurchaseLockTimeoutIT`) to hold a lock for 12 seconds and empirically prove the
5000ms hint does nothing, rather than trusting the Hibernate source reading alone — the same discipline
Task 1 of this story mandates for confirming the fix works, not just the bug.

**Why the `entityManager.refresh(...)` call matters.** Every one of the four repositories' call sites that
needs the locked row's *current* state (not just a lock on the row) already documents this trap: because
`findByIdForUpdate` is JPQL and the target row is frequently already a managed JPA instance from an
earlier `findById`/`findByUserId` in the same method, Hibernate takes the DB lock but returns the
existing in-memory instance with its stale field values — reading a status field off it without the
follow-up `refresh(entity, PESSIMISTIC_WRITE)` would silently check the stale value and could never
observe a concurrent change. (See `BookingService.java:235`, `RescheduleService.java:210-214`,
`MessagingService.java:320` for the existing versions of this comment.) This means the *effective* lock
acquisition point for the NO_WAIT/retry contract may be the `refresh()` call, not the repository query
itself — Task 1.2 exists to settle this before Task 2/3 commit to a design.

**`ApiAdvice.pessimisticLockExceptionHandler` needs no code change** (`ApiAdvice.java:578-583`) — it
already catches `org.springframework.dao.PessimisticLockingFailureException` unconditionally and maps it
to a 409, regardless of what triggered it. Only its doc comment (`:575-577`) needs correcting per AC4.

**Scope discipline — repositories NOT in scope.** Many other `findByIdForUpdate` methods exist in this
codebase (`VideoQuotaRepository`, `CoachReviewRepository`, `MessageRepository`, etc. — see the full
call-site grep this story's creation pass ran) but carry **no** `jakarta.persistence.lock.timeout` hint
at all today, meaning they already wait indefinitely with no bounded-timeout claim to fix or preserve.
That is a separate, larger, unscoped concern (arguably worse than this story's four repositories, since
at least these four *claim* a bound) — explicitly out of scope here, matching the original ledger item's
own scope (it named exactly these four).

### Project Structure Notes

Repository query changes: `platform.marketplace.repo`, `platform.booking.repo`, `platform.payment.repo`.
Service-layer call-site changes: `platform.booking.service` (4 files), `platform.marketplace.service`,
`platform.admin.service`, `platform.payment.service` (3 files). New shared retry helper: place per this
project's existing convention for a cross-module utility (check for a `platform.common`/`infrastructure`
equivalent before creating a new package). `ApiAdvice` (`platform.security.api`) gets a comment-only
change.

### References

- `_bmad-output/implementation-artifacts/deferred-work.md`, `## Deferred from:
  skillars-deferred-23-flaky-perf-test-dead-code-and-ops-hygiene-fixes (2026-08-14)` (no "code review of"
  prefix) — the full original diagnosis this story closes, including the exact `PostgreSQLDialect
  .withTimeout` mechanism and the discarded `SessionPackPurchaseLockTimeoutIT` reproduction.
- `SessionPackPurchaseLockContentionIT` — the existing test this story's new/adapted IT (AC3) should
  mirror in structure and timing conventions.

## Dev Agent Record

### Agent Model Used

Claude Sonnet 5 (claude-sonnet-5)

### Debug Log References

Three throwaway ITs were written, run against a real Testcontainers Postgres, and deleted once their findings were captured below (per Task 1's empirical-first discipline and the "throwaway, discarded once understood" precedent this story's Dev Notes cite):

- `LockTimeoutInvestigationIT` (part 1): confirmed the current `"5000"` hint blocks for the full hold duration (8s hold, ≥7000ms observed) — the gap is real and live, not just a source-reading conclusion.
- `LockTimeoutInvestigationIT` (part 2, same class rewritten): confirmed the JPQL `findByIdForUpdate` query itself is where the lock is genuinely re-acquired (blocked ≥5000ms under a 6s hold), and the follow-up `entityManager.refresh(..., PESSIMISTIC_WRITE)` is fast (<1000ms) because by the time it runs the transaction already holds the row lock. This settled Task 1.2 and meant Task 2.5 (applying NO_WAIT to the refresh call too) was not needed.
- `LockTimeoutInvestigationIT` (part 3, verifying the NO_WAIT fix + retry design): confirmed `NO_WAIT` (hint value `"0"`) fails fast (<3000ms under an 8s hold) with `org.springframework.dao.PessimisticLockingFailureException` (cause chain: Hibernate `PessimisticLockException` → `org.postgresql.util.PSQLException`, SQLState `55P03`, "could not obtain lock on row"). Also proved a plain catch/retry within the same transaction fails at commit (`UnexpectedRollbackException`, since a NOWAIT failure aborts the whole Postgres transaction), that Spring's declarative `Propagation.NESTED` is unavailable (`NestedTransactionNotSupportedException` — `DefaultJpaDialect` has no savepoint support), and that a manually-managed raw JDBC savepoint via Hibernate's `Session.doWork` *does* let the same outer transaction recover and retry successfully — the design `PessimisticLockRetryer` implements.

### Completion Notes List

- AC1: All four repositories (`CoachProfileRepository`, `BookingRescheduleRequestRepository`, `BookingRepository`, `SessionPackPurchaseRepository`) switched from `@QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "5000"))` to `value = "0"` (the `LockOptions.NO_WAIT` sentinel `PostgreSQLDialect.withTimeout(...)` actually honors — confirmed by decompiling the same `hibernate-core-6.6.53.Final.jar` bytecode the story-review pass did). Task 1.2's empirical finding (see Debug Log) meant no change was needed to the `entityManager.refresh(...)` calls — they always run after the transaction already holds the lock, so they never block under contention.
- AC2: New `com.softropic.skillars.infrastructure.persistence.PessimisticLockRetryer` (`@Component`) wraps a `Supplier<T>` with a bounded retry loop (default 8 attempts, 100ms initial backoff, 1.6x multiplier, 800ms cap — total budget ≈3.2s, in the ballpark of the ~5s the old comments promised). Each attempt takes a JDBC savepoint via `Session.doWork` before running the supplier; on `PessimisticLockingFailureException` it rolls back to that savepoint and retries, letting the caller's own transaction recover in place. Wired through all 16 call sites (verified via `grep -c "lockRetryer.withBoundedRetry"` = 16) across the 10 services the AC named — `BookingBatchService`, `BookingDuplicationService`, `BookingService` (×3), `CoachProfileService`, `AdminCoachEnforcementService`, `RescheduleService` (×3), `BookingPaymentPersistenceService`, `PaymentPendingSweeper`, `SessionPackPaymentService`, `PackSessionService` (×3) — replacing each inline `.orElseThrow(...)`/`.orElse(null)` (and, where present, the follow-up `refresh(...)`) with the helper-wrapped equivalent. No behavior change once a lock is acquired.
- AC3: Two new tests added to `SessionPackPurchaseLockContentionIT` (chosen as the IT to extend, per its own javadoc being the thing that "replaced" the discarded `SessionPackPurchaseLockTimeoutIT" the ledger item referenced): `deductSession_briefContention_succeedsAfterBoundedRetry` (1.2s hold, well inside the retry budget — the deduction still succeeds, no 409) and `deductSession_prolongedContention_failsWithBounded409AfterRetryBudgetExhausted` (8s hold, past the ~3.2s budget — fails with `PessimisticLockingFailureException`, bounded well under the hold time, not immediately). The other three repositories share the identical `PessimisticLockRetryer` helper and are covered by their own services' targeted test suites (below) rather than a duplicated full concurrency IT each, per the AC's own allowance.
- AC4: Corrected the misleading "bounded lock wait"/lock.timeout comments on `CoachProfileRepository`, `BookingRescheduleRequestRepository`, `BookingRepository` (the three that actually carry one — `SessionPackPurchaseRepository` has none, confirmed by direct read, nothing to fix there) and `ApiAdvice.pessimisticLockExceptionHandler`'s doc comment, all now describing the real `NO_WAIT` + `PessimisticLockRetryer` mechanism. No code change to the handler itself — it already catches `PessimisticLockingFailureException` unconditionally.
- AC5: Deleted the `## Deferred from: skillars-deferred-23-flaky-perf-test-dead-code-and-ops-hygiene-fixes (2026-08-14)` section outright from `deferred-work.md` — it was that section's only bullet, matching the file's own stated convention ("items are deleted outright once they are implemented") over flipping to `[CLOSED by ...]`.
- Unanticipated fix required during Task 4: `BookingServiceConcurrencyIT`'s 3 `findByIdForUpdate`-contention tests staged determinism by polling `pg_locks` for a genuine "blocked on this transaction's lock" state (`awaitAnotherSessionBlockedOnCoachProfileLock`). Under `NO_WAIT`, a contended attempt never enters that blocked state — it fails immediately, leaving no `pg_locks` trace — so all 3 tests failed (`AssertionError: No other session was observed blocked...`) the first time the full suite ran post-fix. Replaced the polling helper (now dead, deleted) with a fixed lock-hold duration comfortably inside the retry budget, plus a new "not near-instant" timing assertion on the contended call to preserve mutation-detection rigor (a missing/broken lock would otherwise let the call complete suspiciously fast). All 3 tests pass; reasoning recorded in the tests' own updated comments.
- Targeted validation only, per `docs/validation-strategy.md` — `mvn verify` not run. All touched classes' unit tests and the listed concurrency ITs were run individually and are green (see below); GitHub CI is the full-suite gate.

### File List

**Main (production) code:**
- `src/main/java/com/softropic/skillars/infrastructure/persistence/PessimisticLockRetryer.java` (new)
- `src/main/java/com/softropic/skillars/platform/marketplace/repo/CoachProfileRepository.java`
- `src/main/java/com/softropic/skillars/platform/booking/repo/BookingRescheduleRequestRepository.java`
- `src/main/java/com/softropic/skillars/platform/booking/repo/BookingRepository.java`
- `src/main/java/com/softropic/skillars/platform/payment/repo/SessionPackPurchaseRepository.java`
- `src/main/java/com/softropic/skillars/platform/booking/service/BookingBatchService.java`
- `src/main/java/com/softropic/skillars/platform/booking/service/BookingDuplicationService.java`
- `src/main/java/com/softropic/skillars/platform/booking/service/BookingService.java`
- `src/main/java/com/softropic/skillars/platform/marketplace/service/CoachProfileService.java`
- `src/main/java/com/softropic/skillars/platform/admin/service/AdminCoachEnforcementService.java`
- `src/main/java/com/softropic/skillars/platform/booking/service/RescheduleService.java`
- `src/main/java/com/softropic/skillars/platform/payment/service/BookingPaymentPersistenceService.java`
- `src/main/java/com/softropic/skillars/platform/payment/service/PaymentPendingSweeper.java`
- `src/main/java/com/softropic/skillars/platform/payment/service/SessionPackPaymentService.java`
- `src/main/java/com/softropic/skillars/platform/payment/service/PackSessionService.java`
- `src/main/java/com/softropic/skillars/platform/security/api/ApiAdvice.java`

**Test code:**
- `src/test/java/com/softropic/skillars/infrastructure/persistence/PessimisticLockRetryerTest.java` (new — added during code review, AC2 unit coverage)
- `src/test/java/com/softropic/skillars/platform/payment/service/SessionPackPurchaseLockContentionIT.java` (adapted — 2 new tests, class javadoc updated)
- `src/test/java/com/softropic/skillars/platform/booking/service/BookingServiceConcurrencyIT.java` (staging mechanism updated for 3 tests, dead helper removed)
- `src/test/java/com/softropic/skillars/platform/booking/service/RescheduleServiceTest.java`
- `src/test/java/com/softropic/skillars/platform/booking/service/BookingDuplicationServiceTest.java`
- `src/test/java/com/softropic/skillars/platform/booking/service/BookingBatchServiceTest.java`
- `src/test/java/com/softropic/skillars/platform/booking/service/BookingServiceTest.java`
- `src/test/java/com/softropic/skillars/platform/payment/service/PaymentPendingSweeperTest.java`
- `src/test/java/com/softropic/skillars/platform/payment/service/SessionPackPaymentServiceTest.java`
- `src/test/java/com/softropic/skillars/platform/payment/service/PackSessionServiceParityTest.java`
- `src/test/java/com/softropic/skillars/platform/payment/service/PackSessionServicePauseTest.java`
- `src/test/java/com/softropic/skillars/platform/payment/service/CaptureReservationTest.java`

**Documentation/tracking:**
- `_bmad-output/implementation-artifacts/deferred-work.md` (AC5 ledger hygiene)
- `_bmad-output/implementation-artifacts/sprint-status.yaml` (status → review)

## Change Log

| Date | Description |
|------|-------------|
| 2026-08-24 | Story created via story-creation process. Split out from a broader product-decision discussion covering 8 `deferred-work.md` items needing owner input (multi-round Q&A, 2026-08-24) — this story carries the one item (`jakarta.persistence.lock.timeout` has no effect on Postgres across 4 repositories) large enough to need its own dedicated story (16 call sites, a new shared retry helper, a new concurrency IT); the other 7 items ship together in `skillars-deferred-63`. |
| 2026-08-24 | Story-review adjustments applied, status remains ready-for-dev. `story-review.md` filed 3 findings, none blocking, all fixed. Finding 1/Medium: "14 call sites across 9 services" undercounted AC2's own enumerated list, which was itself correct at 16 sites across 10 services — corrected every summary count to match (the enumerated lists in AC2 were never wrong). Finding 2/Low-Medium: "Why this story exists," AC5, and the References section all cited `## Deferred from: code review of skillars-deferred-23-...` as the diagnosis's source section; the correct section is the near-identically-named `## Deferred from: skillars-deferred-23-...` (no "code review of" prefix, one entry earlier in the file) — corrected all three citations and added a disambiguating note, since the cited section's own two bullets are unrelated to this story. Finding 3/Low: AC4 claimed all four repositories carry a misleading "bounded lock wait" comment; `SessionPackPurchaseRepository` has none (bare annotations only), and the two AC4 explicitly cited as carrying the claim were themselves mirroring a third, uncited comment on `CoachProfileRepository` — reworded to name exactly the three repositories that need a fix, including the previously-uncited `CoachProfileRepository` comment. All three findings independently re-verified against live source (and, for the core Hibernate/Postgres claim, against decompiled `hibernate-core-6.6.53.Final.jar` bytecode) before applying — everything else in the story, including the central technical premise, all four repositories' annotations, the `ApiAdvice` handler, the representative call sites, the refresh-trap mechanism, and every test-file existence claim, was independently re-verified as accurate with no changes needed. |
| 2026-08-24 | Dev-story implementation complete, status → review. All 6 tasks / AC1-AC5 shipped: the four repositories switched to `NO_WAIT`; a new `PessimisticLockRetryer` (JDBC-savepoint-backed, since Spring's declarative `NESTED` propagation has no savepoint support here) wired through all 16 call sites; `SessionPackPurchaseLockContentionIT` gained two bounded-wait tests; misleading lock-wait comments corrected on all three repositories that carried one plus `ApiAdvice`; the closed `deferred-work.md` ledger item deleted outright per its own convention. `BookingServiceConcurrencyIT`'s 3 `findByIdForUpdate`-contention tests needed updating — their `pg_locks`-polling staging assumed genuine DB-level blocking, which `NO_WAIT` no longer produces. Full detail in Dev Agent Record above. |
| 2026-08-24 | Code review: 3 parallel layers (Blind Hunter, Edge Case Hunter, Acceptance Auditor) found 1 decision-needed, 8 patch, 5 defer findings; Acceptance Auditor confirmed zero AC violations. Decision resolved (fix now): `PessimisticLockRetryer` now flushes the persistence context before each attempt's savepoint, closing a Hibernate/JDBC desync window that could have silently dropped unrelated pending writes on a retried attempt. All 8 patches applied: config validation (`@PostConstruct`), backoff jitter, explicit savepoint release, WARN-level give-up logging, a tightened IT timing bound, a shared/documented `COACH_LOCK_HOLD_MILLIS` constant replacing a bare `Thread.sleep(600)` in `BookingServiceConcurrencyIT`, strengthened contention-proof assertions, and a new `PessimisticLockRetryerTest` unit test class. 5 items deferred to `deferred-work.md` (pre-existing `cancelBookingAsParent` stale-read gap; connection-hold-during-sleep tradeoff; partial IT coverage across the 4 repositories; unenforced `Supplier` idempotency contract; savepoint-failure edge case). Verified: `mvn test -Dtest=PessimisticLockRetryerTest` (9/9), `mvn test -Dtest=SessionPackPurchaseLockContentionIT` (3/3), `mvn test -Dtest=BookingServiceConcurrencyIT` (5/5), plus `mvn compile`/`mvn test-compile`. |
