# Story Deferred-50: Duplicate-Next-Week Overlap Guard, Reschedule Ordinary-Hours IT Coverage & Availability-Check Argument Verification

Status: ready-for-dev

## Story

As an engineer operating this platform,
I want `BookingDuplicationService.duplicateNextWeek` to check for double-booking the same way
`RescheduleService.acceptReschedule` already does, `RescheduleResourceIT` to actually exercise the
"coach doesn't work this day" scenario its availability-window rejection exists for, and the
availability-window unit tests to verify the real arguments they pass rather than stubbing blindly,
so that three concrete, decision-light gaps left open by `skillars-deferred-49`'s own code review are
closed before they age into the ledger's growing backlog of "worth doing eventually" items.

### Why this story exists

All three items below were filed by `skillars-deferred-49`'s code review
(`_bmad-output/implementation-artifacts/deferred-work.md`, section `## Deferred from: code review of
skillars-deferred-49-reschedule-and-duplicate-current-availability-window-enforcement (2026-08-21)`),
the story immediately preceding this one. Each was re-verified against the live repo during this
story's creation, not just trusted from the ledger text — all three still hold exactly as described.

Three other items from the same review section (the validation-logic-duplication DRY nit, the
`acceptReschedule` unlocked-read TOCTOU race against `CoachProfileService.saveStep4`, and the
`duplicateNextWeek` DST-shift-of-duplicated-time quirk) were **not** picked up — each is either an
explicitly-accepted project convention (the DRY nit mirrors `skillars-deferred-48`'s own dismissal of
an identical finding), or needs a design decision this bundled small-fix story shouldn't make ad hoc
(the locking-strategy change and the DST-shift semantics both spill into other services/behaviors well
beyond this story's three targets). A fourth item — `isSlotWithinAvailabilityWindow`'s inability to
match a coach's own overnight window — is the same class of design-decision-needed gap and is also
left alone.

- **D1 (this story's AC1) — `BookingDuplicationService.duplicateNextWeek` has no overlap/double-booking
  check against other bookings.** Only the coach-availability-window check (`skillars-deferred-49`
  AC2) and the DB-level exclusion constraint at commit guard it, unlike
  `RescheduleService.acceptReschedule`'s explicit `bookingRepository.findOverlappingBookings(...)` call
  for the equivalent finalization step. A constraint-fires-at-commit failure surfaces as an unmapped
  500 rather than a clean rejection — the same class of gap `acceptReschedule`'s own overlap check
  (`skillars-deferred-14` AC4) was added to close.
- **D2 (this story's AC2) — `RescheduleResourceIT`'s one dedicated IT test for `SLOT_OUTSIDE_AVAILABILITY`
  only proves the midnight-crossing edge case works, not the "ordinary hours, coach just doesn't work
  this day" scenario the AC actually exists for.** The file's fixture is necessarily a wide-open,
  every-day-of-week window for `coachProfileId` (required so ~25 pre-existing tests' day-agnostic
  `Instant.now().plus(N, DAYS)` proposals keep passing), so the only way to trigger the rejection under
  it is a session crossing past `23:59:59` on the start's own calendar date. `coachProfile2Id` already
  exists in this file's fixture (seeded with a coach profile, pricing, and a user login) but carries no
  availability windows and no booking of its own — exactly the gap needed to write a real
  narrow-window test without touching `coachProfileId`'s fixture at all.
- **D3 (this story's AC3) — the availability-window unit tests stub
  `isSlotWithinAvailabilityWindow(any(), any(), any())` and never verify the actual start/end/windows
  arguments passed**, so an argument-swap regression (e.g. checking the original booking's time instead
  of the proposed one) would not be caught by this test suite.

## Acceptance Criteria

1. **AC1 — `BookingDuplicationService.duplicateNextWeek` rejects a computed next-week window that
   overlaps another active booking for the same coach, mirroring `RescheduleService.acceptReschedule`'s
   existing overlap check.**
   - In `src/main/java/com/softropic/skillars/platform/booking/service/BookingDuplicationService.java`,
     add the overlap check immediately **after** the existing availability-window check (the block ends
     at `:73` in the current file) and **before** `packSessionService.findActivePackId(...)` (`:75`) —
     the same relative position `acceptReschedule` uses (availability check, then overlap check, then
     finalize):
     ```java
     List<Booking> overlapping = bookingRepository.findOverlappingBookings(
         coach.getId(), newStart, newEnd,
         BookingService.ACTIVE_SLOT_STATUSES_EXCLUDING_REQUESTED, null);
     if (!overlapping.isEmpty()) {
         throw new OperationNotAllowedException(
             "The proposed slot is no longer available — another booking occupies that time",
             Map.of("coach id", coach.getId(), "proposed start time", newStart, "proposed end time", newEnd),
             BookingError.SLOT_UNAVAILABLE);
     }
     ```
     `bookingRepository` is already injected on this class; `BookingService.ACTIVE_SLOT_STATUSES_EXCLUDING_REQUESTED`
     is package-private and `BookingDuplicationService` is already in the same
     `com.softropic.skillars.platform.booking.service` package (it already reuses
     `bookingService.isSlotWithinAvailabilityWindow`, the identical visibility situation). Pass `null`
     for `excludeBookingId` — unlike `acceptReschedule`, which excludes the very booking it is
     finalizing (already active in the DB under its pre-reschedule time), `duplicateNextWeek`'s new
     booking does not exist yet, so there is nothing to exclude. `BookingError.SLOT_UNAVAILABLE` is
     already defined and already used by `acceptReschedule` for the identical rejection — no new error
     code.
   - **Deliberately out of scope: no new coach-row locking.** `acceptReschedule` takes a
     `PESSIMISTIC_WRITE` lock on the coach row before its overlap check (`RescheduleService.java:208-215`);
     `duplicateNextWeek` reads the coach profile unlocked (`coachProfileRepository.findByUserId`, no
     `findByIdForUpdate`) and this AC does not change that. Adding coach-row locking here would be a
     larger, separately-reasoned concurrency change (it also touches the coach-suspension check
     `acceptReschedule` performs under the same lock, which `duplicateNextWeek` has never had) — out of
     this bundled story's scope. The new overlap check narrows the pre-commit rejection window from
     "never checked, relies entirely on the DB exclusion constraint" to "checked, same TOCTOU race the
     unlocked read already had" — a real improvement, not a complete fix, matching how this ledger item
     was scoped.
   - **Unit tests** in `BookingDuplicationServiceTest`: add a rejection test mirroring
     `duplicateNextWeek_slotOutsideAvailabilityWindow_throwsSlotOutsideAvailability`'s shape (`:164-181`)
     — stub `bookingService.isSlotWithinAvailabilityWindow(any(), any(), any())` to return `true` (so
     execution reaches the new check) and `bookingRepository.findOverlappingBookings(any(), any(), any(),
     any(), any())` to return a non-empty `List.of(new Booking())`, assert the throw carries
     `BookingError.SLOT_UNAVAILABLE`, and assert (via `verify(..., never())`) that `bookingRepository.save`
     and `packSessionService.findActivePackId` are never reached. Also add `when(bookingRepository
     .findOverlappingBookings(any(), any(), any(), any(), any())).thenReturn(List.of());` to the existing
     `duplicateNextWeek_completedBooking_createsNewRequestedBookingAdvancedBy7DaysAndCarriesOverPack` test
     (`:79-117`) — it is the only existing test that reaches past the new check into `bookingRepository.save(...)`,
     and an unstubbed Mockito `@Mock` method returning `List` defaults to `null`, which would NPE at
     `overlapping.isEmpty()` rather than cleanly failing the test with a clear assertion — check every
     other existing test in the file for the same unstubbed-default risk before assuming only this one
     test is affected (the class's own established caution, see `skillars-deferred-49`'s Task 1.4/4.2
     for the identical pattern on a boolean mock).
   - **Integration test optional, not required.** `RescheduleResourceIT`'s existing
     `duplicateNextWeek_asOwningCoachWithCompletedBooking_returns204` test has no second booking to
     overlap against, so it is unaffected by this AC and needs no change. Adding a dedicated new IT for
     the overlap rejection would need a second booking fixture at exactly the duplicated slot — doable,
     but the unit test above already proves the behavior at the service layer with less added
     complexity; skip the IT unless it can be added cheaply, matching `skillars-deferred-49` AC4's own
     "integration test optional" precedent for the analogous `acceptReschedule` re-validation case.

2. **AC2 — `RescheduleResourceIT` gains a genuine "coach doesn't work this day" rejection test, using
   `coachProfile2Id`'s already-seeded-but-unused fixture slot, distinct from the existing
   midnight-crossing test.**
   - In `src/test/java/com/softropic/skillars/platform/booking/api/RescheduleResourceIT.java`'s
     `setUp()` (`:74-175`), after the existing `coachProfile2Id` coach-profile/pricing inserts (`:143-152`)
     and before the `packTierId`/pack-purchase block (`:154-170`, which is `coachProfileId`-scoped and
     must stay that way — do not touch it), add:
     - **One narrow availability window** for `coachProfile2Id`: a single day of week (pick the day of
       week two days from "now" at `Europe/Berlin` reliably falls on, computed at test-run time via
       `ZonedDateTime.now(ZoneId.of("Europe/Berlin")).plusDays(2).getDayOfWeek().getValue()`, so the test
       is not itself flaky against a fixed day-of-week constant), `start_time = '08:00:00'`,
       `end_time = '18:00:00'`, `canonical_timezone = 'Europe/Berlin'` — the same shape
       `BookingRequestResourceIT`'s own narrow-window fixture already uses (cited in `skillars-deferred-49`'s
       AC1 as the precedent this file's own wide-open fixture deliberately diverges from).
     - **One `CONFIRMED` booking** owned by `coachProfile2Id`, inside that window (e.g. `+2 days` at
       `10:00`–`11:00` local time), mirroring the shape `insertConfirmedBooking(bookingId)` already
       creates for `coachProfileId` at `:172` — a `requestReschedule` call needs an existing
       reschedulable booking to propose against, and none of this file's ~25 existing tests touch
       `coachProfile2Id`, so this is additive only.
   - Add one new test, `requestReschedule_ordinaryHoursCoachDoesNotWorkThisDay_returns403WithSlotOutsideAvailabilityKey`,
     calling `requestReschedule` against the new `coachProfile2Id` booking with a proposed time on a
     **different** day of week than the one seeded above (e.g. `+3 days` at the same clock hour — outside
     the single-day window, an ordinary daytime hour, not a midnight-crossing construction), asserting
     `403` + `errorMsg.errorKey` equals `"booking.slotOutsideAvailability"`, mirroring the existing
     `requestReschedule_slotOutsideAvailabilityWindow_returns403WithSlotOutsideAvailabilityKey`
     midnight-crossing test's assertion shape exactly (`assertThatThrownBy(...).isInstanceOf(HttpClientErrorException.class)`
     with the same two-property `.satisfies(...)` block).
   - **Do not modify or remove the existing midnight-crossing test.** It proves a different, real edge
     case (the helper's start-date-anchored window-end behavior) and stays as-is; this AC adds coverage,
     it does not replace anything.
   - **Login/session handling:** use `loginAndGetCookies(COACH_2_EMAIL)` for any coach-side call this
     new fixture might also support later, but this AC's one new test is a **parent**-initiated
     `requestReschedule` call, so it authenticates as `PARENT_EMAIL` exactly like every other
     `requestReschedule` test in this file — `COACH_2_EMAIL`/`COACH_2_USER_ID` already exist in the
     file's constants (`:55,59`) purely for the coach-ownership tests that already use `coachProfile2Id`
     as a foil; no new constant needed.

3. **AC3 — the availability-window unit tests verify the actual arguments passed to
   `isSlotWithinAvailabilityWindow`, not just that it was called with `any()`.**
   - In `src/test/java/com/softropic/skillars/platform/booking/service/RescheduleServiceTest.java`, pick
     one representative existing happy-path test per call site already covered by this story's target
     method set — `requestReschedule_parentOwnsBooking_confirmedStatus_createsRequest` (request-time) and
     `acceptReschedule_coachOwnsBooking_updatesTimesAndStatus` (accept-time) — and add an
     `ArgumentCaptor<Instant>`-based (or `verify(bookingService).isSlotWithinAvailabilityWindow(eq(...),
     eq(...), any())`, whichever fits the test's existing stubbing style with the least disruption)
     assertion that the `startTime`/`endTime` arguments passed match the request's own
     `proposedStartTime`/`proposedEndTime` — not, for example, the booking's original
     `requestedStartTime`/`requestedEndTime`, which is the exact argument-swap class of regression this
     ledger item names. Do not touch every test in the file — one assertion per call site (request-time,
     accept-time) is the story's actual ask; broader coverage is optional polish, not required.
   - In `src/test/java/com/softropic/skillars/platform/booking/service/BookingDuplicationServiceTest.java`,
     add the equivalent assertion to
     `duplicateNextWeek_completedBooking_createsNewRequestedBookingAdvancedBy7DaysAndCarriesOverPack`
     (`:79-117`, the one happy-path test that already computes and asserts `expectedStart` at `:105-106`)
     — verify `isSlotWithinAvailabilityWindow` was called with that same `expectedStart`/its
     corresponding end time, not the original booking's pre-duplication times.
   - **Do not change any production code for this AC.** This is a test-only hardening pass; if any of
     these new assertions fail against the current implementation, that is a signal the implementation
     itself has the argument-swap bug this AC is meant to catch — stop and report it rather than
     adjusting the assertion to match, since AC1/AC2/AC4's own positioning notes in `skillars-deferred-49`
     were all independently verified correct by that story's Acceptance Auditor pass.

4. **AC4 — Ledger hygiene.** In `deferred-work.md`, tag all three items under the `## Deferred from: code
   review of skillars-deferred-49-...` section:
   - The `duplicateNextWeek` no-overlap-check item → `` `[PICKED UP by skillars-deferred-50 AC1]` ``
   - The `RescheduleResourceIT` ordinary-hours-coverage item → `` `[PICKED UP by skillars-deferred-50 AC2]` ``
   - The unstubbed-argument-verification item → `` `[PICKED UP by skillars-deferred-50 AC3]` ``
   Leave the other three items in that section (DRY duplication, unlocked-read TOCTOU race,
   `duplicateNextWeek` DST-shift quirk) and the fourth from `skillars-deferred-49` proper
   (`isSlotWithinAvailabilityWindow`'s cross-midnight-window limitation) untouched — none are picked up
   by this story, per the scoping reasoning in "Why this story exists" above.

## Tasks / Subtasks

- [ ] Task 1: `BookingDuplicationService` overlap/double-booking check (AC: #1)
  - [ ] 1.1 Add the `findOverlappingBookings` check to `duplicateNextWeek`, positioned per AC1.
  - [ ] 1.2 Add the new `BookingDuplicationServiceTest` rejection test, and fix the one existing test
    that reaches past the new check with the required `List.of()` stub (check every other existing test
    in the file for the same unstubbed-default risk, don't assume only the one named test is affected).
  - [ ] 1.3 Run targeted verification and confirm green.
- [ ] Task 2: `RescheduleResourceIT` ordinary-hours coverage (AC: #2)
  - [ ] 2.1 Seed the narrow availability window and the `CONFIRMED` booking for `coachProfile2Id` in
    `setUp()`, without touching the `coachProfileId`-scoped pack fixture below it.
  - [ ] 2.2 Add `requestReschedule_ordinaryHoursCoachDoesNotWorkThisDay_returns403WithSlotOutsideAvailabilityKey`.
  - [ ] 2.3 Run the full `RescheduleResourceIT` suite (not just the new test) and confirm every
    pre-existing test — including the ~25 that key off `coachProfileId`'s wide-open fixture — still
    passes; the new `coachProfile2Id` fixture must be additive-only.
- [ ] Task 3: Availability-check argument verification (AC: #3)
  - [ ] 3.1 Add the request-time and accept-time argument assertions to `RescheduleServiceTest`.
  - [ ] 3.2 Add the duplicate-next-week argument assertion to `BookingDuplicationServiceTest`.
  - [ ] 3.3 Confirm all three new assertions pass against the current, unmodified production code; if
    any fails, stop and report rather than silently adjusting the assertion.
- [ ] Task 4: Ledger hygiene (AC: #4) — apply the three `[PICKED UP]` tags specified above.

## Dev Notes

- **This story bundles three independent, decision-light findings from `skillars-deferred-49`'s own
  code review — it is not a single coherent feature.** Implement and verify each AC independently; there
  is no cross-AC dependency (AC1 touches `BookingDuplicationService.java` + its unit test, AC2 touches
  only `RescheduleResourceIT.java`, AC3 touches all three test files). Task order above is a
  convenience, not a requirement.
- **Reuse existing patterns, do not invent new ones.** AC1 mirrors `acceptReschedule`'s
  already-shipped overlap check verbatim in shape (same repository method, same status-list constant,
  same error code). AC2 mirrors `BookingRequestResourceIT`'s narrow-window fixture shape, already cited
  as precedent inside `skillars-deferred-49`'s own AC1. AC3 uses whatever Mockito verification style
  (`ArgumentCaptor` vs. `eq(...)`) requires the least disruption to each test's existing stubbing —
  check the file's own existing conventions before choosing.
- **`IT`-execution gotcha (recorded by `skillars-deferred-47`'s dev pass, still applies):** this
  project's `*IT` classes run under `maven-failsafe-plugin`, bound to `integration-test`/`verify`, **not**
  `mvn test`. Use `mvn -o integration-test -Dit.test=RescheduleResourceIT` and confirm a
  `target/failsafe-reports/...txt` report was actually written.
- Per `docs/validation-strategy.md`, run targeted verification only: `mvn test`/`mvn integration-test`
  scoped to the touched backend classes/ITs — do not run a full `mvn verify` unless targeted
  verification proves insufficient.
- **No frontend changes in this story.** All three ACs are backend-only (production code + tests).

### Project Structure Notes

- `src/main/java/com/softropic/skillars/platform/booking/service/BookingDuplicationService.java` — one
  new overlap check in `duplicateNextWeek`, no new field/import (`bookingRepository`,
  `BookingService.ACTIVE_SLOT_STATUSES_EXCLUDING_REQUESTED`, `BookingError.SLOT_UNAVAILABLE` all already
  in scope) (AC1).
- `src/test/java/com/softropic/skillars/platform/booking/service/BookingDuplicationServiceTest.java` —
  one new rejection test + one existing test's stub fix (AC1), one new argument-verification assertion
  on the existing happy-path test (AC3).
- `src/test/java/com/softropic/skillars/platform/booking/api/RescheduleResourceIT.java` — `setUp()`
  fixture addition (`coachProfile2Id` window + booking, additive-only) + one new test (AC2).
- `src/test/java/com/softropic/skillars/platform/booking/service/RescheduleServiceTest.java` — two new
  argument-verification assertions on existing happy-path tests, no new tests, no production-code change
  (AC3).
- `_bmad-output/implementation-artifacts/deferred-work.md` — three `[PICKED UP]` tags (AC4).
- No changes to `RescheduleService.java` (AC1–AC3 do not touch it; the file already carries all four
  `skillars-deferred-49` checks unmodified), any frontend file, or any i18n bundle.

### References

- [Source: `_bmad-output/implementation-artifacts/deferred-work.md`, section `## Deferred from: code
  review of skillars-deferred-49-reschedule-and-duplicate-current-availability-window-enforcement
  (2026-08-21)` — this story's three source items]
- [Source: `src/main/java/com/softropic/skillars/platform/booking/service/RescheduleService.java:201-247`
  — `acceptReschedule`'s existing overlap check, AC1's mirrored pattern]
- [Source: `src/main/java/com/softropic/skillars/platform/booking/service/BookingDuplicationService.java:41-101`
  — `duplicateNextWeek`, AC1's target]
- [Source: `src/main/java/com/softropic/skillars/platform/booking/repo/BookingRepository.java:21-34`
  — `findOverlappingBookings`, AC1's reused query]
- [Source: `src/test/java/com/softropic/skillars/platform/booking/api/RescheduleResourceIT.java:69-175`
  — `setUp()`, `coachProfile2Id`'s existing-but-availability-window-less fixture, AC2's target]
- [Source: `src/test/java/com/softropic/skillars/platform/booking/api/RescheduleResourceIT.java:384-404`
  — the existing midnight-crossing `SLOT_OUTSIDE_AVAILABILITY` test, AC2's sibling (not replaced)]
- [Source: `src/test/java/com/softropic/skillars/platform/booking/api/BookingRequestResourceIT.java`
  — the narrow-window fixture shape AC2 mirrors, per `skillars-deferred-49` AC1's own citation]
- [Source: `src/test/java/com/softropic/skillars/platform/booking/service/RescheduleServiceTest.java:79-181`
  — the six-plus existing `any(), any(), any()`-stubbed calls, AC3's target]
- [Source: `src/test/java/com/softropic/skillars/platform/booking/service/BookingDuplicationServiceTest.java:78-117`
  — the happy-path test AC3 extends with an argument assertion]
- [Source: `docs/validation-strategy.md` — targeted-test-only validation policy]

## Dev Agent Record

### Agent Model Used

### Debug Log References

### Completion Notes List

### File List

## Change Log

| Date | Change |
|---|---|
| 2026-08-21 | Story created via story-creation process, bundling three items filed by `skillars-deferred-49`'s own code review (`deferred-work.md`'s "code review of skillars-deferred-49-..." section) — per explicit instruction not to create another small story. All three re-verified against live code at creation time rather than trusted from ledger text: `BookingDuplicationService.duplicateNextWeek` still has no overlap check (`BookingDuplicationService.java:41-101` read directly); `RescheduleResourceIT`'s `SLOT_OUTSIDE_AVAILABILITY` coverage is still midnight-crossing-only (`:384-404`), and `coachProfile2Id` is confirmed present in the fixture with no availability window or booking of its own (`:69-175`); `RescheduleServiceTest`/`BookingDuplicationServiceTest` still stub `isSlotWithinAvailabilityWindow(any(), any(), any())` everywhere, confirmed by grep. Three other items from the same review section deliberately not picked up: the validation-logic-duplication DRY nit (matches this project's own accepted anti-abstraction convention, `skillars-deferred-48` precedent), the `acceptReschedule` unlocked-read TOCTOU race (needs a `CoachProfileService` locking-strategy decision, out of a bundled small-fix story's scope), and `duplicateNextWeek`'s DST-shift-of-duplicated-time quirk (pre-existing behavior, no proposed fix in the ledger item itself). |
