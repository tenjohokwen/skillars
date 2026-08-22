# Story Deferred-58: Coach-Availability Write-Lock Consistency & Pack-Deduction Failure-Record Transactional Safety

Status: ready-for-dev

## Story

As an engineer operating this platform,
I want `CoachProfileService.saveStep4`'s availability-window rewrite to serialize against the coach-row
lock that `RescheduleService.acceptReschedule` and `BookingDuplicationService.duplicateNextWeek` already
take (or newly take) when reading those same windows, and I want a pack-based booking's payment-failure
record to survive a real Spring rollback-only transaction instead of silently vanishing,
so that a coach editing their availability mid-accept/mid-duplicate cannot leave a booking validated
against stale window data, and so that a genuine `deductSession` persistence failure is not just caught
but actually recorded — the entire point of the catch branch that exists to guarantee it.

### Why this story exists

`_bmad-output/implementation-artifacts/deferred-work.md` (1783 lines at the time this story was created)
was re-mined end to end at commit `903c513` (the tip of `skillars-deferred-57`). Its own tail —
everything from `skillars-deferred-45` through `skillars-deferred-57` — was read in full and cross-checked
against live code, since that is the span every recent story-creation pass has re-mined most heavily and
where the freshest, least-picked-over candidates live. The large majority of open items in that span are
explicitly not mechanical fixes: they name a needed product/design decision (`DisputeService`'s `FROZEN`
filter, video bandwidth dedup rules, the rate-limit IP-keying strategy), need production DB access this
environment doesn't have (`V101`'s remediation path), need new infrastructure beyond a bounded fix (GDPR
erasure's unbounded S3-delete loop, the quota-release retry question), match an explicit standing
project convention against extraction (the 3-call-site validation-logic DRY nit), or require a frontend
test harness that doesn't exist anywhere in this repo (`playerStore.js` caching-logic coverage). Two
genuine, still-open, bounded, decision-light items survived, each re-verified live against the current
tree rather than trusted from the ledger's own text:

- **D1 (this story's AC1)** — sourced from `## Deferred from: story review of
  skillars-deferred-56-drill-upload-error-code-assertions-and-pack-deduction-exception-safety (2026-08-22)`
  (line 1715): `deferred-56` AC2 widened `PaymentLifecycleService.handlePackBasedBooking`'s catch clause
  from `PaymentGatewayException` to `RuntimeException`, but that story's own follow-up review found the
  widened catch may not survive its own motivating scenario. Re-verified live:
  `PackSessionService.deductSession` (`:51-61`) is plain `@Transactional` (`REQUIRED`), joining
  `onBookingAccepted`'s enclosing `@Transactional(propagation = REQUIRES_NEW)` `AFTER_COMMIT` listener
  transaction rather than starting its own. Under Spring's default rollback rule, an unchecked exception
  propagating out of a participating (non-`REQUIRES_NEW`) `@Transactional` method marks that shared
  transaction rollback-only at the AOP boundary — before the exception ever reaches
  `handlePackBasedBooking`'s catch block. `BookingPaymentPersistenceService.persistPaymentFailure`
  (`:206-207`) is also plain `@Transactional` (`REQUIRED`), so it joins that same now-rollback-only
  transaction: its writes execute against Postgres but are discarded when the outer `REQUIRES_NEW`
  transaction reaches its own commit point and rolls back instead. No test at any level (`CreditRoutingTest`
  uses a `@Mock`-ed `persistenceService`) can observe this, because a mock cannot fail a real Spring
  commit. This mechanism already applied identically to the pre-existing `PaymentGatewayException`
  branch and caused no observed harm there only because both of `deductSession`'s
  `PaymentGatewayException` throw sites fire before any DB write — `deferred-56` AC2 is the first change
  built around a scenario (`.save(purchase)` failing) where a write may already be in flight when the
  exception hits.
- **D2 (this story's AC2)** — two related items naming the same root cause, both left open by their own
  stories as "needs `CoachProfileService`'s locking strategy" / "out of this story's scope":
  `## Deferred from: code review of skillars-deferred-49-reschedule-and-duplicate-current-availability-window-enforcement (2026-08-21)`
  (line 1634) and `## Deferred from: code review of skillars-deferred-50-duplicate-next-week-overlap-guard-reschedule-ordinary-hours-coverage-and-availability-test-hardening (2026-08-21)`
  (line 1640). Re-verified live: `RescheduleService.acceptReschedule` (`:208-215`) already takes
  `coachProfileRepository.findByIdForUpdate(coach.getId())` + `entityManager.refresh(...,
  PESSIMISTIC_WRITE)` for its `SUSPENDED` re-check, then reads
  `coachAvailabilityWindowRepository.findByCoachId(coach.getId())` (`:229`) **unlocked**, one line later,
  in the very same already-locked transaction. `BookingDuplicationService.duplicateNextWeek` (`:42-88`)
  never locks the coach row at all — `coachProfileRepository.findByUserId(coachUserId)` (`:45`) is a plain
  read, and both its availability-window check (`:67`) and its booking-overlap check (`:80`) run unlocked.
  The actual root cause both items point at: `CoachProfileService.saveStep4` (`:225-242`), the sole writer
  of `coach_availability_windows`, never takes the coach-profile row lock before its
  `deleteByCoachId`+`saveAll` rewrite — so even where a reader (`acceptReschedule`) already holds that
  lock, a concurrent `saveStep4` isn't serialized against it, because `saveStep4` never contends for the
  same lock. Locking `saveStep4` closes the gap on the writer side without needing any new lock on the two
  existing reader call sites named above, plus one new lock in `duplicateNextWeek` (which, unlike
  `acceptReschedule`, doesn't lock the coach row for any purpose today).

**Examined and deliberately not picked up**, beyond the already-thin recent span's own excluded set:
`DisputeService`'s `CAPTURED`-only filter silently zeroing `FROZEN` payments (line 1603) — dormant,
`BookingPaymentStatus.FROZEN` is never written anywhere in `src/main/java` today, and a real fix needs a
coordinated design decision spanning `DisputeService` and `RevenueReportingService` neither this story nor
any single bundled small-fix story should make ad hoc; `playerStore.js`'s missing test coverage (line
1611) — this repo has no frontend test harness anywhere, a standing gap `skillars-deferred-35`/`36`/`37`/`38`
already left in place identically; the 3-call-site validation-logic DRY duplication (line 1630) — this
project's own established convention (per `skillars-deferred-48`'s code review, which dismissed an
identical nit) is not to extract blocks this small; the video-bandwidth dedup-rule question (line 1587) —
explicitly needs "a full design review" per its own text, not a mechanical fix.

## Acceptance Criteria

1. **AC1 — `persistPaymentFailure` survives a rollback-only transaction; prove it with a real transactional
   test, not a mock.**
   - File: `src/main/java/com/softropic/skillars/platform/payment/service/BookingPaymentPersistenceService.java:206-207`.
   - Current shape:
     ```java
     @Transactional
     public void persistPaymentFailure(UUID bookingId, BigDecimal creditToReverse,
     ```
   - Change to `REQUIRES_NEW`, mirroring this same class's own existing pattern at `:72` and `:279`
     (both already `@Transactional(propagation = Propagation.REQUIRES_NEW)`):
     ```java
     @Transactional(propagation = Propagation.REQUIRES_NEW)
     public void persistPaymentFailure(UUID bookingId, BigDecimal creditToReverse,
     ```
     `Propagation` is already imported in this file (used by the two existing `REQUIRES_NEW` methods) — no
     new import needed.
   - **Why `REQUIRES_NEW` and not, e.g., catching the exception earlier**: a participating transaction that
     has been marked rollback-only cannot be un-marked short of a new physical transaction. `REQUIRES_NEW`
     suspends the caller's (possibly rollback-only) transaction and opens a fresh one that commits
     independently — the established fix shape this exact codebase already uses for the identical class of
     problem (`skillars-deferred-12`: "settling each booking in its own `REQUIRES_NEW` `TransactionTemplate`").
     This also fixes the pre-existing `PaymentGatewayException` branch's identical latent gap (same call,
     same method), not just AC2's new `RuntimeException` branch — both catch branches in
     `PaymentLifecycleService.handlePackBasedBooking` (`:162-177`) call this same method.
   - **Test coverage — must be a real transaction, not a mock.** `CreditRoutingTest`'s existing AC2 test
     (`packBasedBooking_deductSessionFailsWithNonPaymentGatewayException_callsPersistFailureWithZeroReversal`)
     uses a `@Mock`-ed `persistenceService` and cannot observe commit/rollback behavior — do not extend it
     for this AC. Add a new IT-level test that forces a genuine `DataAccessException` inside the real
     `AFTER_COMMIT`/`REQUIRES_NEW` flow and asserts the failure record actually persists. The most direct
     way to force a real, non-mocked persistence failure from `deductSession`'s `.save(purchase)`
     (`PackSessionService.java:59`) without touching production code: seed a `SessionPackPurchase` row,
     then have the test's transaction hold a conflicting lock or use a fault-injection point already
     established elsewhere in this codebase's concurrency ITs (see `BookingServiceConcurrencyIT`'s pattern
     of using a genuine second thread + `CountDownLatch`/`pg_locks` polling rather than a mock). If no
     existing IT class already exercises `onBookingAccepted`'s pack-based path end-to-end
     (`PaymentLifecycleServiceIT`? — check first), add the test to whichever IT class already covers
     `handlePackBasedBooking`'s happy/failure path, or create a small new one colocated with the payment
     service ITs. The test must prove: (a) a `BookingPayment` failure row exists after the call returns,
     with the correct `bookingId`, despite (b) the pack-deduction transaction itself having rolled back.
     Run the new/extended IT class and confirm green, plus `mvn -o test -Dtest=CreditRoutingTest` to
     confirm no regression to the existing mocked unit tests.

2. **AC2 — Lock the coach-profile row in `CoachProfileService.saveStep4` before rewriting availability
   windows, and take the same lock in `BookingDuplicationService.duplicateNextWeek` before its
   availability/overlap checks.**
   - File: `src/main/java/com/softropic/skillars/platform/marketplace/service/CoachProfileService.java:225-242`.
   - Current shape:
     ```java
     @Transactional
     public ProfileBuilderStepResponse saveStep4(Long userId, ProfileBuilderStep4Request req) {
         CoachProfile profile = requireProfile(userId);
         if (coachPricingRepository.findByCoachId(profile.getId()).isEmpty()) {
             throw new MarketplaceException("marketplace.stepOutOfOrder", "Complete Step 3 before submitting Step 4");
         }
         validateAvailabilityWindows(req.windows());

         coachAvailabilityWindowRepository.deleteByCoachId(profile.getId());
         List<CoachAvailabilityWindow> windows = req.windows().stream().map(w -> { /* ... */ }).toList();
         coachAvailabilityWindowRepository.saveAll(windows);

         return new ProfileBuilderStepResponse(profile.getId(), 4, 5);
     }
     ```
   - Add a locked re-read of the coach profile before the delete/insert, mirroring
     `RescheduleService.acceptReschedule`'s existing lock pattern (`RescheduleService.java:208-215`) exactly
     — `findByIdForUpdate` is JPQL and `profile` is already a managed instance from `requireProfile`'s
     `findByUserId` call, so without the explicit `entityManager.refresh(...)` the lock is taken at the DB
     but Hibernate hands back the stale cached instance:
     ```java
     @Transactional
     public ProfileBuilderStepResponse saveStep4(Long userId, ProfileBuilderStep4Request req) {
         CoachProfile profile = requireProfile(userId);
         if (coachPricingRepository.findByCoachId(profile.getId()).isEmpty()) {
             throw new MarketplaceException("marketplace.stepOutOfOrder", "Complete Step 3 before submitting Step 4");
         }
         validateAvailabilityWindows(req.windows());

         // Deferred-58 AC2: serializes this rewrite against RescheduleService.acceptReschedule's and
         // BookingDuplicationService.duplicateNextWeek's own coach-row lock, so a concurrent
         // accept/duplicate-next-week validated against these windows can no longer race a rewrite of
         // them mid-transaction.
         coachProfileRepository.findByIdForUpdate(profile.getId())
             .orElseThrow(() -> new MarketplaceException("marketplace.profileNotFound",
                 "Coach profile not found for userId=" + userId));
         entityManager.refresh(profile, LockModeType.PESSIMISTIC_WRITE);

         coachAvailabilityWindowRepository.deleteByCoachId(profile.getId());
         List<CoachAvailabilityWindow> windows = req.windows().stream().map(w -> { /* ... */ }).toList();
         coachAvailabilityWindowRepository.saveAll(windows);

         return new ProfileBuilderStepResponse(profile.getId(), 4, 5);
     }
     ```
     `CoachProfileService` does not currently inject `EntityManager` — add
     `import jakarta.persistence.EntityManager;`, `import jakarta.persistence.LockModeType;`, and a new
     `private final EntityManager entityManager;` field (this class uses `@RequiredArgsConstructor`, so no
     constructor edit needed beyond the field declaration).
   - File: `src/main/java/com/softropic/skillars/platform/booking/service/BookingDuplicationService.java:42-88`.
   - Current shape reads the coach profile unlocked (`:45`) and never locks it before either check (`:67`
     availability, `:80` overlap). Add the identical lock immediately after the existing ownership/status
     checks (after the `"COMPLETED"` status check at `:51`, before `newStart`/`newEnd` are computed),
     mirroring `acceptReschedule`'s exact call shape:
     ```java
     // Deferred-58 AC2: acceptReschedule already takes this same lock before its own availability
     // re-check; duplicateNextWeek never did, so a concurrent saveStep4 rewrite of this coach's windows
     // was never serialized against this method's read of them (or against its overlap check, which
     // otherwise only has V87's exclusion constraint as a commit-time backstop).
     coachProfileRepository.findByIdForUpdate(coach.getId())
         .orElseThrow(() -> new ResourceNotFoundException("Coach profile not found", "coach_profile"));
     entityManager.refresh(coach, LockModeType.PESSIMISTIC_WRITE);
     ```
     `BookingDuplicationService` does not currently inject `EntityManager` — add the same two imports
     (`jakarta.persistence.EntityManager`, `jakarta.persistence.LockModeType`) and a new
     `private final EntityManager entityManager;` field (this class also uses `@RequiredArgsConstructor`).
   - **Why lock the writer (`saveStep4`) rather than adding a lock only on more readers**: two readers
     (`acceptReschedule`, and now `duplicateNextWeek`) already/newly take the coach-row lock, but neither
     reader's lock does anything unless the writer contends for the same lock. Locking `saveStep4` is the
     one change that makes every existing and new locked reader's guarantee real.
   - **Test coverage — existing unit tests must be updated, plus one new concurrency proof.**
     `BookingDuplicationServiceTest` currently stubs `coachProfileRepository.findByUserId(...)` for every
     test but never `findByIdForUpdate(...)` — once this AC lands, every existing test that reaches past
     the `"COMPLETED"` status check will hit `orElseThrow` on an unstubbed `Optional.empty()` and fail. Add
     `when(coachProfileRepository.findByIdForUpdate(coach.getId())).thenReturn(Optional.of(coach))` to
     every existing test fixture setup that reaches that point (mirror `RescheduleServiceTest`'s identical
     stubbing pattern at lines 288/324/365, and note `entityManager.refresh(...)` needs no stubbing — it is
     `void`, and `RescheduleServiceTest`'s own `@Mock EntityManager entityManager` field, injected into the
     constructor the same way, already relies on Mockito's default no-op for void methods). `CoachProfileService`
     has no existing unit test file (`saveStep4` is currently covered only by `CoachProfileBuilderIT` at the
     API level) — no existing unit test to update there, but run
     `mvn -o integration-test -Dit.test=CoachProfileBuilderIT` to confirm the new lock introduces no
     regression to the single-request case. Then add one new concurrency test to
     `src/test/java/com/softropic/skillars/platform/booking/service/BookingServiceConcurrencyIT.java`,
     reusing its existing `awaitAnotherSessionBlockedOnCoachProfileLock(Duration)` helper (`:407`) exactly
     as its two existing usages do (`:263`, `:346`) — start a `saveStep4` call in one thread, hold its
     transaction open past the lock acquisition (same latch-based staging those two existing tests use),
     then from the main thread confirm a concurrent `duplicateNextWeek` (or `acceptReschedule`) call blocks
     on the coach-profile lock rather than proceeding with stale window data. Run
     `mvn -o test -Dtest=BookingDuplicationServiceTest` and
     `mvn -o integration-test -Dit.test=BookingServiceConcurrencyIT,CoachProfileBuilderIT` and confirm all
     green.

3. **AC3 — Ledger hygiene.** This project's established convention (confirmed against the "Create Story"
   commits for `deferred-49` through `-57`) is: at **story-creation** time, tag an item this story is about
   to fix as `` `[PICKED UP by skillars-deferred-58 ACn]` ``, appended after the item's existing
   text/citation. `` `[CLOSED by ...]` `` is reserved for items **verified already fixed by separate,
   completed work**. Only flip a `PICKED UP` tag to `CLOSED` in the **implementation** commit, once the
   corresponding code change actually lands — never at story-creation time. This was already applied
   correctly at this story's creation:
   - `deferred-work.md` line 1735 (the `persistPaymentFailure` rollback-only item, under
     `## Deferred from: story review of skillars-deferred-56-...`) tagged
     `` `[PICKED UP by skillars-deferred-58 AC1]` ``.
   - `deferred-work.md` line 1634 (`acceptReschedule`'s unlocked availability read, under
     `## Deferred from: code review of skillars-deferred-49-...`) tagged
     `` `[PICKED UP by skillars-deferred-58 AC2]` ``.
   - `deferred-work.md` line 1640 (`duplicateNextWeek`'s TOCTOU race, under
     `## Deferred from: code review of skillars-deferred-50-...`) tagged
     `` `[PICKED UP by skillars-deferred-58 AC2]` ``.
   This AC's job during **implementation** is to flip both tags to `CLOSED` once AC1/AC2 actually land —
   one commit, matching the code:
   - Once AC1 ships: flip line 1735's tag to `` `[CLOSED by skillars-deferred-58 AC1]` `` with a one-line
     closure note.
   - Once AC2 ships: flip both line 1634's and line 1640's tags to
     `` `[CLOSED by skillars-deferred-58 AC2]` `` the same way.
   - **If a partial implementation lands**, flip only the tags for the ACs that actually shipped — leave
     the others at `PICKED UP`. The ledger must never claim a still-unfixed item is `CLOSED`.

## Tasks / Subtasks

- [ ] Task 1: Pack-deduction failure-record transactional safety (AC: #1)
  - [ ] 1.1 Change `BookingPaymentPersistenceService.persistPaymentFailure` from `@Transactional` to
    `@Transactional(propagation = Propagation.REQUIRES_NEW)`.
  - [ ] 1.2 Add a real (non-mocked) transactional test proving a `deductSession` persistence failure still
    leaves a failure record after the outer transaction rolls back — per AC1's Test coverage guidance,
    check for an existing IT class covering `handlePackBasedBooking`'s full flow before creating a new one.
  - [ ] 1.3 Run the new/extended IT class and `mvn -o test -Dtest=CreditRoutingTest`; confirm all green.
- [ ] Task 2: Coach-profile write-lock consistency (AC: #2)
  - [ ] 2.1 Add the `EntityManager` field + locked re-read to `CoachProfileService.saveStep4`, per AC2's
    snippet.
  - [ ] 2.2 Add the `EntityManager` field + locked re-read to `BookingDuplicationService.duplicateNextWeek`,
    per AC2's snippet.
  - [ ] 2.3 Update every existing `BookingDuplicationServiceTest` fixture that reaches past the
    `"COMPLETED"` status check to stub `coachProfileRepository.findByIdForUpdate(coach.getId())`.
  - [ ] 2.4 Add one new concurrency test to `BookingServiceConcurrencyIT`, reusing
    `awaitAnotherSessionBlockedOnCoachProfileLock`, proving `saveStep4` and a concurrent
    `duplicateNextWeek`/`acceptReschedule` call now serialize on the coach-profile lock.
  - [ ] 2.5 Run `mvn -o test -Dtest=BookingDuplicationServiceTest` and
    `mvn -o integration-test -Dit.test=BookingServiceConcurrencyIT,CoachProfileBuilderIT`; confirm all
    green.
- [ ] Task 3: Ledger hygiene (AC: #3) — flip the `PICKED UP` tags applied at story creation (lines 1735,
  1634, 1640) to `CLOSED` once AC1/AC2 land, per AC3.

## Dev Notes

- **This story bundles two independent, decision-light findings from two different modules — it is not a
  single coherent feature.** AC1 touches `BookingPaymentPersistenceService` (payment module) plus a new/
  extended IT. AC2 touches `CoachProfileService` (marketplace module) and `BookingDuplicationService`
  (booking module) plus their tests. The two ACs' code changes do not overlap or depend on each other;
  implement and review in any order.
- **AC1's fix is one propagation-annotation change**, but its *proof* is the hard part — the existing
  `CreditRoutingTest` coverage for this exact catch branch is mock-based and structurally cannot detect
  this class of bug (a mocked `persistenceService` never rolls back). Do not consider AC1 done with only a
  mocked-unit-test update; a real transactional/IT-level test is the acceptance bar, matching this
  project's own repeatedly-stated lesson (`skillars-deferred-13`, `-15`, and `skillars-uat-3` D11 all
  record "a lock/guard whose test passed without it").
- **AC2's root-cause framing matters for review**: the fix is locking the *writer*
  (`CoachProfileService.saveStep4`), not adding yet another lock on a reader. `acceptReschedule` already
  holds this lock and gets nothing from it today because nothing else ever contends for it — locking
  `saveStep4` is what makes that existing lock (and the new one in `duplicateNextWeek`) actually
  serialize against something.
- **`entityManager.refresh(..., PESSIMISTIC_WRITE)` is not optional/defensive in either new call site** —
  `findByIdForUpdate` is JPQL, and both `profile` (in `saveStep4`, from `requireProfile`'s `findByUserId`)
  and `coach` (in `duplicateNextWeek`, from its own `findByUserId`) are already Hibernate-managed instances
  before the locked re-read. Without the explicit `refresh`, the DB lock is taken but Hibernate returns the
  same cached instance with stale in-memory field values — the exact trap
  `BookingService.createBookingRequest`'s own comments document and `RescheduleService.acceptReschedule`
  already avoids. Omitting it would make the lock a no-op for any field that matters (it doesn't currently
  matter for `CoachProfile.status`/other fields read after the lock in these two methods specifically,
  since neither reads mutable coach fields post-lock today — but the pattern must still be followed
  exactly, since a future edit to either method that adds a post-lock field read would silently inherit the
  stale-read trap otherwise).
- **AC2 does not add any new business rule** (no new `SUSPENDED` check, no new validation) — it only adds
  serialization. The availability-window and overlap checks in both methods keep their existing logic and
  error codes; only the locking around them changes.
- **No frontend changes in this story.** Both ACs are backend-only.
- Per `docs/validation-strategy.md`, run targeted verification only — do not run a full `mvn verify` unless
  targeted verification proves insufficient.

### Project Structure Notes

- `src/main/java/com/softropic/skillars/platform/payment/service/BookingPaymentPersistenceService.java` —
  `persistPaymentFailure`'s `@Transactional` gains `propagation = Propagation.REQUIRES_NEW` (AC1). No new
  imports (`Propagation` already imported for this class's two existing `REQUIRES_NEW` methods).
- A new or extended IT-level test class proving AC1's transactional-survivability fix (exact class TBD by
  the dev agent — check for an existing IT covering `PaymentLifecycleService.onBookingAccepted`'s
  pack-based path first) (AC1).
- `src/main/java/com/softropic/skillars/platform/marketplace/service/CoachProfileService.java` — new
  `EntityManager` field, two new imports, `saveStep4` gains a locked re-read before its
  delete/insert (AC2).
- `src/main/java/com/softropic/skillars/platform/booking/service/BookingDuplicationService.java` — new
  `EntityManager` field, two new imports, `duplicateNextWeek` gains a locked re-read before its
  availability/overlap checks (AC2).
- `src/test/java/com/softropic/skillars/platform/booking/service/BookingDuplicationServiceTest.java` —
  every existing test fixture that reaches past the `"COMPLETED"` check gains a
  `findByIdForUpdate` stub (AC2).
- `src/test/java/com/softropic/skillars/platform/booking/service/BookingServiceConcurrencyIT.java` — one
  new concurrency test reusing the existing `awaitAnotherSessionBlockedOnCoachProfileLock` helper (AC2).
- `_bmad-output/implementation-artifacts/deferred-work.md` — two `PICKED UP`→`CLOSED` tag flips (AC3).
- No new migrations, no new dependencies, no changes to any `*Resource`/`*Controller` class signature.

### References

- [Source: `_bmad-output/implementation-artifacts/deferred-work.md` line 1715-1735, section `## Deferred
  from: story review of skillars-deferred-56-drill-upload-error-code-assertions-and-pack-deduction-exception-safety
  (2026-08-22)` — this story's AC1 source]
- [Source: `_bmad-output/implementation-artifacts/deferred-work.md` line 1628-1637, section `## Deferred
  from: code review of skillars-deferred-49-reschedule-and-duplicate-current-availability-window-enforcement
  (2026-08-21)` — this story's AC2 source (RescheduleService half)]
- [Source: `_bmad-output/implementation-artifacts/deferred-work.md` line 1638-1641, section `## Deferred
  from: code review of skillars-deferred-50-duplicate-next-week-overlap-guard-reschedule-ordinary-hours-coverage-and-availability-test-hardening
  (2026-08-21)` — this story's AC2 source (BookingDuplicationService half)]
- [Source: `src/main/java/com/softropic/skillars/platform/payment/service/PaymentLifecycleService.java:162-177`
  — `handlePackBasedBooking`, the caller whose catch branches both depend on AC1's fix]
- [Source: `src/main/java/com/softropic/skillars/platform/payment/service/PackSessionService.java:51-61` —
  `deductSession`, the plain-`@Transactional` method whose failure marks the shared transaction
  rollback-only]
- [Source: `src/main/java/com/softropic/skillars/platform/payment/service/BookingPaymentPersistenceService.java:72,206-207,279`
  — `persistPaymentFailure`, AC1's target, and its two sibling `REQUIRES_NEW` methods this AC's fix mirrors]
- [Source: `src/main/java/com/softropic/skillars/platform/booking/service/RescheduleService.java:208-215,229`
  — `acceptReschedule`'s existing `findByIdForUpdate`+`refresh` lock pattern, mirrored exactly by AC2 in
  both target files, and the unlocked `windows` read immediately after it that AC2 fixes]
- [Source: `src/main/java/com/softropic/skillars/platform/marketplace/service/CoachProfileService.java:225-242,372-376`
  — `saveStep4`, AC2's primary target, and `requireProfile`, the unlocked read it currently relies on]
- [Source: `src/main/java/com/softropic/skillars/platform/booking/service/BookingDuplicationService.java:42-88`
  — `duplicateNextWeek`, AC2's second target]
- [Source: `src/main/java/com/softropic/skillars/platform/marketplace/repo/CoachProfileRepository.java:28-34`
  — `findByIdForUpdate`, the shared locking query both AC2 call sites reuse]
- [Source: `src/test/java/com/softropic/skillars/platform/booking/service/RescheduleServiceTest.java:48,66,288,324,365`
  — the existing `@Mock EntityManager` + `findByIdForUpdate` stubbing pattern AC2's test updates mirror]
- [Source: `src/test/java/com/softropic/skillars/platform/booking/service/BookingServiceConcurrencyIT.java:263,346,407`
  — the existing `awaitAnotherSessionBlockedOnCoachProfileLock` helper AC2's new concurrency test reuses]
- [Source: `src/test/java/com/softropic/skillars/platform/booking/service/BookingDuplicationServiceTest.java`
  — existing unit tests AC2 must update with the new lock stub]
- [Source: `docs/validation-strategy.md` — targeted-test-only validation policy]

## Dev Agent Record

### Agent Model Used

_Not yet started._

### Debug Log References

_Not yet started._

### Completion Notes List

_Not yet started._

### File List

_Not yet started._

## Change Log

| Date | Change |
|---|---|
| 2026-08-23 | Story created via story-creation process, bundling two items re-mined from `deferred-work.md`'s most recently active tail (`skillars-deferred-45` through `-57`). AC1 closes the `story review of skillars-deferred-56` item — `persistPaymentFailure` joins its caller's transaction, so a real `deductSession` persistence failure would silently lose its own failure record to a rollback-only commit, the exact scenario the catch branch exists to guard against. AC2 closes two related items from `deferred-49`'s and `deferred-50`'s code reviews — `CoachProfileService.saveStep4` never locks the coach-profile row before rewriting availability windows, so `RescheduleService.acceptReschedule`'s existing lock (and a new one added to `BookingDuplicationService.duplicateNextWeek`) had/have nothing to serialize against. AC3 is ledger hygiene for both. Considered and explicitly not picked up: `DisputeService`'s dormant `FROZEN`-filter gap (needs a coordinated design decision spanning two services); `playerStore.js`'s missing test coverage (no frontend test harness exists in this repo, a standing gap left in place by four prior stories); the 3-call-site validation-logic DRY duplication (matches this project's own established anti-abstraction convention); the video-bandwidth dedup-rule question (explicitly needs a full design review per its own text). |
