# Story Review: Deferred-49 — Reschedule & Duplicate-Next-Week Current-Availability-Window Enforcement

Reviewed: `_bmad-output/implementation-artifacts/skillars-deferred-49-reschedule-and-duplicate-current-availability-window-enforcement.md`

Method: every factual claim in the story (line numbers, "no dedicated IT" claims, the fixture-breakage
mechanism AC1 already flags, and the frontend/i18n "already exists" claims) was re-verified against the
current code on this branch. Read in full: `RescheduleService.java`, `BookingDuplicationService.java`,
`BookingService.isSlotWithinAvailabilityWindow` (`:827-854`), `RescheduleServiceTest.java`,
`BookingDuplicationServiceTest.java`, `RescheduleResourceIT.java` (all `@Test` methods, `setUp`, and
helpers), `ParentBookingsPage.vue`'s `submitReschedule()`, `CoachCommandCenterPage.vue`'s
`handleRepeatNextWeek()`, `BookingError.java`, and the three i18n bundles' `slotOutsideAvailability` keys.
Specifically checked and ruled out as non-issues:

- **AC1/AC2's cited line numbers and "before/after" insertion points.** All match the current file contents
  exactly (`RescheduleService.java:97-113`, `BookingDuplicationService.java:53-57`).
- **AC1's fixture-breakage claim itself.** Confirmed real: `RescheduleResourceIT.setUp()` seeds no
  `marketplace.coach_availability_windows` row at all, and its proposal times are bare
  `Instant.now().plus(N, DAYS)` with no hour anchoring — the story's own required 7-day, wide-open-window
  fixture fix is necessary and correctly scoped for *that* breakage.
- **AC3's i18n and frontend claims.** `booking.errors.slotOutsideAvailability` is present verbatim in all
  three locale bundles at the cited lines; `ParentBookingsPage.vue`'s `submitReschedule()` catch chain and
  `CoachCommandCenterPage.vue`'s bare `handleRepeatNextWeek()` catch both match the story's description
  exactly — no frontend gap there.
- **`Booking.coachId` / `CoachAvailabilityWindow.coach_id` semantics.** Both key off `CoachProfile.id`, the
  same space `BookingService.createBookingRequest` already uses — AC1/AC2's `findByCoachId(booking.getCoachId())`
  / `findByCoachId(coach.getId())` calls are the correct id.
- **Field-append / constructor-order guidance.** Correct for both services' `@RequiredArgsConstructor` and
  both existing test files' positional constructor calls.

Four issues survived verification — two are concrete test breakages the story's own scoping text asserts
won't happen, one is a test-authoring approach in the story that cannot achieve its own stated goal, and one
is an unaddressed corner case in the design decision itself.

## Findings

### 1. AC2's "no IT change required" claim is wrong — it will break an existing IT in the very file AC1 already touches

**Severity: High (confirmed) — a currently-green integration test starts failing the moment AC2 ships,
regardless of AC1's fixture fix.**

**Where:** `RescheduleResourceIT.java:672-688`, `duplicateNextWeek_asOwningCoachWithCompletedBooking_returns204`.

AC2 states: *"No new/changed integration test required. `BookingDuplicationService` has no dedicated
`*ResourceIT` of its own found in the repo... unit coverage in `BookingDuplicationServiceTest` is
sufficient."* This is false: `RescheduleResourceIT.java` — the exact file AC1 is already modifying —
contains a full HTTP-level happy-path test for `duplicate-next-week` at `:672-688`.

Worse, even applying AC1's mandated fixture (a 7-day, `00:00:00`–`23:59:59` wide-open window per day of
week) does not save this test. The test calls `setBookingStatus("COMPLETED")` (`:945-951`), which updates
only `status` and `requested_start_time` (to `Instant.now().minus(2, DAYS)`) and leaves `requested_end_time`
untouched at its `insertConfirmedBooking`-seeded value (`Instant.now().plus(2, DAYS).plus(1, HOURS)`). That
gives the booking used by this test an actual duration of ~4 days 1 hour. `duplicateNextWeek` adds 7 days to
both bounds, preserving that same ~4-day span. `isSlotWithinAvailabilityWindow` anchors `windowEnd` to the
session's *start* calendar date (`:844`, `w.getEndTime().atDate(startZdt.toLocalDate())`), so no single-day
window — wide-open or not — can ever satisfy a multi-day span. Once AC2's check lands, this test will start
returning 403/`booking.slotOutsideAvailability` instead of 204.

This needs either a dedicated fixture note (parallel to AC1's) covering this test, or a fix to
`setBookingStatus` to also advance `requested_end_time`, called out explicitly — AC2's task list (2.4) as
written will lead the dev agent to skip IT changes entirely on the "no dedicated IT exists" premise and never
notice this test.

### 2. AC1's own existing unit tests break, and the story's test-guidance never says so

**Severity: High (confirmed) — two currently-green unit tests fail once AC1's check is added, with no
guidance to fix them.**

**Where:** `RescheduleServiceTest.java:87-103` (`requestReschedule_parentOwnsBooking_confirmedStatus_createsRequest`)
and `:113-140` (`requestReschedule_legacyThreeHourBooking_movesAtItsOwnLength`).

Both tests reach past the duration-match check into `rescheduleRepo.save(...)` without stubbing
`bookingService.isSlotWithinAvailabilityWindow(...)`. Since `bookingService` is a Mockito `@Mock`, an
unstubbed call to that method returns Mockito's default `boolean` value, `false`. Once AC1 inserts the
availability check between the duration check and the pending-request check, both tests will start hitting
`OperationNotAllowedException(SLOT_OUTSIDE_AVAILABILITY)` instead of reaching `save(...)`, and both will fail.

AC2's parallel test-guidance explicitly anticipates this for `BookingDuplicationServiceTest` ("confirm the
file's existing happy-path test(s) still pass once the mock is stubbed to return `true`"). AC1's guidance for
`RescheduleServiceTest` has no equivalent sentence — it only describes the two *new* tests to add. A dev
agent following AC1 literally will not think to add
`when(bookingService.isSlotWithinAvailabilityWindow(any(), any(), any())).thenReturn(true)` to these two
pre-existing tests' setup, or thread it into `setUp()`.

### 3. AC1's suggested new-IT-test approach cannot produce the rejection it's meant to test, given AC1's own mandated fixture

**Severity: Medium (confirmed) — as written, the guidance leads to a test that most likely won't fail the
way its name promises, undermining regression coverage for the very AC it verifies.**

**Where:** AC1's bullet on `requestReschedule_slotOutsideAvailabilityWindow_returns403WithSlotOutsideAvailabilityKey`,
and the fixture it depends on (`coach_availability_windows`, all 7 `day_of_week` values, `00:00:00`–`23:59:59`).

AC1 tells the dev to mirror `BookingRequestResourceIT.createBookingRequest_slotOutsideAvailabilityWindow_returns422`'s
convention: pick a time "far enough in the future" (`Instant.now().plusSeconds(21 * 24 * 3600)`-style) to
land outside the window. That convention works in `BookingRequestResourceIT` because its fixture is a single
*narrow* window (next-day, 08:00–18:00) — a far-future date lands on an arbitrary day of week with no window
at all.

AC1's own mandated `RescheduleResourceIT` fixture is categorically different: it seeds a window for **every**
day of week, each covering **all** of `00:00:00`–`23:59:59`. Given `isSlotWithinAvailabilityWindow`'s
day-of-week + intra-day-boundary matching (`:827-854`), there is no "far enough in the future" plain-hours
proposal that lands outside this fixture — every calendar day has full coverage. The only way to fail the
check under this fixture is a session whose `endZdt` crosses past `23:59:59` on the *start's* calendar date
(a proposal starting late at night, e.g. 23:30, running past midnight) — since `windowEnd` is anchored to the
start date only. A "3 weeks out, 1 hour long, arbitrary hour" proposal (the pattern AC1 explicitly suggests)
will, in the overwhelming majority of cases, land fully inside some day's window and return 204 instead of
403 — the opposite of what the new test is supposed to assert, and the kind of thing that would pass CI while
silently testing nothing.

### 4. Design gap: `acceptReschedule` never re-validates availability at accept time, though it already re-validates other proposal-time facts there

**Severity: Low/Open question — not a broken test, but a real hole in "current availability" enforcement as
the story frames its own goal.**

**Where:** `RescheduleService.acceptReschedule` (`:137-231`).

The story's stated semantics are "a reschedule... must fit the coach's CURRENT availability, re-validated at
request time" — but `acceptReschedule` is the method that actually finalizes the booking's
`requested_start_time`/`requested_end_time` (`:217-218`), and it already demonstrates the codebase's own
precedent for re-checking proposal-time facts against present-tense state at accept time: it re-checks
`proposedStartTime().isAfter(Instant.now())` again (`:161-164`, identical to the check already made in
`requestReschedule`), takes a pessimistic coach lock specifically to catch a suspension that lands mid-flight
(`:193-204`), and re-checks slot overlap against the *proposed* window (`:206-215`). Availability windows are
conspicuously absent from that re-check list.

A reschedule request can sit `PENDING` for an arbitrary length of time. If the coach narrows their
`coach_availability_windows` after a parent's proposal but before the coach accepts it, `acceptReschedule`
will still happily finalize the booking into a slot outside the coach's now-current availability — precisely
the scenario this story's own "Why this story exists" section describes as the problem, just reached through
the accept door instead of the request door. This may be an intentional scope boundary ("re-validated at
request time" read literally), but the story doesn't explicitly rule accept-time re-validation in or out, and
the accept-time precedent already set for `START_TIME_IN_PAST`/`SLOT_UNAVAILABLE`/`COACH_UNAVAILABLE` argues
for treating it the same way. Worth an explicit decision before implementation, not left implicit.
