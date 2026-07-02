# Story Deferred-8: Test Coverage Gaps

Status: done

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

- [x] **Task 1 — Fix year-boundary week arithmetic in `SluDashboardServiceTest`** (AC: 1)
  - [x] Read `SluDashboardServiceTest.java` — found the `prevWeek`/`prevPrevWeek` computation in `getWeeklyExposure_withFewerThanRequestedWeeks_returnsAvailableWeeks()` (naive `curWeek - 1` arithmetic, always paired with `curYear`)
  - [x] Confirmed the bug via a standalone simulation (jshell) before editing: for `now = 2027-01-04` (ISO week 1, 2027) the old logic computed `prevWeek=52, prevPrevWeek=51` under `curYear=2027`, but the true prior weeks are `2026-W53` and `2026-W52` — both the week numbers and the year were wrong
  - [x] Fixed using `ZonedDateTime.minusWeeks(n)` + `IsoFields.WEEK_BASED_YEAR` / `IsoFields.WEEK_OF_WEEK_BASED_YEAR` (the codebase's existing convention — matches `NeglectedSkillDetectionService`'s pattern; used `IsoFields` instead of `WeekFields` since the test file already imported `IsoFields` and other production code uses that idiom)
  - [x] Updated the snapshot row seeding to use `prevYear`/`prevPrevYear` alongside the week numbers
  - [x] `IsoFields` (already imported) — no new import needed

- [x] **Task 2 — AC7 IT: highest coach target governs neglected-skill detection** (AC: 2)
  - [x] Read `NeglectedSkillDetectionService.java`, `NeglectedSkillProcessor.java`, `SluTargetRepository.java` — no existing `@SpringBootTest` IT class covered this path (only Mockito unit tests existed)
  - [x] Created `NeglectedSkillDetectionServiceIT.java` (new file, `development.service` package, TSID `9360000001`) with `multipleCoachesHighestTargetGovernsDetection_IT()`:
    - Coach1 target=10, Coach2 target=20 (threshold=0.30 → lowerBound = maxTarget × 0.70)
    - actual=5 (< 7.0 and < 14.0) → flagged (baseline)
    - actual=10 — **discriminating case**: above coach1's lower bound (7.0) but below the highest-target lower bound (14.0); this is the case that would incorrectly resolve the flag if the JPQL regressed to scope `MAX()` by a single `coach_id` → correctly stays flagged
    - actual=20 (≥ 14.0) → resolved
  - [x] Test hits the real `SluTargetRepository.findMaxTargetPerSkill` JPQL (`MAX(t.weeklyTargetSlu) ... WHERE t.id.playerId = :playerId GROUP BY t.id.skillCode`) via `NeglectedSkillProcessor.processPlayer()` against Testcontainers Postgres — not a stub

- [x] **Task 3 — Add `declineBooking_wrongCoach_returns403()` IT** (AC: 3)
  - [x] Added adjacent to `acceptBooking_wrongCoach_returns403()` in `BookingRequestResourceIT.java`, mirroring its setup; calls `PUT /api/bookings/requests/{id}/decline` (confirmed actual endpoint path from `BookingResource.java` — differs from the story's guessed `DELETE`/`POST` paths) and asserts `403`

- [x] **Task 4 — Assert sort order in `getParentBookings` IT** (AC: 4)
  - [x] Updated `getParentBookings_returnsListSortedByStartTime()`: creates one booking via the API and a second (further in the future) via direct SQL insert, then asserts `requestedStartTime` values are `isSortedAccordingTo(String::compareTo)` (ISO-8601 strings, confirmed lexicographically sortable — `spring.jackson.serialization.WRITE_DATES_AS_TIMESTAMPS: false` in `application.yaml`)
  - [x] **Found and fixed a real production bug while implementing this task**: `BookingService.getParentBookings()` threw `NullPointerException` for any parent with at least one non-batched booking, because `batchSizeMap` is `Map.of()` when no bookings are batched, and `Map.of().get(null)` throws NPE on the JDK's immutable maps (`b.getBatchId()` is `null` for non-batched bookings). This was invisible before because the only prior test called the endpoint with zero bookings. Fixed in `BookingService.java` by only looking up `batchSizeMap` when `b.getBatchId() != null`.

- [x] **Task 5 — Assert `parentName` in `getCoachBookingRequests` IT** (AC: 5)
  - [x] Added `parentName` assertion (non-null, non-empty) to `getCoachBookingRequests_returnsOnlyRequestedBookingsForThisCoach()`; the seeded parent already has real `firstName`/`lastName` values via the shared `insertUser()` helper, so no seed change was needed

### Review Findings

- [x] [Review][Patch] Sort assertion uses lexicographic string comparison instead of temporal comparison [BookingRequestResourceIT.java:~628] — fixed: parses to `Instant` and compares via `Comparator.naturalOrder()` instead of `String::compareTo`.
- [x] [Review][Patch] `multipleCoachesHighestTargetGovernsDetection_IT` missing exact-threshold boundary case [NeglectedSkillDetectionServiceIT.java:~100] — fixed: added an `actual == lowerBound` (14.0 exactly) case asserting no open flag, pinning the strict `<` comparison in `NeglectedSkillProcessor`.
- [x] [Review][Confirmed Defect] `NeglectedSkillDetectionServiceIT.tearDown()` never deletes the `main.sec` row inserted by its class-level `@Sql({SecurityIT.SEC_DATA_SQL_PATH})` [NeglectedSkillDetectionServiceIT.java:62-70] — **reproduced**: running `NeglectedSkillDetectionServiceIT` followed by `BookingRequestResourceIT` (which shares the identical `@ActiveProfiles`/`@TestPropertySource`, so Spring reuses the same cached context/DB) in the same JVM caused `BookingRequestResourceIT`'s own `@Sql(SEC_DATA_SQL_PATH)` insert to fail with `duplicate key value violates unique constraint "sec_pkey"`, since the fixture script does a plain `INSERT` with a hardcoded id and no `ON CONFLICT`. Fixed by adding `jdbcTemplate.execute("DELETE FROM main.sec")` to `tearDown()`, matching the convention already used in `BookingRequestResourceIT.java:194`. Verified: `NeglectedSkillDetectionServiceIT` + all `BookingRequestResourceIT` tests now pass together (20/20, 0 failures). Severity: High (test-suite reliability — order-dependent failure across IT classes).
- [x] [Review][Defer] `resolveParentName()` can render "null null" when a user's first/last name is null [BookingService.java, unchanged by this diff] — deferred, pre-existing; the new `parentName` assertion (AC5) only checks non-null/non-empty, which would pass on this garbage value too.
- [x] [Review][Defer] `declineBooking_wrongCoach_returns403` reads `createResp.getBody().get("id")` without asserting creation succeeded first [BookingRequestResourceIT.java:~537] — deferred, pre-existing; mirrors the same gap in the existing `acceptBooking_wrongCoach_returns403` test.
- [x] [Review][Dismiss] ~~`NeglectedSkillDetectionServiceIT` activates both `dev` and `test` Spring profiles~~ — false positive; `BookingRequestResourceIT.java:42` uses the identical `@ActiveProfiles({"dev", "test"})`, confirming this is the codebase's existing IT convention, not a novel pattern.
- [x] [Review][Defer] Hardcoded `PLAYER_ID = 9360000001L` reused against the shared `SecurityIT.SEC_DATA_SQL_PATH` fixture [NeglectedSkillDetectionServiceIT.java:47] — deferred, unconfirmed; possible cross-test collision risk, not provable from this diff alone.

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

claude-sonnet-5

### Debug Log References

- Confirmed the AC1 year-boundary bug pre-fix by simulating `now` at ISO week 1 boundaries in `jshell` (e.g. 2027-01-04): old logic produced `prevWeek=52, prevPrevWeek=51` under the current year, vs. correct `2026-W53`/`2026-W52`.
- `getParentBookings_returnsListSortedByStartTime` (Task 4) initially failed with `HttpServerErrorException$InternalServerError: 500` — server log showed `NullPointerException` at `BookingService.java:340` (`batchSizeMap.get(b.getBatchId())`, called with a `null` key against `Map.of()`). Root-caused to `Map.of().get(null)` / `getOrDefault(null, ...)` always throwing NPE on the JDK's immutable maps. Fixed in `BookingService.getParentBookings()`; re-ran — passed.
- Full regression suite (`mvn -o test`): 1287 tests, 0 failures, 0 errors, 5 skipped (pre-existing skips, unrelated to this story).

### Completion Notes List

- All 5 ACs implemented as test-coverage additions/strengthening per the story's intent.
- Task 4 surfaced and required fixing a genuine production defect (NPE in `getParentBookings()` for any parent with a non-batched booking) — out of the story's stated "test files only" scope, but necessary for the new assertion to pass and for the endpoint to function correctly. Documented above and in File List.
- AC2's IT test values were adapted from the story's suggested scenario (which compared `actual` directly against raw targets) to the processor's real `lowerBound = maxTarget × (1 − threshold)` logic, and chosen specifically to discriminate a `MAX()`-scoped-by-single-coach regression from the correct all-coaches aggregation.
- No new dependencies introduced; no changes outside the affected test files and the one production NPE fix.

### File List

**New Files:**
- `src/test/java/com/softropic/skillars/platform/development/service/NeglectedSkillDetectionServiceIT.java`

**Modified Files:**
- `src/test/java/com/softropic/skillars/platform/development/service/SluDashboardServiceTest.java`
- `src/test/java/com/softropic/skillars/platform/booking/api/BookingRequestResourceIT.java`
- `src/main/java/com/softropic/skillars/platform/booking/service/BookingService.java` (production NPE fix found via Task 4)

## Change Log

- 2026-07-02: All 5 tasks implemented — fixed AC1 year-boundary week arithmetic, added AC2 multi-coach IT (`NeglectedSkillDetectionServiceIT`), added AC3 `declineBooking_wrongCoach_returns403`, strengthened AC4 sort-order assertion (which surfaced and required fixing a `getParentBookings()` NPE regression), added AC5 `parentName` assertion. Full regression suite green (1287 tests, 0 failures). Status → review.
- 2026-07-02: Code review (3-layer adversarial: Blind Hunter, Edge Case Hunter, Acceptance Auditor) confirmed all 5 ACs satisfied, no production defects. Applied 2 patches (temporal-vs-string sort comparison; missing exact-threshold boundary case in AC7 IT) plus 1 confirmed defect found during patch verification (`NeglectedSkillDetectionServiceIT` never cleaned up its `main.sec` fixture row, causing a reproducible `duplicate key` failure in subsequent IT classes sharing the same Spring test context — fixed). 4 items deferred as pre-existing/unconfirmed. Full unit suite green (758 tests) plus all 3 touched IT/test classes verified together (20/20). Status → done.
