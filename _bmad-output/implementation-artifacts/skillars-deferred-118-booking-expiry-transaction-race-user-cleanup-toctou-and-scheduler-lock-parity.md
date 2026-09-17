# Story: Booking-Expiry Transaction Race, User-Cleanup TOCTOU & Scheduler-Lock Parity

**Story Key:** `skillars-deferred-118-booking-expiry-transaction-race-user-cleanup-toctou-and-scheduler-lock-parity`
**Epic:** Deferred Work
**Priority:** High (AC1 is an active correctness bug with misleading logs and needless churn on every run that hits it; AC2 is a real cross-account data-loss race)
**Status:** ready-for-dev
**Created:** 2026-09-17

---

## Provenance & Scoping (read before starting)

`deferred-work.md`'s "genuine one-off bugs & gaps" bucket has been repeatedly confirmed exhausted by
audits from 2026-09-09 through 2026-09-17 (most recently by `skillars-deferred-117`'s own post-merge
prune, which found nothing further to close). Per the established pattern this ledger's last three
stories set (`skillars-deferred-115`/`-116`/`-117`), when the ledger itself runs dry the next story is
sourced from a **fresh ad-hoc audit** applying the same lens — transaction-boundary / TOCTOU /
batch-memory patterns on `@Scheduled` classes — to a part of the codebase the prior three audits did
not yet cover.

`skillars-deferred-115` audited `platform.notification.*` + `platform.video.*`. `skillars-deferred-116`
audited the payment module's two subscription schedulers. `skillars-deferred-117` AC3 closed a third
payment scheduler (`SessionPackForfeitureScheduler`) found via the same lens. **This story's own
research (2026-09-17) applied the identical lens to every `@Scheduled` class in `platform.booking`,
`platform.messaging`, `platform.security`, and `platform.development` that no prior audit had yet
touched** — 12 classes read in full, each traced against every other code path that can concurrently
write the same rows. Two genuine, currently-reachable bugs were found and independently re-verified
against HEAD before this story was drafted (not accepted on the research pass's own assertion — see
each AC's "Independently re-verified" note); three low-risk consistency gaps were found alongside them.

**Cleared, not picked up (confirmed safe, so the next audit does not re-investigate):**
`MessageModerationSweeper.sweepOne` (re-fetches via `findByIdForUpdate` + re-asserts state before
writing — textbook correct); `SessionPackExpiryNotifier.notifyExpiringPacks` (writes the batch-read
entity back directly, but `SessionPackPurchase.version` makes any concurrent mutation fail with a
caught `OptimisticLockingFailureException` rather than silently overwrite);
`PaymentPendingSweeper.sweepOne` (already takes the same pessimistic lock the real capture path
takes, with an explicit post-lock re-check); `QuickCompleteTimeoutService`'s batch-read fields (traced
every write path to `SessionCompletionData` — it is written once, at creation, and never mutated
afterward, so there is no edit path that could make the scheduler's copy diverge); `NeglectedSkillProcessor.processPlayer`
(entirely self-contained inside one transaction, no earlier batch-load gap); `BookingReminderScheduler`
(already the reference-correct implementation for this exact bug class — see AC1's Dev Notes for why
this matters); `SluSnapshotAppliedRetentionService` and `AuthCleanupService` (bulk idempotent deletes,
no per-row logic at all); `NeglectedSkillDetectionService` (delegates all per-player logic to the
already-cleared `NeglectedSkillProcessor`).

**Owner decision needed before implementation:** none — all three ACs below are direct fixes with an
established sibling pattern already in this codebase to mirror. No AC requires a tradeoff decision.

---

## Acceptance Criteria

### AC1 — `BookingExpiryScheduler.expireStaleRequests` no longer loses successful auto-expiries to a concurrent booking's `UnexpectedRollbackException`

**The bug, traced end to end:**

`BookingExpiryScheduler.expireStaleRequests()` (`BookingExpiryScheduler.java:43-44`) carries a
method-level `@Transactional` around its *entire loop* over the stale-booking batch. Inside that loop
it calls `bookingService.transition(booking.getId(), BookingEvent.DECLINE, ...)`
(`BookingExpiryScheduler.java:50-51`). `BookingService.transition` is itself `@Transactional`
(`BookingService.java:151`, default `REQUIRED` propagation) — called from inside the scheduler's
already-open transaction, it **joins** that same physical transaction rather than opening its own.

`transitionInternal` (`BookingService.java:156-174`) takes a pessimistic row lock, then calls
`bookingStateMachine.validate(currentStatus, event)` (`BookingService.java:167`), which throws
`BookingStateTransitionException` — an unchecked `RuntimeException`
(`BookingStateTransitionException.java:3`) — if the booking's *current* status is no longer
`REQUESTED`. A real, everyday concurrent writer exists: a coach accepting the same booking
(`BookingService.java:429` accept path) or a parent cancelling it (`BookingService.java:794`
cancel path) between the scheduler's batch `SELECT`
(`bookingRepository.findRequestedBookingsOlderThan`) and this booking's turn in the loop.

When that race hits, `validate()` throws inside the shared physical transaction. Spring's default
rollback rule marks that transaction `rollbackOnly=true` **immediately**, regardless of the fact that
the scheduler's own per-iteration `try/catch` (`BookingExpiryScheduler.java:48-66`) swallows the
exception and logs `"Failed to auto-expire booking {}"`. The loop continues to the next booking as if
nothing structural happened. When `expireStaleRequests()` finally returns normally, Spring's
transaction interceptor attempts to **commit** the outer transaction (no exception propagated past the
method boundary) — finds `rollbackOnly=true` — and throws `UnexpectedRollbackException` at that commit
attempt, uncaught, out of the whole `@Scheduled` method.

**The consequence is not "one booking fails to expire."** Every booking in that run that *did*
successfully decline earlier in the loop — whose `INFO "Auto-expired booking {}"` log line has already
been written — has its DB write rolled back too, because it shares the one physical transaction with
the booking that raced. The log record is now a lie: it says a booking was expired and its
`BookingExpiredEvent` was published, but neither actually committed. The next run five minutes later
re-selects the same still-`REQUESTED` bookings and tries again — a permanent stall only if the same
race recurs every run, otherwise a delayed, silently-duplicated retry with a misleading audit trail in
the interim.

**This codebase already fixed the identical bug in two siblings, and left behind the proof.**
`BookingReminderScheduler`'s own class javadoc (`BookingReminderScheduler.java:43-58`) documents fixing
this *exact* pattern — "one transaction per booking, never one per batch" — for
`processReminderWindows`, and `BandwidthResetService.resetMonthlyBandwidth` was fixed the same way for
the identical reason (`SchedulerLockTransactionOrderingIT.java`'s javadoc on
`bandwidthResetService_isNotTransactional_...`). `BookingExpiryScheduler` was never updated to match —
it is still the one method-level-`@Transactional` scheduler `SchedulerLockTransactionOrderingIT`
explicitly pins as *keeping* the old shape (see Dev Notes below — that test itself needs updating as
part of this AC's fix).

**Independently re-verified against HEAD (2026-09-17, story creation):** read
`BookingExpiryScheduler.java`, `BookingService.transition`/`transitionInternal`,
`BookingStateTransitionException.java`, and `BookingReminderScheduler.java` directly; confirmed
`transition()`'s default `REQUIRED` propagation, confirmed `BookingStateTransitionException extends
RuntimeException` (unchecked, default-rollback), and confirmed the two already-fixed siblings' javadoc
describes the identical mechanism this AC closes.

**Fix:** give `expireStaleRequests()` a per-booking `TransactionTemplate` scope — the exact shape
`BookingReminderScheduler.processReminderWindows` already uses — and remove the method-level
`@Transactional`. The batch `SELECT` itself should run in its own short transaction (mirror
`BookingReminderScheduler.idsOf`'s pattern, or simplify to a single `TransactionTemplate.execute`
around the `findRequestedBookingsOlderThan` call), then each booking's `transition()` +
`eventPublisher.publishEvent(...)` runs inside its own `transactionTemplate.executeWithoutResult(...)`
block, with the existing per-iteration `try/catch` staying exactly where it is (now able to actually
do its job, since a thrown exception inside one booking's own transaction no longer poisons any other
booking's transaction).

**Verified by:**
- A new unit test proving the actual regression this AC exists to prevent: given two stale bookings
  where the second's `transition()` call throws `BookingStateTransitionException` (simulating a
  concurrent accept/cancel), the first booking's `transition()` + event-publish still happen (i.e. are
  not undone) and the scheduler does not propagate the exception out of `expireStaleRequests()`. Follow
  `BookingReminderSchedulerTest`'s established `@Mock PlatformTransactionManager` +
  `new TransactionTemplate(transactionManager)` pattern (do not mock `TransactionTemplate` itself) so
  the real per-item transaction boundary is exercised, not mocked away.
- `SchedulerLockTransactionOrderingIT.bookingExpiryScheduler_shedLockAdvisorIsOutsideTheTransactionAdvisor`
  must be updated: `expireStaleRequests` no longer carries a method-level `@Transactional`, so the
  "ShedLock outside the transaction advisor" assertion this test makes no longer applies (there is no
  method-level transaction advisor to be outside of anymore). Replace it with an `assertNotTransactional`
  call, mirroring `bookingReminderScheduler_isNotTransactional_soOneBadEnqueueCannotRollBackTheBatch`
  exactly (same helper method, new javadoc explaining why per this AC). Update the test class's own
  javadoc, which currently states `BookingExpiryScheduler` is the one scheduler that *keeps* the old
  shape — that claim becomes false with this fix.
- Existing `BookingExpirySchedulerTest` suite re-run green after updating its constructor call for the
  new `TransactionTemplate` dependency (mirror `BookingReminderSchedulerTest`'s constructor exactly).
- `@SchedulerLock` stays unchanged — this AC touches only the transaction boundary, not locking.

---

### AC2 — `UserAdminService.removeNotActivatedUsers` no longer deletes a user who activated between the sweep's select and its per-user delete

**The bug, traced end to end:**

`findExpiredUsers` (`UserAdminService.java:122-129`) selects users where `activated=false AND
createdDate < cutoffDate`. Each matching login is then passed to `deleteUserInTransaction`
(`UserAdminService.java:135-140`), which runs in its own `REQUIRES_NEW` transaction and re-fetches the
row (`userRepository.findOneByLogin(login)`) — but never re-checks `user.isActivated()` before calling
`userRepository.delete(user)`.

A real, everyday concurrent writer exists: every registration flow's email-verification step —
`ParentRegistrationService.java:149`, `PlayerRegistrationService.java:149`,
`CoachRegistrationService.java:145` (`user.setActivated(true)`), and
`UserRegistrationService.java:85` (`user.activate()`) — can run at any time, including in the narrow
window between this scheduler's batch select and a specific user's turn in the per-user delete loop.
A user whose account sits right at the `accountActivationExpirationDays` cutoff (default 3 days,
`SecurityProperties.java:26`) and who clicks their verification link in that window is now activated in
the DB, but this scheduler's stale in-memory read still says `activated=false` for that row — and the
re-fetch inside `deleteUserInTransaction` re-reads the *current*, now-activated row, then deletes it
anyway, because nothing in that method looks at the `activated` field at all. A legitimate,
freshly-activated account is destroyed.

**Independently re-verified against HEAD (2026-09-17, story creation):** read
`UserAdminService.java` directly — confirmed `deleteUserInTransaction` has no `activated` guard — and
confirmed all four registration-flow activation call sites cited above by direct grep and read.

**Fix:** add a guard inside `deleteUserInTransaction` that re-checks `!user.isActivated()` on the
freshly re-fetched row before calling `delete`, skipping (with a DEBUG log, matching this codebase's
established skip-silently-on-no-longer-eligible convention, e.g.
`SessionPackForfeitureScheduler`'s AC3 fix) if the user has since activated.

**Verified by:**
- No existing test file covers `UserAdminService.removeNotActivatedUsers` /
  `deleteUserInTransaction` at all (confirmed by search — this is new coverage, not an extension).
  Add a `UserAdminServiceTest` (Mockito unit test, matching this module's established
  `@ExtendWith(MockitoExtension.class)` shape) covering: (a) an expired, still-unactivated user is
  deleted; (b) a user returned by `findExpiredUsers` but found activated on the `deleteUserInTransaction`
  re-fetch (simulating the race) is skipped, not deleted; (c) the existing happy-path/batch-loop
  behavior (multiple users, one continues on a per-user exception) is unchanged.
- `deleteUserInformation` (the admin-triggered manual delete, `UserAdminService.java:46-50`) is **not**
  touched by this AC — that path is an explicit admin action on a specific login, not a stale
  batch-driven state check, and has no equivalent race.

---

### AC3 — `@SchedulerLock` parity for the three `@Scheduled` classes that lack it

**Finding:** `QuickCompleteTimeoutService.processExpiredQuickCompletes`
(`QuickCompleteTimeoutService.java:35-36`), `MessageRetentionScheduler.runRetention`
(`MessageRetentionScheduler.java:28`), and `RadarCompositeDlqProcessor.process`
(`RadarCompositeDlqProcessor.java:34`) are the only three `@Scheduled` classes in the codebase with no
`@SchedulerLock`, while every sibling scheduler with the same fixed-delay/cron shape does carry one
(`BookingExpiryScheduler`, `BookingReminderScheduler`, `SessionPackForfeitureScheduler`,
`SubscriptionChangeApplicator`, `SubscriptionGracePeriodChecker`, `VideoLifecycleScheduler`, etc.).

**Current real-world risk is low, not zero, and this AC is deliberately scoped as a cheap
consistency/defense-in-depth fix, not an emergency patch** — this deployment is single-instance today
(the same fact `skillars-deferred-117` AC4's Dev Notes established for `RateLimitingService`'s
"not cluster-safe" limitation: `docker-compose` runs one `app` container). Two of the three are
already benign under a hypothetical second instance regardless (`QuickCompleteTimeoutService` already
catches `OptimisticLockingFailureException` per-item, `MessageRetentionScheduler`'s bulk deletes are
naturally idempotent — a second concurrent run just deletes zero rows). **`RadarCompositeDlqProcessor`
is the one with a real latent correctness gap worth naming explicitly:** its `findClaimedBatch()` query
(`RadarCompositeDlqRepository.java` — `SELECT * FROM development.radar_composite_dlq WHERE status =
'CLAIMED'`) is not scoped to the calling invocation's own claim, and `RadarCompositeDlqEntry` carries no
`@Version` field — so two concurrent invocations (today: two overlapping ticks with no lock preventing
it, not two pods) could each read and unconditionally overwrite the other's `status`/`attempts`/
`lastError`/`nextRetryAt` on the same row with no optimistic-lock protection at all. Adding
`@SchedulerLock` closes this fully by preventing the concurrent invocation in the first place — a
narrower fix scoping `findClaimedBatch` to a claim-token column is out of scope for this AC (it fixes
nothing `@SchedulerLock` doesn't already fix, at real schema-change cost).

**Fix:** add `@SchedulerLock` to all three, sized the same way `skillars-deferred-116` AC3 sized its
two: `lockAtMostFor` at a real worst-case-runtime multiple (not a copy-pasted constant — compute each
scheduler's own worst case from its batch size × per-item timeout, the same arithmetic
`skillars-deferred-116`'s Dev Notes walks through), `lockAtLeastFor` at a value that prevents
back-to-back reruns inside the same fixed-delay/cron window (mirror the `PT2M` convention every sibling
already uses unless a scheduler's own cadence makes that wrong).

**Verified by:**
- `SchedulerLockTransactionOrderingIT`-style reasoning is not required here (none of the three stack
  `@Transactional` at the method level, so there is no advisor-ordering hazard to guard) — a simpler
  assertion suffices: extend or add unit tests confirming the `@SchedulerLock` annotation is present
  with the chosen `lockAtMostFor`/`lockAtLeastFor` values (reflection-based, matching this codebase's
  existing `ConfigBoundsEnumCoverageTest`-style "assert an annotation's attributes" convention if one
  exists for schedulers, or a plain reflection assertion in each scheduler's own test class if not).
- Existing `RadarCompositeDlqProcessorTest` and `QuickCompleteTimeoutServiceTest` suites re-run green
  (no behavior change to the methods' bodies, only the added annotation).
- No `MessageRetentionSchedulerTest` exists today — do not add one solely for this AC unless a
  reflection-based annotation check is the cheapest way to pin it; if so, a minimal new test file is
  acceptable, matching this module's established naming convention.

---

## Tasks/Subtasks

- [ ] **Task 1 — AC1: `BookingExpiryScheduler` per-booking transaction scope**
  - [ ] Add a `TransactionTemplate` dependency to `BookingExpiryScheduler`, remove the method-level
        `@Transactional`, wrap the batch `SELECT` and each per-booking `transition()` +
        `eventPublisher.publishEvent(...)` in their own `TransactionTemplate` scopes, mirroring
        `BookingReminderScheduler` exactly
  - [ ] Update `SchedulerLockTransactionOrderingIT`: replace
        `bookingExpiryScheduler_shedLockAdvisorIsOutsideTheTransactionAdvisor` with an
        `assertNotTransactional` call; update the class-level javadoc's claim that
        `BookingExpiryScheduler` keeps the old shape
  - [ ] Add the new regression test proving a mid-batch `BookingStateTransitionException` no longer
        rolls back an earlier booking's successful expiry
  - [ ] Update `BookingExpirySchedulerTest`'s constructor call for the new dependency; full suite
        re-run green

- [ ] **Task 2 — AC2: `UserAdminService` re-check before delete**
  - [ ] Add an `!user.isActivated()` guard inside `deleteUserInTransaction`, skip with a DEBUG log if
        the user has since activated
  - [ ] Create `UserAdminServiceTest` covering: expired-and-unactivated deleted; race-activated-between-
        select-and-delete skipped; batch loop continues past a per-user exception

- [ ] **Task 3 — AC3: `@SchedulerLock` parity**
  - [ ] Add `@SchedulerLock` to `QuickCompleteTimeoutService.processExpiredQuickCompletes`,
        `MessageRetentionScheduler.runRetention`, `RadarCompositeDlqProcessor.process`, each sized from
        its own real batch-size/per-item-timeout arithmetic (show the arithmetic in the Dev Agent
        Record, per `skillars-deferred-116`'s precedent)
  - [ ] Add/extend tests asserting each new `@SchedulerLock`'s presence and configured values
  - [ ] Re-run `RadarCompositeDlqProcessorTest`, `QuickCompleteTimeoutServiceTest` suites green

- [ ] **Task 4 — AC4: ledger updates**
  - [ ] This story's findings are new (not pre-existing `deferred-work.md` bullets) — no ledger
        deletion needed; confirm no other bullet in the file names `BookingExpiryScheduler`,
        `UserAdminService`, `QuickCompleteTimeoutService`, `MessageRetentionScheduler`, or
        `RadarCompositeDlqProcessor` before closing this story (grep sweep)

- [ ] **Task 5 — Final validation**
  - [ ] Run every touched module's targeted test suites together; confirm zero regressions
  - [ ] Update Verification Checklist, File List, Change Log, Dev Agent Record
  - [ ] Mark story Status → review

---

## Dev Notes

- **No local `mvn verify`** per project convention — GitHub CI is the sole full-verification gate. Run
  targeted suites only: `BookingExpirySchedulerTest`, `BookingReminderSchedulerTest` (unchanged, but
  re-run to confirm no cross-contamination from the pattern being mirrored), `SchedulerLockTransactionOrderingIT`
  (real Testcontainers Postgres — this is an `@SpringBootTest` IT, not a unit test), the new
  `UserAdminServiceTest`, `RadarCompositeDlqProcessorTest`, `QuickCompleteTimeoutServiceTest`.
- **AC1 — re-read `BookingReminderScheduler.java` in full before writing any code, not just its
  javadoc.** The javadoc explains *why*; the method bodies (`processReminderWindows`, `idsOf`) are the
  literal shape to mirror. Do not invent a different transaction-scoping shape for
  `BookingExpiryScheduler` — the whole point of this AC is that this codebase already has one
  established, tested pattern for "batch-select then per-item TransactionTemplate scope with a
  swallowing catch," and `BookingExpiryScheduler` should look like its sibling when done.
- **AC1 — `SchedulerLockTransactionOrderingIT` is an integration test (`@SpringBootTest`, real
  Postgres via `AbstractIntegrationTest`), not a unit test.** Read it in full before editing — it
  asserts on live Spring AOP advisor chains, so getting the replacement assertion's shape right matters
  more than usual; copy `bandwidthResetService_isNotTransactional_soChunksCommitIndependently`'s
  structure (same helper, same style of javadoc explaining the specific regression it guards against)
  rather than reinventing.
- **AC1 — do not change `@SchedulerLock`'s `lockAtMostFor="PT15M"`/`lockAtLeastFor="PT2M"` values.**
  This AC is scoped to the transaction boundary only; the lock sizing was not found to be wrong and
  changing it is out of scope (unlike AC3, which is specifically about lock *presence*, not sizing of
  an existing lock).
- **AC2 — `deleteUserInformation` (the admin-manual-delete path) is explicitly out of scope.** Do not
  add the same guard there — it is a different call, triggered by an explicit admin action against a
  specific login, not a stale batch read, and adding an activation guard there would change intended
  admin behavior (an admin should be able to delete an activated user's data on request).
- **AC2 — the fix belongs inside `deleteUserInTransaction`, not `findExpiredUsers`.** Re-querying with
  the same `activated=false` filter a second time at the top of the batch loop would not close the race
  (the window is between `findExpiredUsers` and each individual `deleteUserInTransaction` call, not
  before the batch read) — the guard must be the last check immediately before `delete()`, inside the
  same `REQUIRES_NEW` transaction that does the delete, so it sees the truly-current row.
  `deleteUserInTransaction` already re-fetches via `findOneByLogin` for exactly this reason (to work on
  a live row rather than a detached one) — this AC simply makes that live re-fetch's result matter.
- **AC3 — do the worst-case arithmetic per scheduler, do not copy a constant across all three.**
  `skillars-deferred-116`'s Dev Notes walked through exactly this reasoning for its own two schedulers
  (batch size × realistic per-item worst case, plus margin) — read that story file's Dev Notes section
  before picking `lockAtMostFor` values here. `RadarCompositeDlqProcessor`'s `BATCH_SIZE = 50` constant
  (`RadarCompositeDlqProcessor.java:25`) and its per-item work
  (`compositeCalculationService.recalculateComposite`) are the inputs for that scheduler; do the
  equivalent read for the other two before choosing their values.
- **AC3 — `MessageRetentionScheduler` runs once daily (`cron = "0 0 2 * * *"`) — its `lockAtLeastFor`
  convention should reflect that cadence, not the `PT2M` used by every 5-minute-`fixedDelay` sibling.**
  A `PT2M` `lockAtLeastFor` on a daily job is not wrong, but re-derive it deliberately rather than
  copy-pasting; if the daily job's own worst-case runtime is short, a smaller `lockAtLeastFor` may be
  more appropriate — use judgment, document the choice in the Dev Agent Record.
- **Project structure:** four independent modules touched (`booking`, `security`, `messaging`,
  `development`) — no cross-AC coupling; the three ACs can be implemented and tested in any order.
- **Testing approach:** `BookingExpirySchedulerTest` (AC1, extend existing Mockito shape, mirror
  `BookingReminderSchedulerTest`'s `PlatformTransactionManager` mock pattern exactly);
  `SchedulerLockTransactionOrderingIT` (AC1, extend existing `@SpringBootTest` IT);
  `UserAdminServiceTest` (AC2, new file, no existing precedent — mirror this module's other service
  Mockito-unit-test shape, e.g. `AuthCleanupServiceTest` if one exists, otherwise
  `BookingExpirySchedulerTest`'s general shape); `RadarCompositeDlqProcessorTest` /
  `QuickCompleteTimeoutServiceTest` (AC3, extend existing files); a new minimal
  `MessageRetentionSchedulerTest` only if needed to pin the new annotation (AC3).

### Project Structure Notes

- AC1 touches `platform.booking.service` (`BookingExpiryScheduler`) and
  `platform.scheduler` (test-only, `SchedulerLockTransactionOrderingIT`) — no new module, no migration.
- AC2 touches `platform.security.service` (`UserAdminService`) — no new module, no migration.
- AC3 touches `platform.booking.service`, `platform.messaging.service`, `platform.development.service`
  — no new module, no migration, no new dependency (ShedLock is already a project dependency, used by
  every other `@SchedulerLock` site).

---

## Files to Read Before Implementation

- `src/main/java/com/softropic/skillars/platform/booking/service/BookingExpiryScheduler.java` (AC1 —
  whole file, 76 lines)
- `src/main/java/com/softropic/skillars/platform/booking/service/BookingReminderScheduler.java` (AC1 —
  whole file including its class javadoc; this is the shape to mirror)
- `src/main/java/com/softropic/skillars/platform/booking/service/BookingService.java:151-175` (AC1 —
  `transition`/`transitionInternal`, confirms the propagation and lock behavior the bug depends on)
- `src/main/java/com/softropic/skillars/platform/booking/contract/BookingStateTransitionException.java`
  (AC1 — confirms unchecked/default-rollback)
- `src/test/java/com/softropic/skillars/platform/booking/service/BookingExpirySchedulerTest.java` (AC1
  — existing test pattern to extend)
- `src/test/java/com/softropic/skillars/platform/booking/service/BookingReminderSchedulerTest.java`
  (AC1 — the `PlatformTransactionManager`/`TransactionTemplate` mocking pattern to copy)
- `src/test/java/com/softropic/skillars/platform/scheduler/SchedulerLockTransactionOrderingIT.java`
  (AC1 — whole file; this test's shape and javadoc must change with the fix)
- `src/main/java/com/softropic/skillars/platform/security/service/UserAdminService.java` (AC2 — whole
  file, 142 lines)
- `src/main/java/com/softropic/skillars/platform/security/repo/User.java:60-140` (AC2 — `activated`
  field, `isActivated()`, `setActivated()`, `activate()`)
- `src/main/java/com/softropic/skillars/platform/security/service/{Parent,Player,Coach}RegistrationService.java`,
  `UserRegistrationService.java` (AC2 — confirm each activation call site cited above)
- `src/main/java/com/softropic/skillars/platform/booking/service/QuickCompleteTimeoutService.java` (AC3
  — whole file)
- `src/main/java/com/softropic/skillars/platform/messaging/service/MessageRetentionScheduler.java` (AC3
  — whole file)
- `src/main/java/com/softropic/skillars/platform/development/service/RadarCompositeDlqProcessor.java`
  (AC3 — whole file)
- `src/main/java/com/softropic/skillars/platform/development/repo/RadarCompositeDlqRepository.java`
  (AC3 — confirms `findClaimedBatch`'s un-scoped query)
- `_bmad-output/implementation-artifacts/skillars-deferred-116-subscription-scheduler-transaction-isolation-hardening.md`
  (AC3 — Dev Notes section, the worst-case `@SchedulerLock` sizing arithmetic to reuse)

---

## Verification Checklist

- [ ] AC1: `expireStaleRequests` no longer carries a method-level `@Transactional`; each booking's
      `transition()` + event publish runs in its own `TransactionTemplate` scope; a mid-batch
      `BookingStateTransitionException` on one booking no longer rolls back an earlier booking's
      already-committed expiry; `SchedulerLockTransactionOrderingIT` updated to assert absence of
      `@Transactional` (matching its `BookingReminderScheduler`/`BandwidthResetService` siblings);
      `@SchedulerLock` values unchanged
- [ ] AC2: `deleteUserInTransaction` re-checks `!user.isActivated()` on the fresh re-fetch before
      deleting; a user who activates between the batch select and their own delete call is skipped,
      not deleted; `deleteUserInformation` (admin manual delete) unchanged
- [ ] AC3: `QuickCompleteTimeoutService`, `MessageRetentionScheduler`, `RadarCompositeDlqProcessor` all
      carry `@SchedulerLock` with values derived from each scheduler's own worst-case arithmetic (shown
      in the Dev Agent Record), not copy-pasted constants
- [ ] No regressions in any touched module's existing test suites

---

## References

- `_bmad-output/implementation-artifacts/deferred-work.md` — this story's findings are new (surfaced by
  this story's own creation-time audit), not mined from an existing ledger bullet; no bullet to cite
- `_bmad-output/implementation-artifacts/skillars-deferred-115-scheduler-transaction-isolation-hardening.md`,
  `skillars-deferred-116-subscription-scheduler-transaction-isolation-hardening.md`,
  `skillars-deferred-117-legacy-table-drop-and-scheduler-lock-hardening-sweep.md` — the three prior
  stories that established the audit lens this story continues, and the `TransactionTemplate`/
  `@SchedulerLock`-sizing patterns this story's fixes mirror

---

## Change Log

- 2026-09-17: Story created via `/bmad-create-story`. `deferred-work.md`'s ledger re-confirmed
  exhausted of genuine one-off bugs (per its own last five audits). Following the established pattern
  set by `skillars-deferred-115`/`-116`/`-117`, dispatched a fresh ad-hoc audit applying the
  transaction-boundary/TOCTOU/batch-memory lens to every `@Scheduled` class in `platform.booking`,
  `platform.messaging`, `platform.security`, and `platform.development` not yet covered by a prior
  audit (12 classes). Found and independently re-verified against HEAD: AC1, a genuine active bug in
  `BookingExpiryScheduler` where a method-level `@Transactional` around a per-booking loop lets one
  concurrently-raced booking's `BookingStateTransitionException` mark the whole physical transaction
  rollback-only, silently discarding every other booking's already-logged-successful auto-expiry in
  the same run via an uncaught `UnexpectedRollbackException` at commit — the identical bug this
  codebase's own `BookingReminderScheduler` and `BandwidthResetService` were previously fixed for,
  confirmed by reading both siblings' javadoc and the `SchedulerLockTransactionOrderingIT` test that
  currently pins `BookingExpiryScheduler` as the one scheduler keeping the old, buggy shape. AC2, a
  real cross-account race in `UserAdminService.removeNotActivatedUsers`/`deleteUserInTransaction`: no
  re-check of `activated` before deleting, so a user who verifies their email in the window between the
  scheduler's batch select and their own turn in the per-user delete loop has their now-legitimate
  account destroyed — confirmed by reading all four registration-flow activation call sites directly.
  AC3, three schedulers (`QuickCompleteTimeoutService`, `MessageRetentionScheduler`,
  `RadarCompositeDlqProcessor`) found with no `@SchedulerLock` where every sibling has one; scoped as a
  low-risk-today (single-instance deployment, matching `skillars-deferred-117` AC4's established
  reasoning for `RateLimitingService`) but cheap, real consistency fix — `RadarCompositeDlqProcessor`
  specifically has a latent correctness gap (`findClaimedBatch` not scoped to its own claim, no
  `@Version` on the entity) that adding the lock fully closes. No owner decision needed — all three ACs
  are direct fixes with an established sibling pattern already in this codebase. Branch:
  `story/deferred-118-booking-user-scheduler-fixes` (to be created), off `master` post-`skillars-deferred-117`-merge
  (PR #197) and post-`deferred-work.md` prune (PR #198).
