# Story Review: skillars-deferred-50 (Duplicate-Next-Week Overlap Guard, Reschedule Ordinary-Hours IT Coverage & Availability-Check Argument Verification)

Reviewed against the live repo at commit `4aeabc1` (branch
`duplicate-next-week-overlap-guard-reschedule-ordinary-hours-coverage-and-availability-test-hardening`).
Every file/line citation in the story was independently re-checked against the current source,
not trusted from the story text. 4 findings below; everything else in the story checks out (see
"Verified accurate" at the end) — no changes are recommended beyond addressing these.

---

## Finding 1 (High) — AC2's fixture addition breaks an existing IT test's booking-count assertion; the story's "additive only" claim does not hold

**AC2** directs adding a new `CONFIRMED` booking for `coachProfile2Id` in `setUp()`, "mirroring the
shape `insertConfirmedBooking(bookingId)` already creates for `coachProfileId`" — which means the
new booking's `parent_id` will be `PARENT_ID`, the same parent every other fixture booking in this
file uses (`insertConfirmedBooking` at `RescheduleResourceIT.java:942-953` hardcodes `PARENT_ID`;
there's no parameterized coach-only variant, so a dev following the "mirror the shape" instruction
will naturally reuse `PARENT_ID`, and there is no other parent user this test file could plausibly
use for it — `requestReschedule` requires the caller's `parentUserId` to equal the booking's
`parent_id`, and the new AC2 test authenticates as `PARENT_EMAIL`).

`setUp()` runs before **every** test method — confirmed via `DatabaseResetTestExecutionListener`,
which truncates the database before each test method runs, so there is no cross-test leakage to
rely on for isolation; every test gets a fresh copy of whatever `setUp()` inserts.

`duplicateNextWeek_asOwningCoachWithCompletedBooking_returns204`
(`RescheduleResourceIT.java:720-736`) asserts:

```java
Integer newBookingCount = jdbcTemplate.queryForObject(
    "SELECT COUNT(*) FROM booking.bookings WHERE parent_id = ? AND id != ?",
    Integer.class, PARENT_ID, bookingId);
assertThat(newBookingCount).isEqualTo(1);
```

This query is scoped only by `parent_id` and excludes only the one `bookingId` field — it has no
`coach_id` filter. Once AC2's `coachProfile2Id` booking exists (parent `PARENT_ID`, a different
`id` than `bookingId`), the baseline count before `duplicateNextWeek` even runs is already 1, and
after the call creates its own new booking the count becomes 2 — the assertion `isEqualTo(1)`
fails.

This is not a hypothetical: `skillars-deferred-49`'s own code review already hit this exact test
with an analogous fixture-collision bug (its change log, `deferred-49` story file: "Finding 1/High:
AC2's 'no IT change required' claim was wrong — `duplicateNextWeek_asOwningCoachWithCompletedBooking_returns204`
... breaks regardless of AC1's fixture, because `setBookingStatus` leaves the booking with a ~4-day
span; fixed by specifying a required fix to that helper"). This test is a known fragile point for
exactly this class of fixture change, and this story's AC2 doesn't mention it at all.

**AC2's text asserts "this is additive only" and Task 2.3 asks the dev to "confirm every
pre-existing test ... still passes; the new `coachProfile2Id` fixture must be additive-only" — that
verification step will catch this at implementation time, but the AC itself should have flagged
the required companion fix up front** (e.g., scope the count query to `coach_id = ?`, or give the
new `coachProfile2Id` booking a distinct parent, or exclude it explicitly), the same way AC2's own
citation of `setBookingStatus`'s prior fix shows this project's convention of calling out such
fixture-collision fixes explicitly rather than leaving them to "verification will catch it."

**Recommendation:** amend AC2 (or flag it as a Dev Note) to either scope
`duplicateNextWeek_asOwningCoachWithCompletedBooking_returns204`'s count query to
`coach_id = coachProfileId`, or otherwise account for the new fixture booking, before Task 2 is
implemented.

---

## Finding 2 (Medium) — AC1's stated reason for the `List.of()` stub fix is factually wrong: Mockito does not default unstubbed `List`-returning mocks to `null`

AC1's unit-test guidance says:

> an unstubbed Mockito `@Mock` method returning `List` defaults to `null`, which would NPE at
> `overlapping.isEmpty()` rather than cleanly failing the test with a clear assertion

This is incorrect. Mockito's default answer for `@Mock`-created mocks (`RETURNS_DEFAULTS`, backed
by `ReturnsEmptyValues`) returns an **empty collection** for `Collection`/`List`/`Set`/`Map` return
types, not `null`. Verified empirically against this project's actual resolved Mockito version
(`mockito-core:5.17.0`, confirmed via `mvn dependency:tree`): a plain `@Mock` interface method
returning `List<String>`, called unstubbed, returns `[]`, and `result == null` is `false`.

Practical consequence: an unstubbed `bookingRepository.findOverlappingBookings(...)` call would
return an empty list, so `overlapping.isEmpty()` evaluates to `true` and execution simply
**continues past the new check silently** — no NPE, no failure. Tracing all 5 pre-existing
`BookingDuplicationServiceTest` tests against this confirms none of them are actually at NPE risk;
`duplicateNextWeek_noCreditsAvailable_throws` (the one other test that reaches past where the new
check will sit) passes regardless, since it doesn't depend on the overlap result — `packSessionService.findActivePackId`
throws immediately after.

This doesn't change the recommended action — adding the explicit
`.thenReturn(List.of())` stub to the happy-path test is still good practice (makes the test
self-documenting and doesn't rely on implicit Mockito defaults) — but the story's stated
justification is wrong, and the follow-on instruction to "check every other existing test in the
file for the same unstubbed-default risk" is chasing a risk that doesn't exist in this codebase's
actual Mockito version. Worth correcting the rationale so a future reader doesn't take the wrong
lesson about Mockito's default behavior.

---

## Finding 3 (Low/Informational) — AC4's ledger tags are already applied; Task 4 has nothing left to do

AC4 and Task 4 direct the dev to add `[PICKED UP by skillars-deferred-50 AC1/AC2/AC3]` tags to the
three ledger items in `deferred-work.md`. Checking the live file: all three tags are **already
present** (`deferred-work.md:1631-1633`), applied in the same commit that created this story file
(`4aeabc1`, per `git blame`). The other three items in that ledger section correctly remain
untagged, matching AC4's "leave untouched" instruction.

Not a defect — the end state AC4 asks for already exists — but worth flagging so whoever executes
Task 4 doesn't spend time second-guessing an apparently-already-done step, and so the story's
completion checklist isn't misread as incomplete when this one item requires no code change.

---

## Finding 4 (Low) — Stale line-number citation for the existing midnight-crossing test

The story's References section cites the existing `SLOT_OUTSIDE_AVAILABILITY` midnight-crossing
test at `RescheduleResourceIT.java:384-404`. In the live file that test
(`requestReschedule_slotOutsideAvailabilityWindow_returns403WithSlotOutsideAvailabilityKey`) is
actually at lines **572–602** — the file has grown (more tests added) since that citation was
first written into the ledger by `skillars-deferred-49`'s own review, and this story's References
section carried the stale line numbers forward without re-verifying them, despite the story's own
"Why this story exists" section stating all citations were "re-verified against the live repo
during this story's creation, not just trusted from the ledger text." The test name itself is
correct and unambiguous, so this doesn't block implementation, but the specific line range is
wrong.

---

## Verified accurate (no issues found)

- AC1's insertion point (`BookingDuplicationService.java`, after the availability check ending at
  `:73`, before `packSessionService.findActivePackId(...)` at `:75`) — exact match.
- `bookingRepository`, `BookingService.ACTIVE_SLOT_STATUSES_EXCLUDING_REQUESTED` (package-private,
  same package), and `BookingError.SLOT_UNAVAILABLE` are all already in scope / already defined as
  claimed; `findOverlappingBookings`'s `null`-excludeBookingId handling is explicit in its `@Query`
  (`BookingRepository.java:27`), confirming AC1's `null` argument choice is safe.
- `RescheduleService.acceptReschedule`'s overlap check and coach-row locking citations
  (`RescheduleService.java:208-215`, `:238-247`) are accurate.
- AC2's fixture-insertion boundaries (`coachProfile2Id` insert at `:143-152`, pack-purchase block
  at `:154-170`) are pinpoint-accurate against the live file.
- AC2's narrow-window fixture shape matches `BookingRequestResourceIT`'s existing precedent
  (`day_of_week`/`start_time`/`end_time`/`canonical_timezone` columns, dynamic day-of-week
  computation via `ZonedDateTime`), including the `TIME` column type in the `V26` migration
  accepting the `'08:00:00'`-style literals.
- AC3's target tests (`requestReschedule_parentOwnsBooking_confirmedStatus_createsRequest`,
  `acceptReschedule_coachOwnsBooking_updatesTimesAndStatus`,
  `duplicateNextWeek_completedBooking_createsNewRequestedBookingAdvancedBy7DaysAndCarriesOverPack`)
  all exist as named, at the cited (or near-cited) line ranges, and each has the local variables
  needed (`proposedStart`/`proposedEnd`/`expectedStart`) to write the described argument
  assertions without further plumbing. `eq()` is available via the existing
  `import static org.mockito.Mockito.*` (Mockito extends ArgumentMatchers), so no new import is
  needed.
- The `maven-failsafe-plugin` IT-execution gotcha and `docs/validation-strategy.md` targeted-test
  guidance are both accurate as stated.
- No cross-AC production-code coupling: AC1 (BookingDuplicationService) and AC2
  (RescheduleService.requestReschedule, different method/class) don't interact, and AC1's new
  overlap check is coach-scoped, so AC2's new `coachProfile2Id` booking cannot trigger it.
