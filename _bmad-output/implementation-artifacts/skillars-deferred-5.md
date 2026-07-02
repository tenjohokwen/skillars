# Story Deferred-5: Platform Security — Coach-Player Authorization

Status: done

## Story

As a platform security engineer,
I want coach endpoints that access player development data to verify the coach-player relationship,
so that a coach cannot read or write development data for players they have never coached.

## Acceptance Criteria

1. **Given** a coach calls any development module endpoint that takes a `playerId`
   **When** the coach has no prior booking relationship with that player (no ACCEPTED, COMPLETED, or CONFIRMED booking linking them)
   **Then** the endpoint returns `403` with `helpCode = "security.missingRights"` — a coach without a booking relationship cannot access that player's data
   **Affected endpoints**:
   - `GET /api/development/players/{playerId}/narrative` (skill exposure)
   - `GET /api/development/players/{playerId}/exposure` (skill exposure)
   - `GET /api/development/players/{playerId}/targets` (SLU targets)
   - `POST /api/development/players/{playerId}/targets` (SLU targets)
   - `GET /api/development/players/{playerId}/radar/display`
   - `GET /api/development/players/{playerId}/radar/correlation`
   - `POST /api/development/players/{playerId}/radar/assessments`
   - `GET /api/development/players/{playerId}/reports` (performance reports)
   - `POST /api/development/players/{playerId}/reports`

2. **Given** the booking relationship check is implemented
   **When** the coach has at least one booking in `ACCEPTED`, `CONFIRMED`, `COMPLETED`, or `UPCOMING` status with the given player
   **Then** the coach passes the authorization check and the endpoint proceeds normally
   **And** a parent calling the same endpoint for their own child still works (parent auth path is separate)

3. **Given** `GET /api/booking/{bookingId}` is called by any authenticated user
   **When** the caller is not the booking's parent, coach, or admin
   **Then** the endpoint returns `403` with `helpCode = "security.missingRights"` instead of the booking details
   **And** an admin role bypasses the ownership check (same pattern as other admin bypass paths)

4. **Given** `AdminReviewResource` (at `/api/admin/reviews/...`) throws `ResourceNotFoundException`
   **When** the exception propagates
   **Then** it is handled by the same advice that handles `ReviewResource` exceptions, returning the same error shape (`ErrorDto` with `helpCode`)
   **Fix**: extend `@ControllerAdvice` scope in `ReviewApiAdvice` to include `platform.admin.api` package (or `basePackages = {"platform.reviews.api", "platform.admin.api"}`)

## Tasks / Subtasks

- [x] **Task 1 — Add `BookingRepository.existsCoachPlayerRelationship()`** (AC: 1, 2)
  - [x] Reused the existing `BookingRepository.existsByCoachIdAndPlayerIdAndStatusIn(UUID coachId, Long playerId, List<String> statuses)` derived-query method instead of adding a duplicate — it already implements exactly the query the task specified. Called with `List.of("ACCEPTED", "CONFIRMED", "COMPLETED", "UPCOMING")`.
  - [x] `coachId` is the coach profile UUID (not the user Long ID) — resolved via `coachProfileRepository.findByUserId(currentUserId)`
  - [x] `playerId` is the `PlayerProfile.id` (Long TSID)
  - [x] Confirmed exact status strings from `BookingStatus.java` (enum `.name()` values stored as-is in the `status` column)

- [x] **Task 2 — Create `CoachPlayerAuthorizationService`** (AC: 1, 2)
  - [x] Created `platform.development.service.CoachPlayerAuthorizationService` — `@Service @RequiredArgsConstructor`, with `requireCoachPlayerRelationship(Long coachUserId, Long playerId)` as specified, plus a `requireCoachPlayerRelationship(UUID coachProfileId, Long playerId)` overload for call sites that already resolved the coach profile UUID (avoids a redundant `coachProfileRepository` lookup at several call sites).

- [x] **Task 3 — Add authorization check to development service layer** (AC: 1, 2)
  - [x] Injected `CoachPlayerAuthorizationService` (and `SecurityUtil` where the method had no coach identifier available) into:
    - `SluDashboardService` — `getWeeklyExposure` (exposure) and `getNarrativeSummary` (narrative); role-gated since this endpoint is dual coach/parent access
    - `SluTargetService` — `getTargets`/`setTargets` (coach-only route, `coachId` already resolved by caller)
    - `RadarDisplayService` — `getRadarDisplay` (role-gated, dual access)
    - `DevelopmentCorrelationService` — `getInsights` (correlation; this is where the correlation endpoint's logic actually lives, not `RadarDisplayService` as the task text assumed — `coachId` already resolved by caller)
    - `RadarAssessmentService` — `submitAssessment` and `getMyEntries` (both already receive `coachUserId`)
    - `ReportGenerationService` — `generateReport` (already receives `coachUserId`) and `listReports` (role-gated, dual access)
  - [x] Used `securityUtil.isCurrentUserInRole(AuthoritiesConstants.COACH)` for the role check — `SecurityUtil` already had this method (equivalent to the story's proposed `hasRole`), so no new method was added to `SecurityUtil`.
  - [x] The `ROLE_PARENT` path is untouched — the check is skipped entirely when the caller is not in the COACH role (or, for coach-only routes, always runs since only coaches reach that code path).
  - [x] Check inserted as the first statement in each method, before any data is loaded.

- [x] **Task 4 — Add caller auth check to booking lookup by id** (AC: 3)
  - [x] **Deviation from the task's snippet**: `BookingService.getBooking(UUID)` has no caller-authorization check today and isn't the method backing a public lookup-by-id endpoint. The actual `GET /api/bookings/{id}` endpoint is `BookingEventResource.getBooking()`, which already had a `verifyIsParty(booking, actorUserId)` check (parent-or-coach) — it was just missing the admin bypass required by AC3. Added `if (securityUtil.isAdmin()) return;` as the first line of `verifyIsParty`, matching the `isAdmin()` bypass pattern used elsewhere in the codebase. This also fixes the same gap on the sibling `GET /{id}/events` (SSE) endpoint, which shares `verifyIsParty`.
  - [x] `CoachProfileRepository` was already injected in `BookingEventResource`; no new injection needed.

- [x] **Task 5 — Ensure `AdminReviewResource` uses the same error shape as `ReviewResource`** (AC: 4)
  - [x] **Deviation from the task's primary suggestion**: verified that `ReviewApiAdvice` does not (and never did) declare a handler for `ResourceNotFoundException` — both `ReviewResource` and `AdminReviewResource` already fall through to the global `ApiAdvice.resourceNotFoundExceptionHandler`, so they already produce an identical `ErrorDto` shape for that exception today. Per the task's own WARNING and the safe-fallback documented in Dev Notes, broadening `ReviewApiAdvice`'s `basePackages` to all of `platform.admin.api` was rejected — that package hosts unrelated admin resources whose `OperationNotAllowedException`/validation handling must not be silently rerouted through review-specific status-code logic.
  - [x] Implemented the safe fallback instead: created `platform.admin.api.AdminReviewApiAdvice extends ReviewApiAdvice`, scoped via `@RestControllerAdvice(assignableTypes = AdminReviewResource.class)` — this makes `AdminReviewResource` share every exception-shape rule `ReviewResource` has (present and future), without affecting any other admin controller. `ReviewApiAdvice.java` itself was not modified.

- [x] **Task 6 — Integration tests** (AC: 1, 3)
  - [x] New `CoachPlayerAuthorizationIT` (TSID range `9330_xxx`) with:
    - `coachAccessPlayerWithoutRelationship_returns403()`
    - `coachAccessPlayerWithRelationship_succeeds()`
    - `getBooking_thirdParty_returns403()`
    - `getBooking_admin_succeeds()`
  - [x] Also added `approveReview_notFound_returns404WithConsistentErrorShape()` to the existing `AdminReviewQueueIT` to cover AC4.
  - [x] Updated fixtures in `SkillExposureResourceIT`, `RadarAssessmentResourceIT`, `RadarDisplayResourceIT`, and `PlayerTimelineResourceIT` to seed a `COMPLETED` booking between each test coach and the test player — these pre-existing coach-path tests would otherwise now fail the new relationship check (they were exercising subscription-tier/RBAC/data-isolation behavior, not the relationship check itself).

## Dev Notes

### Performance of `existsCoachPlayerRelationship`

This runs on every development endpoint call for COACH role. At MVP scale (small number of coaches per player), the query is fast. Add an index on `booking.bookings(coach_id, player_id, status)` if not already present — check existing indexes from V31 and V62 migrations.

### `securityUtil.hasRole()` method

Confirm that `SecurityUtil` has a `hasRole(String role)` method, or use Spring Security's `SecurityContextHolder.getContext().getAuthentication().getAuthorities()` directly. If no role-check helper exists, add one to `SecurityUtil`:
```java
public boolean hasRole(String role) {
    return SecurityContextHolder.getContext().getAuthentication()
        .getAuthorities().stream()
        .anyMatch(a -> a.getAuthority().equals(role));
}
```

### `ReviewApiAdvice` scope — admin package risk

Before broadening `ReviewApiAdvice` to cover `platform.admin.api`, confirm that `ResourceNotFoundException` responses from admin controllers (10.1–10.4) already produce the same `ErrorDto` JSON shape. If `GlobalApiAdvice` (or the global handler) produces a different shape, the admin controllers currently use the global handler for these exceptions — broadening `ReviewApiAdvice` would change their error shape silently. When in doubt, create a `AdminReviewApiAdvice` that covers only `AdminReviewResource`:
```java
@ControllerAdvice(assignableTypes = AdminReviewResource.class)
public class AdminReviewApiAdvice extends ReviewApiAdvice { }
```

### Coach profile ID resolution

The development module uses `playerId` (Long) as the player identifier. The booking table uses `coachId` (UUID, coach profile PK). The authorization check must resolve the current coach user's profile UUID via `coachProfileRepository.findByUserId(Long)` — this method already exists (confirmed in Story 10.4 dev notes).

### References — Files to Read Before Implementing

- `SkillExposureResource.java` / `SkillExposureService.java` — confirm ROLE_COACH guard on narrative endpoint
- `RadarAssessmentResource.java`, `RadarDisplayResource.java` — existing `@PreAuthorize` annotations
- `PerformanceReportResource.java` — existing coach auth pattern
- `BookingService.java:getBooking()` — current implementation (no caller check exists)
- `ReviewApiAdvice.java` — exact `@ControllerAdvice` annotation
- `AdminReviewResource.java` — `@RequestMapping` and which exceptions it throws
- `V31__booking_requests.sql` — existing booking indexes
- `CoachProfile.java`, `CoachProfileRepository.java` — `findByUserId(Long)` method

## Dev Agent Record

### Agent Model Used

Claude Sonnet 5 (claude-sonnet-5)

### Debug Log References

None — no debugging tooling invoked. One test-infra issue was found and fixed during Task 6: `jdbcTemplate.update()` calls made directly inside a `@Test` method body (outside `transactionTemplate.execute(...)`) never commit in this project, because `spring.datasource.hikari.auto-commit: false` is set globally. All new/modified test fixture inserts wrap their `jdbcTemplate.update` calls in `transactionTemplate.execute(...)`, matching the convention already used everywhere else in this test suite.

### Completion Notes List

- Task 1 reused the existing `BookingRepository.existsByCoachIdAndPlayerIdAndStatusIn()` derived query instead of adding a duplicate method — it already matched the required query exactly.
- Task 2 added a `UUID`-based overload of `requireCoachPlayerRelationship` alongside the `Long coachUserId` overload specified by the story, to avoid a redundant `coachProfileRepository` lookup at call sites (`SluTargetService`, `DevelopmentCorrelationService`) that already have the coach profile UUID resolved.
- Task 3: the correlation endpoint's logic lives in `DevelopmentCorrelationService.getInsights`, not `RadarDisplayService` as the task text assumed — the check was added there. `SkillExposureService` does not exist in this codebase; the narrative/exposure logic lives in `SluDashboardService`, which is what was modified.
- Task 4: `BookingService.getBooking(UUID)` is not the method backing the public `GET /api/bookings/{id}` endpoint and has no party check. The real endpoint is `BookingEventResource.getBooking()`, backed by `verifyIsParty()`, which already implemented the parent-or-coach check but was missing the admin bypass required by AC3. Added the bypass there instead of modifying `BookingService`; this also fixes the identical gap on the sibling SSE endpoint (`GET /{id}/events`) for free, since both share `verifyIsParty()`.
- Task 5: verified (per the task's own warning) that `ResourceNotFoundException` from `AdminReviewResource` and `ReviewResource` already produce an identical `ErrorDto` shape today, both falling through to the global `ApiAdvice` handler — `ReviewApiAdvice` never declared a handler for that exception type. Broadening `ReviewApiAdvice`'s `basePackages` was rejected as unsafe (it would silently reroute unrelated admin controllers' `OperationNotAllowedException`/validation handling through review-specific logic). Implemented the safe fallback explicitly suggested in the story's Dev Notes: `AdminReviewApiAdvice extends ReviewApiAdvice`, scoped via `assignableTypes = AdminReviewResource.class`.
- Task 6: added `CoachPlayerAuthorizationIT` covering all 4 specified scenarios, plus one extra test on `AdminReviewQueueIT` for AC4. Discovered and fixed 4 existing IT test classes (`SkillExposureResourceIT`, `RadarAssessmentResourceIT`, `RadarDisplayResourceIT`, `PlayerTimelineResourceIT`) whose coach-path tests started failing once the new relationship check went live, because their fixtures never created a booking between the test coach and test player. Added a `COMPLETED` booking to each affected fixture.
- Full regression suite run (`mvn test`, all modules): 751 tests, 0 failures, 0 errors, 1 pre-existing unrelated skip.

### File List

**New Files:**
- `src/main/java/com/softropic/skillars/platform/development/service/CoachPlayerAuthorizationService.java`
- `src/main/java/com/softropic/skillars/platform/admin/api/AdminReviewApiAdvice.java`
- `src/test/java/com/softropic/skillars/platform/development/api/CoachPlayerAuthorizationIT.java`

**Modified Files:**
- `src/main/java/com/softropic/skillars/platform/development/service/SluDashboardService.java`
- `src/main/java/com/softropic/skillars/platform/development/service/SluTargetService.java`
- `src/main/java/com/softropic/skillars/platform/development/service/RadarDisplayService.java`
- `src/main/java/com/softropic/skillars/platform/development/service/DevelopmentCorrelationService.java`
- `src/main/java/com/softropic/skillars/platform/development/service/RadarAssessmentService.java`
- `src/main/java/com/softropic/skillars/platform/development/service/ReportGenerationService.java`
- `src/main/java/com/softropic/skillars/platform/booking/api/BookingEventResource.java`
- `src/test/java/com/softropic/skillars/platform/development/service/SluDashboardServiceTest.java`
- `src/test/java/com/softropic/skillars/platform/development/service/DevelopmentCorrelationServiceTest.java`
- `src/test/java/com/softropic/skillars/platform/development/service/ReportGenerationServiceTest.java`
- `src/test/java/com/softropic/skillars/platform/development/api/SkillExposureResourceIT.java`
- `src/test/java/com/softropic/skillars/platform/development/api/RadarAssessmentResourceIT.java`
- `src/test/java/com/softropic/skillars/platform/development/api/RadarDisplayResourceIT.java`
- `src/test/java/com/softropic/skillars/platform/development/api/PlayerTimelineResourceIT.java`
- `src/test/java/com/softropic/skillars/platform/admin/api/AdminReviewQueueIT.java`
- `_bmad-output/implementation-artifacts/skillars-deferred-5.md`
- `_bmad-output/implementation-artifacts/sprint-status.yaml`

## Change Log

- 2026-07-02: Implemented coach-player booking-relationship authorization across all AC1 development endpoints, admin bypass on the booking-detail lookup (AC3), and the AdminReviewResource error-shape consistency fix (AC4). Added `CoachPlayerAuthorizationIT` plus fixture fixes to 4 pre-existing IT test classes. All ACs verified via integration tests; full regression suite green (751 tests).
