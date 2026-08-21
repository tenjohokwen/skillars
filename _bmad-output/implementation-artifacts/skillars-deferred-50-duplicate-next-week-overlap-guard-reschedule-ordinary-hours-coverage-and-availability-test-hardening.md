# Story Deferred-50: Duplicate-Next-Week Overlap Guard, Reschedule Ordinary-Hours IT Coverage & Availability-Check Argument Verification

Status: done

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
     (`:79-117`) — it is the only existing test that reaches past the new check into `bookingRepository.save(...)`.
     **Correction — story-review.md Finding 2 (Medium): the reason for this stub is NOT an NPE risk.**
     Mockito's default answer for an unstubbed `@Mock` method returning `List` is an **empty list**, not
     `null` (verified against this project's resolved `mockito-core:5.17.0`) — an unstubbed
     `findOverlappingBookings(...)` call returns `[]`, so `overlapping.isEmpty()` is `true` and execution
     simply continues past the new check silently, with no failure of any kind. Add the explicit
     `.thenReturn(List.of())` stub anyway, as good practice (self-documenting, doesn't rely on an implicit
     default), not because any existing test would otherwise NPE or fail — none of this file's other
     existing tests are actually at risk from this change (traced individually: `duplicateNextWeek_noCreditsAvailable_throws`,
     the only other test reaching past where the new check sits, throws from `packSessionService.findActivePackId`
     immediately after and never depends on the overlap result).
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
   - **Required companion fix, or `duplicateNextWeek_asOwningCoachWithCompletedBooking_returns204` breaks
     — story-review.md Finding 1 (High).** That existing test (`:720-736`) asserts the post-duplication
     booking count via `SELECT COUNT(*) FROM booking.bookings WHERE parent_id = ? AND id != ?` scoped by
     `PARENT_ID` only, with no `coach_id` filter, then asserts the result `isEqualTo(1)`. `setUp()` runs
     before every test (the DB is truncated between tests — no cross-test leakage), so once this AC's new
     `coachProfile2Id` booking exists using `PARENT_ID` (the only parent this file's fixture has —
     `insertConfirmedBooking` hardcodes it, and this AC's own new test authenticates as `PARENT_EMAIL`),
     that query's baseline count is already 1 before `duplicateNextWeek` even runs, making the post-call
     count 2 and the `isEqualTo(1)` assertion fail. This is the same class of fixture-collision breakage
     `skillars-deferred-49`'s own review already hit on this identical test (its Finding 1). Fix
     `duplicateNextWeek_asOwningCoachWithCompletedBooking_returns204`'s count query to scope by
     `coach_id = ?` (bound to `coachProfileId`) in addition to `parent_id = ?`, so it counts only bookings
     for the coach it's actually asserting about. Do this as part of Task 2, not left for Task 2.3's
     verification pass to merely catch.

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
   - **Already done — story-review.md Finding 3 (Low/Informational).** All three tags were already
     applied to `deferred-work.md` in the same commit that created this story file (verified via
     `git blame`). Task 4 requires no further code change; do not re-apply or second-guess this step when
     executing the story.

## Tasks / Subtasks

- [x] Task 1: `BookingDuplicationService` overlap/double-booking check (AC: #1)
  - [x] 1.1 Add the `findOverlappingBookings` check to `duplicateNextWeek`, positioned per AC1.
  - [x] 1.2 Add the new `BookingDuplicationServiceTest` rejection test, and fix the one existing test
    that reaches past the new check with the required `List.of()` stub (check every other existing test
    in the file for the same unstubbed-default risk, don't assume only the one named test is affected).
  - [x] 1.3 Run targeted verification and confirm green.
- [x] Task 2: `RescheduleResourceIT` ordinary-hours coverage (AC: #2)
  - [x] 2.1 Seed the narrow availability window and the `CONFIRMED` booking for `coachProfile2Id` in
    `setUp()`, without touching the `coachProfileId`-scoped pack fixture below it.
  - [x] 2.2 Add `requestReschedule_ordinaryHoursCoachDoesNotWorkThisDay_returns403WithSlotOutsideAvailabilityKey`.
  - [x] 2.3 Fix `duplicateNextWeek_asOwningCoachWithCompletedBooking_returns204`'s booking-count query to
    scope by `coach_id = ?` (`coachProfileId`) in addition to `parent_id = ?` (story-review.md Finding 1
    — required, this test breaks otherwise once AC2's `coachProfile2Id` booking exists under the same
    `PARENT_ID`).
  - [x] 2.4 Run the full `RescheduleResourceIT` suite (not just the new test) and confirm every
    pre-existing test — including the ~25 that key off `coachProfileId`'s wide-open fixture — still
    passes; the new `coachProfile2Id` fixture must be additive-only.
- [x] Task 3: Availability-check argument verification (AC: #3)
  - [x] 3.1 Add the request-time and accept-time argument assertions to `RescheduleServiceTest`.
  - [x] 3.2 Add the duplicate-next-week argument assertion to `BookingDuplicationServiceTest`.
  - [x] 3.3 Confirm all three new assertions pass against the current, unmodified production code; if
    any fails, stop and report rather than silently adjusting the assertion.
- [x] Task 4: Ledger hygiene (AC: #4) — apply the three `[PICKED UP]` tags specified above. Already done
  as of story creation (story-review.md Finding 3) — verify the tags are present in `deferred-work.md`,
  no edit needed.

### Review Findings

- [x] [Review][Patch] `RescheduleResourceIT.setUp()`'s two independent `ZonedDateTime.now(Europe/Berlin)`
  calls risk midnight-rollover flakiness [src/test/java/com/softropic/skillars/platform/booking/api/RescheduleResourceIT.java:162-171] — fixed, both computations now derive from a single captured `now()`
- [x] [Review][Defer] `duplicateNextWeek`'s new overlap check has a TOCTOU race with `save()` (same class
  as the already-deferred `acceptReschedule` unlocked-read race; explicitly scoped out by this story)
  [src/main/java/com/softropic/skillars/platform/booking/service/BookingDuplicationService.java:75-88] — deferred, pre-existing
- [x] [Review][Defer] New `duplicateNextWeek_overlapsAnotherBooking_throwsSlotUnavailable` test doesn't
  verify the arguments passed to `findOverlappingBookings`, inconsistent with this story's own AC3 rigor
  [src/test/java/com/softropic/skillars/platform/booking/service/BookingDuplicationServiceTest.java] — deferred, pre-existing

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
- [Source: `src/test/java/com/softropic/skillars/platform/booking/api/RescheduleResourceIT.java:572-602`
  — the existing midnight-crossing `SLOT_OUTSIDE_AVAILABILITY` test
  (`requestReschedule_slotOutsideAvailabilityWindow_returns403WithSlotOutsideAvailabilityKey`), AC2's
  sibling (not replaced); line range corrected by story-review.md Finding 4 from the stale `:384-404`
  citation carried forward from `skillars-deferred-49`'s ledger text — the file has grown since]
- [Source: `src/test/java/com/softropic/skillars/platform/booking/api/BookingRequestResourceIT.java`
  — the narrow-window fixture shape AC2 mirrors, per `skillars-deferred-49` AC1's own citation]
- [Source: `src/test/java/com/softropic/skillars/platform/booking/service/RescheduleServiceTest.java:79-181`
  — the six-plus existing `any(), any(), any()`-stubbed calls, AC3's target]
- [Source: `src/test/java/com/softropic/skillars/platform/booking/service/BookingDuplicationServiceTest.java:78-117`
  — the happy-path test AC3 extends with an argument assertion]
- [Source: `docs/validation-strategy.md` — targeted-test-only validation policy]

## Dev Agent Record

### Agent Model Used

Claude Sonnet 5

### Debug Log References

Implemented in story order: Task 1 (BookingDuplicationService overlap check) → Task 2 (RescheduleResourceIT
ordinary-hours coverage) → Task 3 (argument verification) → Task 4 (already done at story creation, verified
only). Followed red-green: the new `duplicateNextWeek_overlapsAnotherBooking_throwsSlotUnavailable` unit test
was written first and confirmed failing (`mvn -o test -Dtest=BookingDuplicationServiceTest` — assertion
failure "Expecting code to raise a throwable", plus a `UnnecessaryStubbingException` on the happy-path test's
new `findOverlappingBookings` stub) before the production check was added; both cleared once the check
landed (7/7 green).

No deviations from the story's own AC1/AC2/AC3 snippets — all insertion points, query shapes, and fixture
placements matched exactly as specified (story-review.md's pre-dev pass had already verified these against
live code). No blockers encountered.

### Completion Notes List

- AC1: `BookingDuplicationService.duplicateNextWeek` now rejects a computed next-week window that overlaps
  another active booking for the coach, positioned after the availability check and before
  `packSessionService.findActivePackId(...)` exactly as specified, reusing `bookingRepository.findOverlappingBookings`,
  `BookingService.ACTIVE_SLOT_STATUSES_EXCLUDING_REQUESTED`, and the existing `BookingError.SLOT_UNAVAILABLE`
  — no new field, import, or error code. Deliberately no new coach-row locking, per AC1's own scoping. New
  `BookingDuplicationServiceTest` rejection test added; the one existing test reaching past the new check
  (`duplicateNextWeek_completedBooking_createsNewRequestedBookingAdvancedBy7DaysAndCarriesOverPack`) given the
  explicit `List.of()` stub as good practice (story-review.md Finding 2 corrected the original "NPE risk"
  rationale — Mockito's actual default for an unstubbed `List`-returning mock is an empty list, not `null`).
  `duplicateNextWeek_noCreditsAvailable_throws` confirmed unaffected, as story-review.md traced.
- AC2: `RescheduleResourceIT.setUp()` now seeds a narrow, single-day-of-week availability window (`08:00`–`18:00`,
  day computed at test-run time via `ZonedDateTime.now(...).plusDays(2)`) plus one `CONFIRMED` booking for
  `coachProfile2Id`, additive-only — verified by running the full 25-test suite green, not just the new test.
  New test `requestReschedule_ordinaryHoursCoachDoesNotWorkThisDay_returns403WithSlotOutsideAvailabilityKey`
  proposes on `+3 days` (a different day of week than the seeded window, guaranteed distinct from `+2 days`
  since they're consecutive calendar days) at an ordinary daytime hour — a genuine "coach doesn't work this
  day" rejection, distinct from the existing midnight-crossing test (left unmodified). Applied the required
  companion fix from story-review.md Finding 1: `duplicateNextWeek_asOwningCoachWithCompletedBooking_returns204`'s
  booking-count query now also scopes by `coach_id = coachProfileId`, not just `parent_id`, since AC2's new
  fixture booking shares the file's only parent (`PARENT_ID`).
- AC3: Added `verify(bookingService).isSlotWithinAvailabilityWindow(eq(...), eq(...), any())` assertions to
  `RescheduleServiceTest`'s `requestReschedule_parentOwnsBooking_confirmedStatus_createsRequest` and
  `acceptReschedule_coachOwnsBooking_updatesTimesAndStatus`, and to `BookingDuplicationServiceTest`'s
  `duplicateNextWeek_completedBooking_createsNewRequestedBookingAdvancedBy7DaysAndCarriesOverPack` (verifying
  against the test's own already-computed `expectedStart`/derived `expectedEnd`). All three assertions passed
  against the current, unmodified production code on first run — no argument-swap bug found, no production
  change needed. No new imports required (`eq`/`any` already available via each file's existing
  `import static org.mockito.Mockito.*`).
- AC4: Verified only — the three `[PICKED UP by skillars-deferred-50 AC1/AC2/AC3]` tags in `deferred-work.md`
  were already applied at story-creation time (confirmed present, unchanged); no edit made.
- Verification: `mvn -o test -Dtest=BookingDuplicationServiceTest` — 7/7 green (AC1). `mvn -o integration-test
  -Dit.test=RescheduleResourceIT` — 25/25 green (24 pre-existing + 1 new, AC2). `mvn -o test
  -Dtest=RescheduleServiceTest,BookingDuplicationServiceTest` — 25/25 green (AC3). No full `mvn verify` run,
  per `docs/validation-strategy.md`'s targeted-verification policy (CI is the full-suite gate).

### File List

- `src/main/java/com/softropic/skillars/platform/booking/service/BookingDuplicationService.java` (modified —
  new overlap check in `duplicateNextWeek` (AC1))
- `src/test/java/com/softropic/skillars/platform/booking/service/BookingDuplicationServiceTest.java` (modified
  — new rejection test, `List.of()` stub on the existing happy-path test (AC1), new argument-verification
  assertion on the same happy-path test (AC3))
- `src/test/java/com/softropic/skillars/platform/booking/api/RescheduleResourceIT.java` (modified —
  `coachProfile2Id` narrow-window + booking fixture in `setUp()`, one new test, `duplicateNextWeek_asOwningCoachWithCompletedBooking_returns204`'s
  count-query fix (AC2))
- `src/test/java/com/softropic/skillars/platform/booking/service/RescheduleServiceTest.java` (modified — two
  new argument-verification assertions on existing happy-path tests, no new tests (AC3))
- `_bmad-output/implementation-artifacts/deferred-work.md` (already tagged at story creation — no change this
  pass, AC4)

## Change Log

| Date | Change |
|---|---|
| 2026-08-21 | Story created via story-creation process, bundling three items filed by `skillars-deferred-49`'s own code review (`deferred-work.md`'s "code review of skillars-deferred-49-..." section) — per explicit instruction not to create another small story. All three re-verified against live code at creation time rather than trusted from ledger text: `BookingDuplicationService.duplicateNextWeek` still has no overlap check (`BookingDuplicationService.java:41-101` read directly); `RescheduleResourceIT`'s `SLOT_OUTSIDE_AVAILABILITY` coverage is still midnight-crossing-only (`:384-404`), and `coachProfile2Id` is confirmed present in the fixture with no availability window or booking of its own (`:69-175`); `RescheduleServiceTest`/`BookingDuplicationServiceTest` still stub `isSlotWithinAvailabilityWindow(any(), any(), any())` everywhere, confirmed by grep. Three other items from the same review section deliberately not picked up: the validation-logic-duplication DRY nit (matches this project's own accepted anti-abstraction convention, `skillars-deferred-48` precedent), the `acceptReschedule` unlocked-read TOCTOU race (needs a `CoachProfileService` locking-strategy decision, out of a bundled small-fix story's scope), and `duplicateNextWeek`'s DST-shift-of-duplicated-time quirk (pre-existing behavior, no proposed fix in the ledger item itself). |
| 2026-08-21 | `story-review.md` applied: 4 findings, all addressed before dev started. Finding 1/High: AC2's new `coachProfile2Id` booking (under the file's only parent, `PARENT_ID`) collides with `duplicateNextWeek_asOwningCoachWithCompletedBooking_returns204`'s `parent_id`-only (no `coach_id` filter) booking-count assertion, breaking it exactly the way an analogous fixture collision broke the same test in `skillars-deferred-49`'s own review; fixed by adding a required companion fix (scope that query's count by `coach_id` too) directly to AC2/Task 2, not left for verification to merely catch. Finding 2/Medium: AC1's stated reason for the `List.of()` stub — an unstubbed Mockito `List`-returning mock defaulting to `null` and NPEing — is factually wrong for this project's resolved Mockito version (default is an empty list, execution silently continues instead); corrected the rationale in AC1/Task 1.2 while keeping the stub itself as good practice. Finding 3/Low: AC4/Task 4's three ledger tags were already applied at story-creation time (same commit); Task 4 marked done, with a note that no further edit is needed. Finding 4/Low: the References section's midnight-crossing-test line citation was stale (`:384-404` carried forward from `skillars-deferred-49`'s ledger text); corrected to `:572-602`. No other issues found — story-review.md's "Verified accurate" section independently confirmed every other AC1–AC3 file/line citation, insertion point, and cross-AC independence claim against the live repo. |
| 2026-08-21 | Dev implementation complete (AC1–AC4). AC1: `BookingDuplicationService.duplicateNextWeek` gained the overlap/double-booking check exactly as specified; new unit test written first and confirmed failing (red) before the check was added (green), matching this repo's red-green-refactor convention. AC2: `RescheduleResourceIT` gained the `coachProfile2Id` narrow-window fixture, the new ordinary-hours rejection test, and the required `duplicateNextWeek_asOwningCoachWithCompletedBooking_returns204` count-query fix from story-review.md Finding 1 — full 25-test suite green. AC3: three argument-verification assertions added across `RescheduleServiceTest`/`BookingDuplicationServiceTest`, all passed against unmodified production code on first run (no argument-swap bug found). AC4: verified already-applied, no change. All targeted backend unit/integration tests green (7/7, 25/25 IT, 25/25 unit). Status → review. |
| 2026-08-21 | Code review complete (Blind Hunter + Edge Case Hunter + Acceptance Auditor). Acceptance Auditor found 0 AC violations across AC1–AC4 — AC1's code block confirmed byte-for-byte match to spec, AC2's day-offset logic independently traced against `BookingService.isSlotWithinAvailabilityWindow`'s actual day-of-week matching, AC3's assertions confirmed to check the genuinely-correct variables (not shadowed/misleading names), AC4's ledger tags confirmed present and untouched. 15 raw findings from Blind Hunter + Edge Case Hunter, 12 dismissed as false positives or matches to already-accepted convention — most notably, Blind Hunter's `findOverlappingBookings(..., null)` null-handling concern was verified false by reading `BookingRepository.java:27`'s JPQL, which explicitly guards `(:excludeBookingId IS NULL OR ...)`. 1 patch applied: `RescheduleResourceIT.setUp()`'s two independent `ZonedDateTime.now(Europe/Berlin)` calls (window day-of-week vs. booking start time) risked midnight-rollover flakiness — fixed by capturing `now()` once and deriving both from it; `mvn -o compile test-compile` confirmed green post-patch. 2 deferred to `deferred-work.md`: `duplicateNextWeek`'s new overlap check has a TOCTOU race with `save()` (explicitly scoped out by this story itself, same class as `acceptReschedule`'s already-deferred unlocked-read race), and the new overlap-rejection unit test doesn't verify `findOverlappingBookings`'s call arguments (optional polish, not required by AC1). Status → done. |
