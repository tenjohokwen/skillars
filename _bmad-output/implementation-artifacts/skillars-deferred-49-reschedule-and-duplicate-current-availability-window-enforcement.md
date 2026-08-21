# Story Deferred-49: Reschedule & Duplicate-Next-Week Current-Availability-Window Enforcement

Status: ready-for-dev

## Story

As an engineer operating this platform,
I want `RescheduleService.requestReschedule` and `BookingDuplicationService.duplicateNextWeek` to validate
their proposed time window against the coach's current `coach_availability_windows`,
so that a parent can no longer reschedule — or a coach repeat-next-week — a session into a time the coach
does not currently work, the same way an initial booking request is already blocked from doing so.

### Why this story exists

`_bmad-output/implementation-artifacts/deferred-work.md` (D1, filed while scoping an earlier story's AC3,
never picked up) reads:

> **D1 — `RescheduleService` performs no availability-window check at all.** ... a parent can move a session
> to 03:00 on a day the coach does not work. ... The same question governs `BookingDuplicationService`, which
> is exempt from AC3 for the identical reason. Decide the semantics first (does a reschedule have to fit
> *current* availability, or only *availability as it stood when the booking was made*?), then fix both
> together.

Re-verified today by direct read: both gaps remain real and unfixed. `RescheduleService.requestReschedule`
(`RescheduleService.java:67-135`) validates duration, future-start, and end-after-start, but never calls
`coachAvailabilityWindowRepository` at all. `BookingDuplicationService.duplicateNextWeek`
(`BookingDuplicationService.java:36-83`) computes `newStart`/`newEnd` by adding 7 days to the original
booking's times and only checks that the result is in the future — no availability check whatsoever.

**Design decision (made for this cycle, not left to the dev agent):** the semantics question above was
resolved explicitly by the user (2026-08-21) — **a reschedule/duplicate must fit the coach's CURRENT
availability**, re-validated at request time, not the availability as it stood when the original booking was
made. If a coach has since narrowed their hours, a reschedule or repeat-next-week into the now-unavailable
slot is rejected, even though the original booking was legitimate when made. This matches how every other
live booking-time mutation in this codebase already behaves (`BookingService.createBookingRequest`,
`BookingBatchService`) — none of them grandfather a stale availability snapshot.

**Implementation approach:** both fixes reuse the already-existing, already-battle-tested
`BookingService.isSlotWithinAvailabilityWindow(startTime, endTime, windows)` (package-private, already
shared by `BookingBatchService` — see its own comment "a copy would drift from this one's cross-midnight
anchoring and invalid-timezone handling"). No new validation logic, no migration, no new error code —
`BookingError.SLOT_OUTSIDE_AVAILABILITY` (`errorKey: "booking.slotOutsideAvailability"`) already exists and
is already used by `BookingService`/`BookingBatchService` for the identical rejection on the initial-booking
path. This story adds two more callers of the same method and the same error, nothing new is invented.

## Acceptance Criteria

1. **AC1 — `RescheduleService.requestReschedule` rejects a proposed window outside the coach's current
   availability, reusing `BookingService.isSlotWithinAvailabilityWindow`.**
   - In `src/main/java/com/softropic/skillars/platform/booking/service/RescheduleService.java`: add a new
     field `private final CoachAvailabilityWindowRepository coachAvailabilityWindowRepository;` at the end of
     the existing field list (Lombok `@RequiredArgsConstructor` generates the constructor in field-declaration
     order — appending avoids reordering the existing test's positional constructor call beyond adding one
     more argument at the end). Add the matching import
     (`com.softropic.skillars.platform.marketplace.repo.CoachAvailabilityWindowRepository`, and
     `com.softropic.skillars.platform.marketplace.repo.CoachAvailabilityWindow` for the `List<...>` type).
   - In `requestReschedule`, immediately **after** the existing duration-match check (`:97-107`) and
     **before** the pending-reschedule-already-exists check (`:108-113`) — mirrors
     `BookingService.createBookingRequest`'s own ordering (duration validated before availability is looked
     up):
     ```java
     List<CoachAvailabilityWindow> windows = coachAvailabilityWindowRepository.findByCoachId(booking.getCoachId());
     if (!bookingService.isSlotWithinAvailabilityWindow(req.proposedStartTime(), req.proposedEndTime(), windows)) {
         throw new OperationNotAllowedException(
             "Proposed slot is not within coach availability",
             Map.of("proposed start time", req.proposedStartTime(), "proposed end time", req.proposedEndTime()),
             BookingError.SLOT_OUTSIDE_AVAILABILITY);
     }
     ```
     `bookingService` is already injected on this class; `isSlotWithinAvailabilityWindow` is package-private
     and `RescheduleService` is in the same `com.softropic.skillars.platform.booking.service` package, so no
     visibility change is needed anywhere.
   - **Unit tests** in `RescheduleServiceTest`: add `@Mock private CoachAvailabilityWindowRepository
     coachAvailabilityWindowRepository;`, wire it into the `new RescheduleService(...)` constructor call
     (append as the last argument), and add two tests mirroring this file's existing
     `Mockito.when(...).thenReturn(...)` / `assertThatThrownBy` style: one asserting
     `requestReschedule` throws `OperationNotAllowedException` with `BookingError.SLOT_OUTSIDE_AVAILABILITY`
     when `bookingService.isSlotWithinAvailabilityWindow(...)` is stubbed to return `false`, one asserting the
     happy path still succeeds (reaches `rescheduleRepo.save(...)`) when it returns `true`.
   - **Required existing-test fix, or two currently-green unit tests break — story-review.md Finding 2:**
     `requestReschedule_parentOwnsBooking_confirmedStatus_createsRequest` (`:87-103`) and
     `requestReschedule_legacyThreeHourBooking_movesAtItsOwnLength` (`:113-140`) both reach past the duration
     check into `rescheduleRepo.save(...)` today without stubbing `isSlotWithinAvailabilityWindow`. Since
     `bookingService` is a Mockito `@Mock`, an unstubbed call returns the default `boolean` value — `false` —
     so once AC1's check is inserted, both tests will hit `SLOT_OUTSIDE_AVAILABILITY` instead of reaching
     `save(...)` and fail. Add `when(bookingService.isSlotWithinAvailabilityWindow(any(), any(),
     any())).thenReturn(true);` to each of these two tests' own stubbing block (per-test, not a blanket
     `@BeforeEach` stub — this file's `MockitoExtension` uses strict stubbing, and several other existing
     tests reject before ever reaching this check, so a blanket stub would fail as an unnecessary stubbing).
     Do not skip this — it is not covered by "add two new tests," it is a fix to two pre-existing ones.
   - **Integration test** in `RescheduleResourceIT`: add one new test,
     `requestReschedule_slotOutsideAvailabilityWindow_returns403WithSlotOutsideAvailabilityKey`, mirroring
     `requestReschedule_proposedStartTimeInPast_returns403WithStartTimeInPastKey`'s shape
     (`assertThatThrownBy(...).isInstanceOf(HttpClientErrorException.class)`, asserting both the `403` status
     and the response body's `errorMsg.errorKey` equals `"booking.slotOutsideAvailability"`).
     **story-review.md Finding 3 — do NOT use a "far enough in the future" proposal to trigger this
     rejection; it cannot work under this story's own fixture.** That convention (mirrored from
     `BookingRequestResourceIT.createBookingRequest_slotOutsideAvailabilityWindow_returns422`) only works
     there because that file's fixture is a single *narrow* window (next-day, `08:00`–`18:00`) — a far-future
     date lands on an arbitrary day with no window at all. This story's own mandated fixture (below) is the
     opposite: **every** day of week, **all** of `00:00:00`–`23:59:59`. Under that fixture there is no
     "N days out, 1 hour long, arbitrary hour" proposal that lands outside it — every calendar day has full
     coverage, so that approach would produce a test that returns 204 (passes CI) while asserting nothing.
     The only way to fail the check under this fixture is a session whose end crosses past `23:59:59` on the
     **start's own calendar date** (`isSlotWithinAvailabilityWindow` anchors `windowEnd` to `startZdt`'s
     `LocalDate` only — `BookingService.java:844`). Construct the proposal as a late-night start that runs
     past midnight, e.g.:
     ```java
     ZonedDateTime lateNightStart = ZonedDateTime.now(ZoneId.of("Europe/Berlin")).plusDays(5)
         .withHour(23).withMinute(30).withSecond(0).withNano(0);
     Instant proposedStart = lateNightStart.toInstant();
     Instant proposedEnd = proposedStart.plus(1, ChronoUnit.HOURS); // 00:30 next day — past windowEnd, which is anchored to the START date
     ```
     Add `java.time.ZoneId` and `java.time.ZonedDateTime` imports to `RescheduleResourceIT.java` (not
     currently imported there; already used the same way in `BookingRequestResourceIT`'s own `setUp()`).
   - **Required test-fixture change, or every existing `RescheduleResourceIT` test breaks:** this file's
     `setUp()` (`:72-160`) currently seeds **no** `marketplace.coach_availability_windows` row for
     `coachProfileId` at all, and its ~25 existing tests compute proposed times as bare
     `Instant.now().plus(N, ChronoUnit.DAYS)` with no hour-of-day anchoring (unlike
     `BookingRequestResourceIT`'s fixture, which anchors to a specific `.withHour(10)` inside a narrow
     `08:00`–`18:00` window). Adding AC1's check with **no** fixture change would make every happy-path
     reschedule test in this file fail nondeterministically (whether it passes depends on what wall-clock
     hour/day-of-week the test happens to run at). Fix: in `setUp()`, after the existing coach-profile
     inserts, seed **one wide-open window per day of week** for `coachProfileId` (all 7 days, `day_of_week` 1
     through 7, `start_time = '00:00:00'`, `end_time = '23:59:59'`, `canonical_timezone = 'Europe/Berlin'`
     matching this coach's existing `canonical_timezone`) — a loop over `1..7` inserting into
     `marketplace.coach_availability_windows` with the same columns/shape every other IT's window insert
     already uses (`id, coach_id, day_of_week, start_time, end_time, canonical_timezone`). This keeps every
     existing test's arbitrary wall-clock-relative proposed time inside *some* window regardless of when CI
     runs, and is the only correct fix — narrowing to a single day/hour range would make the existing tests'
     day-agnostic `Instant.now().plus(N, DAYS)` pattern flaky by construction. Do not seed a window for
     `coachProfile2Id` — no test in this file proposes a reschedule against it.

2. **AC2 — `BookingDuplicationService.duplicateNextWeek` rejects a computed next-week window outside the
   coach's current availability, reusing the identical check.**
   - In `src/main/java/com/softropic/skillars/platform/booking/service/BookingDuplicationService.java`: add
     the same `private final CoachAvailabilityWindowRepository coachAvailabilityWindowRepository;` field
     (appended, same reasoning as AC1) and the same two imports.
   - In `duplicateNextWeek`, immediately **after** the existing past-time check (`:53-56`) and **before**
     `packSessionService.findActivePackId(...)` (`:57`):
     ```java
     List<CoachAvailabilityWindow> windows = coachAvailabilityWindowRepository.findByCoachId(coach.getId());
     if (!bookingService.isSlotWithinAvailabilityWindow(newStart, newEnd, windows)) {
         throw new OperationNotAllowedException(
             "Proposed slot is not within coach availability",
             Map.of("proposed start time", newStart, "proposed end time", newEnd),
             BookingError.SLOT_OUTSIDE_AVAILABILITY);
     }
     ```
     `bookingService` is already injected on this class.
   - **Unit tests** in `BookingDuplicationServiceTest`: same pattern as AC1 — add the `@Mock`, wire it into
     the `new BookingDuplicationService(...)` call (appended last), add a rejection test (stub
     `isSlotWithinAvailabilityWindow` to return `false`, assert the throw + `SLOT_OUTSIDE_AVAILABILITY`) and
     confirm the file's existing happy-path test(s) still pass once the mock is stubbed to return `true` in
     `setUp()`'s default fixture (whichever of `@BeforeEach` stubbing or per-test stubbing already matches
     this file's existing convention for `bookingService`'s other stubbed methods — follow that, don't invent
     a new stubbing style).
   - **Correction — story-review.md Finding 1: an integration-test change IS required, and skipping it breaks
     a currently-green test.** `BookingDuplicationService` has no *dedicated* `*ResourceIT` of its own, but
     `RescheduleResourceIT.java` — the exact file AC1 is already modifying — contains a full HTTP-level
     happy-path test for this endpoint: `duplicateNextWeek_asOwningCoachWithCompletedBooking_returns204`
     (`:672-687`). AC1's mandated 7-day wide-open fixture does **not** save this test on its own: the test
     calls the file's `setBookingStatus("COMPLETED")` helper (`:945-951`), which updates only `status` and
     `requested_start_time` (to `Instant.now().minus(2, DAYS)`), leaving `requested_end_time` untouched at
     its `insertConfirmedBooking`-seeded value (`Instant.now().plus(2, DAYS).plus(1, HOURS)`). That gives the
     booking this test duplicates an actual span of ~4 days 1 hour. `duplicateNextWeek` adds 7 days to both
     bounds, preserving that same ~4-day span — and `isSlotWithinAvailabilityWindow` anchors `windowEnd` to
     the session's *start* calendar date only, so no single-day window, wide-open or not, can ever satisfy a
     multi-day span. Once AC2's check lands, this test starts returning 403/`booking.slotOutsideAvailability`
     instead of 204, regardless of AC1's fixture.
     **Required fix:** change `setBookingStatus` to also advance `requested_end_time` by the same offset,
     preserving the original 1-hour duration `insertConfirmedBooking` set up:
     ```java
     private void setBookingStatus(String status) {
         transactionTemplate.execute(s -> {
             Instant newStart = Instant.now().minus(2, ChronoUnit.DAYS);
             jdbcTemplate.update(
                 "UPDATE booking.bookings SET status = ?, requested_start_time = ?, requested_end_time = ? WHERE id = ?",
                 status, Timestamp.from(newStart), Timestamp.from(newStart.plus(1, ChronoUnit.HOURS)), bookingId);
             return null;
         });
     }
     ```
     Verified safe: `setBookingStatus`'s other two callers
     (`requestReschedule_bookingNotInReschedulableStatus_returns403WithNotReschedulableKey`,
     `acceptReschedule_bookingNoLongerReschedulable_returns403WithNotReschedulableKey`) both reject on booking
     status before either the duration or the (pre-existing) code path ever reads `requested_end_time`, so
     this change doesn't affect their behavior. `duplicateNextWeek_asParent_returns403` is rejected by
     `@PreAuthorize` before the method body runs at all (parent calling a coach-only endpoint), so it is
     unaffected either way.
   - **No frontend change needed — verified, not assumed.** `duplicateNextWeek`'s only frontend caller,
     `CoachCommandCenterPage.vue`'s `handleRepeatNextWeek` (`:448-460`), already wraps the call in a bare
     `catch { $q.notify({ message: t('booking.schedule.repeatFailed'), ... }) }` with **no** `errorKey`
     discrimination at all — every rejection, old or new, already surfaces the same generic toast. Do not add
     an errorKey branch here; there is nothing to add it to.

3. **AC3 — `ParentBookingsPage.vue`'s reschedule-submission error handling recognizes the new rejection.**
   - In `src/frontend/src/pages/parent/ParentBookingsPage.vue`'s `submitReschedule()` `catch` block
     (`:209-233`), add one new `else if` branch, in the existing if/else chain, mirroring the shape of every
     sibling branch exactly (e.g. the `booking.invalidTimeRange` branch immediately above it):
     ```js
     } else if (errorKey === 'booking.slotOutsideAvailability') {
       $q.notify({ message: t('booking.errors.slotOutsideAvailability'), type: 'negative' })
     ```
     Place it anywhere in the existing chain before the final generic `else` — position within the chain has
     no behavioral effect, but placing it adjacent to `booking.invalidTimeRange`/`booking.rescheduleAlreadyPending`
     groups the request-validation rejections together for readability.
   - **No new i18n key needed — verified already present in all three shipped locale bundles**, unlike a
     typical new-errorKey addition: `booking.errors.slotOutsideAvailability` already exists in
     `src/frontend/src/i18n/en-US/index.js:925`, `de-DE/index.js:469`, and `fr-FR/index.js:1207` — it was
     added for `BookingRequestPage.vue`'s identical rejection on the initial-booking path and is simply
     unreferenced by this reschedule path today. Do not add a duplicate key.
   - **Manually exercise** (this repo has no frontend test suite — see Dev Notes): confirm a reschedule
     proposal inside the coach's availability still submits successfully (unchanged happy path), and confirm
     a proposal outside it now shows the "outside available hours" toast instead of falling through to the
     generic `console.warn('[booking] unmapped errorKey:', ...)` + `booking.reschedule.requestFailed` path.

4. **AC4 — `RescheduleService.acceptReschedule` re-validates the proposed window against the coach's
   availability at accept time, not just at request time.** Added per story-review.md Finding 4: the story's
   own stated semantics are "current availability, re-validated" — but `acceptReschedule` is the method that
   actually finalizes `requested_start_time`/`requested_end_time` (`:217-218`), and it already re-checks
   other proposal-time facts against present-tense state right before finalizing: `proposedStartTime` is
   future again (`:161-164`), the coach isn't suspended (`:193-204`), and the slot doesn't overlap another
   booking (`:206-215`). Availability was conspicuously absent from that list. A reschedule request can sit
   `PENDING` for an arbitrary time; if the coach narrows their availability after the parent's proposal but
   before the coach accepts, `acceptReschedule` would otherwise still finalize a booking outside the coach's
   now-current availability — the exact problem this story exists to close, just reached through the accept
   door instead of the request door. Closing this is a direct, consistent extension of the semantics already
   decided for AC1/AC2, not a new decision.
   - In `acceptReschedule`, add the identical check immediately **after** the coach-suspension check
     (`:201-204`) and **before** the existing overlap check (`:206-215`):
     ```java
     List<CoachAvailabilityWindow> windows = coachAvailabilityWindowRepository.findByCoachId(coach.getId());
     if (!bookingService.isSlotWithinAvailabilityWindow(req.getProposedStartTime(), req.getProposedEndTime(), windows)) {
         throw new OperationNotAllowedException(
             "Proposed slot is not within coach availability",
             Map.of("submitted coach id", coach.getId(), "proposed start time", req.getProposedStartTime(),
                 "proposed end time", req.getProposedEndTime()),
             BookingError.SLOT_OUTSIDE_AVAILABILITY);
     }
     ```
     No new field/import needed beyond AC1's own `coachAvailabilityWindowRepository` addition to this same
     class.
   - **Unit test** in `RescheduleServiceTest`: add one test mirroring `acceptReschedule_suspendedCoach_throwsCoachUnavailable`'s
     shape exactly (stub `isSlotWithinAvailabilityWindow` to return `false`, assert the throw carries
     `SLOT_OUTSIDE_AVAILABILITY`), plus confirm `acceptReschedule_coachOwnsBooking_updatesTimesAndStatus`
     still passes once the mock is stubbed `true` in its own setup (same unstubbed-default-`false` risk as
     AC1's Finding 2 — check every existing `acceptReschedule` unit test that reaches this far and add the
     stub where needed, don't assume only the two named tests are affected).
   - **Integration test optional, not required.** Triggering this rejection via HTTP needs the same
     late-night/crosses-midnight construction as AC1's Finding-3 fix (this file's wide-open fixture applies
     here too) plus timing an accept after a window change — meaningfully more IT complexity for a case the
     unit test already proves at the service layer. Skip the IT unless it can be added cheaply; do not force
     it.

5. **AC5 — Ledger hygiene.** In `deferred-work.md`, tag the D1 item (the `RescheduleService`/
   `BookingDuplicationService` availability-window paragraph) with
   `` `[PICKED UP by skillars-deferred-49 AC1, AC2, AC4]` ``.

## Tasks / Subtasks

- [ ] Task 1: RescheduleService request-time availability check (AC: #1)
  - [ ] 1.1 Add `coachAvailabilityWindowRepository` field + imports to `RescheduleService`.
  - [ ] 1.2 Add the availability-window check to `requestReschedule`, positioned per AC1.
  - [ ] 1.3 Add the two new `RescheduleServiceTest` unit tests (reject / accept).
  - [ ] 1.4 Fix the two pre-existing `RescheduleServiceTest` tests broken by 1.2 (story-review.md Finding 2
    — `requestReschedule_parentOwnsBooking_confirmedStatus_createsRequest` and
    `requestReschedule_legacyThreeHourBooking_movesAtItsOwnLength`), and check every other existing test in
    the file that reaches past the duration check for the same unstubbed-default-`false` risk.
  - [ ] 1.5 Seed the 7-day wide-open availability window fixture in `RescheduleResourceIT.setUp()` — required
    for every existing test in this file to keep passing (see AC1's fixture note; do not skip).
  - [ ] 1.6 Add `requestReschedule_slotOutsideAvailabilityWindow_returns403WithSlotOutsideAvailabilityKey` to
    `RescheduleResourceIT`, using the late-night/crosses-midnight construction specified in AC1 (story-review.md
    Finding 3 — a "far future" proposal cannot trigger this rejection under the 7-day wide-open fixture).
  - [ ] 1.7 Run targeted verification for both the touched unit tests and IT and confirm green (see Dev Notes
    — `*IT` classes run under `maven-failsafe-plugin`, not `mvn test`).
- [ ] Task 2: BookingDuplicationService availability check (AC: #2)
  - [ ] 2.1 Add `coachAvailabilityWindowRepository` field + imports to `BookingDuplicationService`.
  - [ ] 2.2 Add the availability-window check to `duplicateNextWeek`, positioned per AC2.
  - [ ] 2.3 Add the `BookingDuplicationServiceTest` unit tests (reject / accept), confirming existing
    happy-path tests still pass once the new mock is stubbed.
  - [ ] 2.4 Fix `RescheduleResourceIT`'s `setBookingStatus` helper to also advance `requested_end_time`
    (story-review.md Finding 1 — required or `duplicateNextWeek_asOwningCoachWithCompletedBooking_returns204`
    breaks regardless of Task 1.5's fixture). This is a change to a file Task 1 already touches, not a new IT
    file.
  - [ ] 2.5 Run targeted verification and confirm green.
- [ ] Task 3: Frontend reschedule error handling (AC: #3)
  - [ ] 3.1 Add the `booking.slotOutsideAvailability` branch to `ParentBookingsPage.vue`'s `submitReschedule()`.
  - [ ] 3.2 Manually exercise the happy path and the new rejection path.
  - [ ] 3.3 Run `npx eslint` on the touched file and confirm clean.
- [ ] Task 4: RescheduleService accept-time availability check (AC: #4)
  - [ ] 4.1 Add the availability-window check to `acceptReschedule`, positioned per AC4.
  - [ ] 4.2 Add the new `RescheduleServiceTest` unit test (reject), and fix any existing `acceptReschedule`
    unit test broken by the unstubbed-default-`false` risk (same class of issue as Task 1.4).
  - [ ] 4.3 Run targeted verification and confirm green.
- [ ] Task 5: Ledger hygiene (AC: #5) — apply the `[PICKED UP]` tag specified above.

## Dev Notes

- **The semantics decision (current availability, re-validated at request time) is already made — do not
  re-litigate it.** The dev agent's job is implementation, not re-evaluating "current vs. as-booked
  availability."
- **Reuse `BookingService.isSlotWithinAvailabilityWindow`, do not write a second copy.** It is already
  package-private and already shared by `BookingBatchService` for the identical reason — a copy would drift
  from its cross-midnight anchoring and invalid-timezone handling, exactly the risk its own comment already
  warns against.
- **AC1's `RescheduleResourceIT` fixture change is not optional polish — it is required for correctness.**
  Implementing AC1's production-code check without also seeding the 7-day wide-open window will make roughly
  two dozen pre-existing tests in that file fail (nondeterministically, depending on wall-clock timing at
  test-run time), not just the one new test. Do this fixture change *before* running the full file's test
  suite, not after chasing individual failures.
- **This story went through one review pass (story-review.md) before dev started, and the fixes are already
  folded into AC1/AC2/AC4 above — do not treat this as optional context.** Three of the four findings were
  concrete test breakages the original draft's own scoping text asserted wouldn't happen: (1) AC2 originally
  claimed no IT change was needed, but `duplicateNextWeek_asOwningCoachWithCompletedBooking_returns204` in
  the very file AC1 touches breaks regardless of AC1's fixture, needing a fix to `setBookingStatus` itself;
  (2) two pre-existing `RescheduleServiceTest` tests break the moment AC1's check lands, because an unstubbed
  Mockito `@Mock` boolean method defaults to `false`; (3) AC1's originally-suggested "far enough in the
  future" new-IT-test approach cannot actually trigger the rejection it's meant to test, given AC1's own
  mandated wide-open-every-day fixture — it needs the late-night/crosses-midnight construction specified in
  AC1 instead. The fourth (AC4, accept-time re-validation) was a design gap the review surfaced and this
  story's own creation process decided to close for consistency with the already-decided semantics, rather
  than leaving it as an open question for the dev agent.
- **IT-execution gotcha (recorded by `skillars-deferred-47`'s dev pass, still applies):** this project's `*IT`
  classes run under `maven-failsafe-plugin`, bound to `integration-test`/`verify`, **not** `mvn test`. Use
  `mvn -o integration-test -Dit.test=RescheduleResourceIT` (and `BookingRequestResourceIT` if touched) and
  confirm a `target/failsafe-reports/...txt` report was actually written.
- **No new frontend automated test coverage** — standing repo-wide gap, recorded by every prior
  `skillars-deferred-*` frontend-only change. Manual exercise per AC3's own text is this project's established
  verification path here.
- Per `docs/validation-strategy.md`, run targeted verification only: `mvn test`/`mvn integration-test` scoped
  to the touched backend classes/ITs, and `npx eslint` on the one touched frontend file — do not run a full
  `mvn verify` or full frontend build unless targeted verification proves insufficient.

### Project Structure Notes

- `src/main/java/com/softropic/skillars/platform/booking/service/RescheduleService.java` — one new field,
  two new imports, one new check in `requestReschedule` (AC1).
- `src/main/java/com/softropic/skillars/platform/booking/service/BookingDuplicationService.java` — one new
  field, two new imports, one new check in `duplicateNextWeek` (AC2).
- `src/test/java/com/softropic/skillars/platform/booking/service/RescheduleServiceTest.java` — new mock,
  two new `requestReschedule` tests + fixes to two pre-existing ones (AC1), one new `acceptReschedule` test +
  any existing-test fixes needed there (AC4).
- `src/test/java/com/softropic/skillars/platform/booking/service/BookingDuplicationServiceTest.java` — new
  mock + new test(s) (AC2).
- `src/test/java/com/softropic/skillars/platform/booking/api/RescheduleResourceIT.java` — `setUp()` fixture
  change (7-day window seed, AC1) + `setBookingStatus` helper fix (AC2) + one new test (AC1).
- `src/frontend/src/pages/parent/ParentBookingsPage.vue` — one new `else if` branch in `submitReschedule()`
  (AC3).
- `_bmad-output/implementation-artifacts/deferred-work.md` — one `[PICKED UP]` tag (AC5).
- No changes to `BookingService.java` (its `isSlotWithinAvailabilityWindow` method is reused as-is, already
  package-private, no visibility change needed), `CoachCommandCenterPage.vue` (verified no errorKey
  discrimination exists there to extend — AC2), `booking.api.js`/`booking.store.js` (no new endpoint, no
  contract change — both services already return the same error shape their callers already handle), or any
  i18n bundle (the key already exists in all three locales — AC3).

### References

- [Source: `_bmad-output/implementation-artifacts/deferred-work.md` line 1251, under
  `## Deferred from: skillars-uat-2-session-duration-and-booking-slot-integrity (2026-08-10)` — this story's
  sole source item]
- [Source: `src/main/java/com/softropic/skillars/platform/booking/service/BookingService.java:220-224,827-851`
  — `isSlotWithinAvailabilityWindow`'s definition and its existing call site in `createBookingRequest`, the
  pattern both ACs mirror]
- [Source: `src/main/java/com/softropic/skillars/platform/booking/service/RescheduleService.java:67-135` —
  `requestReschedule`, AC1's target]
- [Source: `src/main/java/com/softropic/skillars/platform/booking/service/BookingDuplicationService.java:36-83`
  — `duplicateNextWeek`, AC2's target]
- [Source: `src/main/java/com/softropic/skillars/platform/booking/contract/BookingError.java:36,51` —
  `SLOT_OUTSIDE_AVAILABILITY` / `"booking.slotOutsideAvailability"`, already defined, reused not added]
- [Source: `src/test/java/com/softropic/skillars/platform/booking/api/RescheduleResourceIT.java:44-160` —
  existing fixture/test conventions AC1 extends, and the absence of any `coach_availability_windows` seed
  that makes the fixture change mandatory]
- [Source: `src/test/java/com/softropic/skillars/platform/booking/api/BookingRequestResourceIT.java:59-73,487-507`
  — the sibling "far outside any window" IT convention AC1's new test mirrors]
- [Source: `src/frontend/src/pages/parent/ParentBookingsPage.vue:195-235` — `submitReschedule()`'s existing
  errorKey if/else chain, AC3's target]
- [Source: `src/frontend/src/pages/coach/CoachCommandCenterPage.vue:448-460` — `handleRepeatNextWeek`,
  verified to need no change for AC2]
- [Source: `src/frontend/src/i18n/en-US/index.js:925`, `de-DE/index.js:469`, `fr-FR/index.js:1207` —
  `booking.errors.slotOutsideAvailability`, already present, reused not added]
- [Source: `src/test/java/com/softropic/skillars/platform/booking/service/RescheduleServiceTest.java:378-401`
  — `acceptReschedule_suspendedCoach_throwsCoachUnavailable`, the accept-time re-check precedent AC4's new
  test mirrors]
- [Source: `docs/validation-strategy.md` — targeted-test-only validation policy]

## Change Log

| Date | Change |
|---|---|
| 2026-08-21 | Story created via story-creation process, as a single substantial item (not a bundle) — the standard bundling ledger ran dry of small/decision-light items this pass (re-mined in full; every remaining candidate was either explicitly decision-needing without a resolvable-in-scope answer, a standing accepted tradeoff, or already deliberately-not-fixed/spec-intentional). Source: `deferred-work.md` D1 (`RescheduleService`/`BookingDuplicationService` availability-window gap), re-verified against live code — both gaps remain real and unfixed. The item's own named semantics question ("current vs. as-booked availability") was explicitly resolved by the user before story creation (2026-08-21: current availability), not left to the dev agent. Both fixes reuse the existing `BookingService.isSlotWithinAvailabilityWindow` and the existing `SLOT_OUTSIDE_AVAILABILITY` error — no new validation logic or error code. AC1 additionally required discovering and specifying a mandatory `RescheduleResourceIT` fixture change (no availability window currently seeded there) to avoid breaking ~25 pre-existing tests. |
| 2026-08-21 | `story-review.md` applied: 4 findings, all fixed before dev started. Finding 1/High: AC2's "no IT change required" claim was wrong — `duplicateNextWeek_asOwningCoachWithCompletedBooking_returns204` (in the file AC1 already touches) breaks regardless of AC1's fixture, because `setBookingStatus` leaves the booking with a ~4-day span; fixed by specifying a required fix to that helper. Finding 2/High: two pre-existing `RescheduleServiceTest` tests break the moment AC1's check lands (unstubbed Mockito boolean defaults to `false`); fixed by specifying the required stub addition to both. Finding 3/Medium: AC1's originally-suggested "far future" new-IT-test approach cannot trigger the rejection under AC1's own wide-open-every-day fixture; fixed by replacing it with a late-night/crosses-midnight construction. Finding 4/Low: `acceptReschedule` never re-validated availability at accept time despite re-checking every other proposal-time fact there; closed by adding new AC4 (accept-time re-check), a direct consistency extension of the already-decided semantics rather than a new open question. Story now has 5 ACs; Tasks/Dev Notes/Project Structure Notes updated to match. |
