# Story Deferred-8: Test Coverage Gaps

Status: backlog

## Story

As a platform engineer,
I want critical test gaps closed,
so that regressions in booking sort order, coach target logic, and year-boundary SLU calculations are caught automatically rather than discovered in production.

## Acceptance Criteria

1. **Given** `SluDashboardServiceTest.java` computes `prevWeek` and `prevPrevWeek` for snapshot IDs
   **When** the test runs in ISO week 1 (early January)
   **Then** `prevWeek` correctly uses the prior ISO year's week 52 (or 53), not `curWeek - 1` of the current year
   **Fix**: compute `prevWeek` and `prevPrevWeek` using `ZonedDateTime.minusWeeks(n)` + `WeekFields.ISO.weekBasedYear()` rather than `curWeek - 1` arithmetic

2. **Given** a player has SLU targets set by two different coaches
   **When** `NeglectedSkillDetectionService` evaluates whether a skill is neglected
   **Then** the detection uses the HIGHEST target across all coaches (AC 7 of Story 5.2)
   **And** an IT test verifies this by inserting targets from two coaches with different values and asserting the flag reflects the higher target — not just a unit test with a pre-baked MAX stub

3. **Given** `BookingService.declineBooking()` is called by a coach who does not own the booking
   **When** the wrong coach calls `DELETE /api/booking/{bookingId}/decline`
   **Then** the response is `403` (same guard as `acceptBooking`)
   **And** an IT test `declineBooking_wrongCoach_returns403()` exists alongside the existing `acceptBooking_wrongCoach_returns403()` test

4. **Given** `BookingService.getParentBookings()` returns bookings sorted by `requestedStartTime`
   **When** the IT test `getParentBookings_returnsListSortedByStartTime()` runs
   **Then** the test asserts the sort order on 2+ bookings with different `requestedStartTime` values using `.extracting("requestedStartTime").isSorted()`

5. **Given** `BookingService.getCoachBookingRequests()` returns a list with booking rows
   **When** the IT test `getCoachBookingRequests()` runs
   **Then** the response includes `parentName` and the test asserts `response.getBody().get(0).get("parentName")` is non-null (AC 8 of Story 3.3)

## Tasks / Subtasks

- [ ] **Task 1 — Fix year-boundary week arithmetic in `SluDashboardServiceTest`** (AC: 1)
  - [ ] Read `SluDashboardServiceTest.java` around line 690-692 — find the `prevWeek`/`prevPrevWeek` computation
  - [ ] Current (broken for ISO week 1):
    ```java
    int prevWeek = curWeek > 1 ? curWeek - 1 : 52;  // assigns wrong year for week 1
    int prevPrevWeek = curWeek > 2 ? curWeek - 2 : (curWeek == 1 ? 51 : 52);
    ```
  - [ ] Fix using the same pattern as the existing narrative test:
    ```java
    ZonedDateTime now = ZonedDateTime.now(ZoneOffset.UTC);
    ZonedDateTime prevWeekDt = now.minusWeeks(1);
    ZonedDateTime prevPrevWeekDt = now.minusWeeks(2);

    int curYear = (int) now.getLong(WeekFields.ISO.weekBasedYear());
    int curWeek = (int) now.getLong(WeekFields.ISO.weekOfWeekBasedYear());

    int prevYear = (int) prevWeekDt.getLong(WeekFields.ISO.weekBasedYear());
    int prevWeek = (int) prevWeekDt.getLong(WeekFields.ISO.weekOfWeekBasedYear());

    int prevPrevYear = (int) prevPrevWeekDt.getLong(WeekFields.ISO.weekBasedYear());
    int prevPrevWeek = (int) prevPrevWeekDt.getLong(WeekFields.ISO.weekOfWeekBasedYear());
    ```
  - [ ] Update the snapshot row seeding to use `prevYear`/`prevPrevYear` as well as the week numbers — if snapshot IDs are keyed as `year * 100 + week`, both year and week must be correct
  - [ ] Verify `WeekFields` is imported from `java.time.temporal.WeekFields`

- [ ] **Task 2 — AC7 IT: highest coach target governs neglected-skill detection** (AC: 2)
  - [ ] Read `NeglectedSkillDetectionService.java` and `SluDashboardServiceIT.java` (or wherever IT tests for Story 5.2 are)
  - [ ] Add `multipleCoachesHighestTargetGovernsDetection_IT()` to the IT class (TSID range `9360_xxx` or use existing IT seed range):
    ```
    Setup:
    - Player P, skill S
    - Coach C1: weeklyTargetSlu = 10 for skill S, player P
    - Coach C2: weeklyTargetSlu = 20 for skill S, player P
    - Player P: actual SLU this week = 5 (below both targets)
    - Run neglected-skill detection
    Expected: player P / skill S flagged as neglected (actual 5 < highest target 20)

    Variation:
    - Player P: actual SLU = 15 (above C1's target, below C2's)
    - Run detection
    Expected: still flagged (15 < 20)

    Variation:
    - Player P: actual SLU = 25 (above both targets)
    - Run detection
    Expected: NOT flagged
    ```
  - [ ] This test specifically hits the JPQL `SELECT ... MAX(t.weeklyTargetSlu) ... GROUP BY ...` query to verify it aggregates across coaches — the unit test with a pre-baked stub cannot catch a `WHERE coach_id = :coachId` bug in the query

- [ ] **Task 3 — Add `declineBooking_wrongCoach_returns403()` IT** (AC: 3)
  - [ ] Read `BookingRequestResourceIT.java` — find `acceptBooking_wrongCoach_returns403()` and the test setup pattern
  - [ ] Add adjacent test:
    ```java
    @Test
    void declineBooking_wrongCoach_returns403() {
        // Use the same booking seed as acceptBooking_wrongCoach_returns403()
        // but call DELETE/POST /api/booking/{bookingId}/decline
        // Expect: 403
    }
    ```
  - [ ] Confirm the decline endpoint path from `BookingResource.java` — it may be `DELETE /api/booking/{bookingId}` or `POST /api/booking/{bookingId}/decline` depending on how Story 3.3 implemented it

- [ ] **Task 4 — Assert sort order in `getParentBookings` IT** (AC: 4)
  - [ ] Read `BookingRequestResourceIT.java` — find `getParentBookings_returnsListSortedByStartTime()` around line 480
  - [ ] Current: only asserts HTTP 200 + non-null body
  - [ ] Updated: seed 2 bookings with different `requestedStartTime` (one further in the future), then:
    ```java
    List<Map<String, Object>> bookings = response.getBody();
    assertThat(bookings).hasSizeGreaterThanOrEqualTo(2);
    List<String> startTimes = bookings.stream()
        .map(b -> (String) b.get("requestedStartTime"))
        .collect(toList());
    assertThat(startTimes).isSortedAccordingTo(String::compareTo);
    // ISO-8601 strings are lexicographically sortable
    ```
  - [ ] If the current test only seeds 1 booking, add a second with an earlier `requestedStartTime` to have 2 rows in both possible orders before the sort is applied

- [ ] **Task 5 — Assert `parentName` in `getCoachBookingRequests` IT** (AC: 5)
  - [ ] Read `BookingRequestResourceIT.java` around line 524 — find the `getCoachBookingRequests` test
  - [ ] Add assertion:
    ```java
    assertThat(response.getBody().get(0).get("parentName"))
        .as("parentName (AC 8 of Story 3.3)")
        .isNotNull()
        .isNotBlank();
    ```
  - [ ] Ensure the parent user seeded in the test has a real `firstName` + `lastName` (not placeholder values like "Deleted User") so `parentName` is populated

## Dev Notes

### ISO week edge case — why it matters in CI

The year-boundary bug in `SluDashboardServiceTest` only manifests when the test is run in ISO week 1 (first week of January that contains Thursday). In CI pipelines running on Jan 3–7, the test will fail. The fix is always valid regardless of current week; there is no trade-off.

### AC7 IT placement

The IT test for Task 2 should go in the same IT class that tests `NeglectedSkillDetectionService` — likely `NeglectedSkillDetectionServiceIT.java` or `SluDashboardResourceIT.java`. Read both to determine which has the closer data setup pattern (the test needs to insert `slu_targets` rows for two coaches and then run the detection job).

### `WeekFields.ISO` vs `ChronoField.ALIGNED_WEEK_OF_YEAR`

Use `WeekFields.ISO.weekOfWeekBasedYear()` not `ChronoField.ALIGNED_WEEK_OF_YEAR` — only the ISO week fields correctly handle the year boundary (days in late December that belong to ISO week 1 of the next year).

### `isSortedAccordingTo` vs `isSorted`

AssertJ's `isSorted()` uses natural ordering. For `String` ISO-8601 timestamps, natural (lexicographic) ordering is equivalent to chronological order. If `requestedStartTime` is serialized as `Instant` or an epoch millisecond in the response, use a custom comparator.

### Decline endpoint path verification

Before writing Task 3, grep the actual decline endpoint:
`grep -r "decline" src/main/java --include="*.java" -l` then read the matching resource file.

### References — Files to Read Before Implementing

- `SluDashboardServiceTest.java:690-692` — exact broken computation
- `NeglectedSkillDetectionService.java` — JPQL query for MAX target
- `BookingRequestResourceIT.java:480,524` — existing sort and parentName tests
- `BookingResource.java` — decline endpoint path (Task 3)
- `SluTargetRepository.java` — `findMaxWeeklyTargetByPlayerAndSkill` or equivalent JPQL
- `NeglectedSkillDetectionServiceIT.java` (or the containing IT class) — test setup pattern

## Dev Agent Record

### Agent Model Used

### Debug Log References

### Completion Notes List

### File List

**Modified Files:**
- `src/test/java/com/softropic/skillars/platform/development/service/SluDashboardServiceTest.java`
- `src/test/java/com/softropic/skillars/platform/booking/api/BookingRequestResourceIT.java`
- `src/test/java/com/softropic/skillars/platform/development/service/NeglectedSkillDetectionServiceIT.java` *(or equivalent)*
