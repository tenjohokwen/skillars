# Story Deferred-5: Platform Security — Coach-Player Authorization

Status: backlog

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

- [ ] **Task 1 — Add `BookingRepository.existsCoachPlayerRelationship()`** (AC: 1, 2)
  - [ ] Add to `platform.booking.repo.BookingRepository`:
    ```java
    @Query("""
        SELECT COUNT(b) > 0 FROM Booking b
        WHERE b.coachId = :coachId
          AND b.playerId = :playerId
          AND b.status IN ('ACCEPTED', 'CONFIRMED', 'COMPLETED', 'UPCOMING')
        """)
    boolean existsCoachPlayerRelationship(
        @Param("coachId") UUID coachId,
        @Param("playerId") Long playerId);
    ```
  - [ ] `coachId` is the coach profile UUID (not the user Long ID) — resolved via `coachProfileRepository.findByUserId(currentUserId)`
  - [ ] `playerId` is the `PlayerProfile.id` (Long TSID)
  - [ ] Confirm the exact booking status string values from `BookingStatus.java` — use the same strings stored in DB

- [ ] **Task 2 — Create `CoachPlayerAuthorizationService`** (AC: 1, 2)
  - [ ] Create `platform.development.service.CoachPlayerAuthorizationService` — `@Service @RequiredArgsConstructor`:
    ```java
    @Service
    @RequiredArgsConstructor
    public class CoachPlayerAuthorizationService {

        private final BookingRepository bookingRepository;
        private final CoachProfileRepository coachProfileRepository;

        /**
         * Throws OperationNotAllowedException if the current coach has no booking
         * relationship with the given player.
         */
        public void requireCoachPlayerRelationship(Long coachUserId, Long playerId) {
            UUID coachProfileId = coachProfileRepository.findByUserId(coachUserId)
                .map(CoachProfile::getId)
                .orElseThrow(() -> new OperationNotAllowedException(
                    "Coach profile not found", SecurityError.MISSING_RIGHTS));

            if (!bookingRepository.existsCoachPlayerRelationship(coachProfileId, playerId)) {
                throw new OperationNotAllowedException(
                    "Coach has no booking relationship with this player",
                    SecurityError.MISSING_RIGHTS);
            }
        }
    }
    ```

- [ ] **Task 3 — Add authorization check to development service layer** (AC: 1, 2)
  - [ ] Inject `CoachPlayerAuthorizationService` into the following services and add the check at the top of each method that takes a `playerId` parameter called by a COACH role:
    - `SkillExposureService` / `SluDashboardService` — narrative and exposure methods
    - `SluTargetService` — get/set targets
    - `RadarDisplayService` — display and correlation
    - `RadarAssessmentService` — assessment submission and read
    - `ReportGenerationService` / `PerformanceReportService` — list and generate reports
  - [ ] Pattern for each:
    ```java
    // At method start, after extracting currentUserId and playerId:
    if (securityUtil.hasRole("ROLE_COACH")) {
        coachPlayerAuthorizationService.requireCoachPlayerRelationship(currentUserId, playerId);
    }
    // PARENT path: verify player belongs to parent (already done in most of these methods)
    ```
  - [ ] The `ROLE_PARENT` path is NOT affected — parents access their own children's data via the existing `playerId` ownership check; do not add the booking-relationship check for parents
  - [ ] Read each service method to identify exactly where the coach authorization check should be inserted — it must run before any data is loaded

- [ ] **Task 4 — Add caller auth check to `getBooking(UUID)`** (AC: 3)
  - [ ] In `BookingService.getBooking(UUID bookingId)`:
    ```java
    @Transactional(readOnly = true)
    public BookingDto getBooking(UUID bookingId) {
        Booking booking = bookingRepository.findById(bookingId)
            .orElseThrow(() -> new ResourceNotFoundException("Booking", bookingId.toString()));

        Long currentUserId = securityUtil.requireCurrentUserId();
        boolean isAdmin = securityUtil.hasRole("ROLE_ADMIN");
        boolean isParty = booking.getParentId().equals(currentUserId)
            || isCoachOfBooking(currentUserId, booking);

        if (!isAdmin && !isParty) {
            throw new OperationNotAllowedException(
                "Not authorized to view this booking", SecurityError.MISSING_RIGHTS);
        }
        return mapToDto(booking);
    }

    private boolean isCoachOfBooking(Long userId, Booking booking) {
        return coachProfileRepository.findByUserId(userId)
            .map(cp -> cp.getId().equals(booking.getCoachId()))
            .orElse(false);
    }
    ```
  - [ ] Confirm `BookingService` already injects `CoachProfileRepository` (it likely does from prior stories) or add the injection

- [ ] **Task 5 — Fix `ReviewApiAdvice` scope to cover `AdminReviewResource`** (AC: 4)
  - [ ] Read `ReviewApiAdvice.java` — find the `@ControllerAdvice` annotation
  - [ ] Current: `@ControllerAdvice(basePackages = "platform.reviews.api")` (or similar)
  - [ ] Update:
    ```java
    @ControllerAdvice(basePackages = {
        "com.softropic.skillars.platform.reviews.api",
        "com.softropic.skillars.platform.admin.api"
    })
    ```
  - [ ] Confirm the fully-qualified package names from the existing `@ControllerAdvice` annotation — the package prefix `com.softropic.skillars` may differ
  - [ ] **WARNING**: extending the advice scope to `platform.admin.api` means ALL `ResourceNotFoundException` from ALL admin controllers will now use the reviews error shape. Verify that `ResourceNotFoundException` in other admin controllers (10.1, 10.2, 10.3, 10.4) already uses the same `ErrorDto` format — if not, create a separate `AdminApiAdvice` that covers only `AdminReviewResource` instead of broadening `ReviewApiAdvice`

- [ ] **Task 6 — Integration tests** (AC: 1, 3)
  - [ ] TSID range `9330_xxx`
  - [ ] `coachAccessPlayerWithoutRelationship_returns403()`:
    - Seed: coach, player, parent; no bookings between coach and player
    - GET `/api/development/players/{playerId}/narrative` with coach token → 403
  - [ ] `coachAccessPlayerWithRelationship_succeeds()`:
    - Seed: coach, player, parent, booking (`status = 'COMPLETED'`)
    - GET `/api/development/players/{playerId}/exposure` with coach token → 200
  - [ ] `getBooking_thirdParty_returns403()`:
    - Seed: booking for parentA and coachA; GET with parentB token → 403
  - [ ] `getBooking_admin_succeeds()`:
    - GET with admin token → 200

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

### Debug Log References

### Completion Notes List

### File List

**New Files:**
- `src/main/java/com/softropic/skillars/platform/development/service/CoachPlayerAuthorizationService.java`

**Modified Files:**
- `src/main/java/com/softropic/skillars/platform/booking/repo/BookingRepository.java`
- `src/main/java/com/softropic/skillars/platform/booking/service/BookingService.java`
- `src/main/java/com/softropic/skillars/platform/development/service/SkillExposureService.java`
- `src/main/java/com/softropic/skillars/platform/development/service/SluTargetService.java`
- `src/main/java/com/softropic/skillars/platform/development/service/RadarDisplayService.java`
- `src/main/java/com/softropic/skillars/platform/development/service/RadarAssessmentService.java`
- `src/main/java/com/softropic/skillars/platform/development/service/ReportGenerationService.java`
- `src/main/java/com/softropic/skillars/platform/reviews/api/ReviewApiAdvice.java`
- `src/main/java/com/softropic/skillars/platform/security/service/SecurityUtil.java`
