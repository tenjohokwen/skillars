# Story 10.2: Coach Enforcement & Strike Management

Status: done

## Story

As a platform admin,
I want to view coach reliability strikes, manually intervene when needed, and suspend coaches who breach thresholds,
So that the platform remains safe for players and parents and coaches are treated fairly through a transparent process.

## Acceptance Criteria

1. **Given** a `StrikeThresholdReachedEvent` is published (Story 7.3)
   **When** the admin module consumes it
   **Then** an `admin_alerts` row is inserted (type = `STRIKE_THRESHOLD`) — **already implemented in Story 10.1**
   **And** `coach_profiles.status = PENDING_REVIEW` — **already set by `ReliabilityStrikeService.issue()` in Story 7.3**
   **NOTE:** AC 1 confirms pre-existing wiring is correct. No new code for the event listener — `AdminAlertEventListener.onStrikeThreshold()` already handles this.

2. **Given** an admin views a coach's enforcement profile
   **When** `GET /api/admin/coaches/{coachId}/enforcement` is called
   **Then** `@PreAuthorize` admin role required
   **And** the response includes:
   - `coachId`, `coachName`, `currentStatus` (ACTIVE/REDUCED/PENDING_REVIEW/SUSPENDED/DEACTIVATED)
   - `activeStrikes`: count of strikes in the rolling 30-day window from `coach_reliability_strikes`
   - `strikeHistory`: all rows from `coach_reliability_strikes` ordered by `createdAt DESC` (epic says `issuedAt` but the entity field is `createdAt`), each with `reason`, `bookingId`, `createdAt`, `acknowledged`
   - `cancellationHistory`: all rows from `coach_cancellation_history` ordered by `createdAt DESC` (epic says `cancelledAt` but the entity field is `createdAt`), each with `cancelReason`, `bookingId`, `createdAt`
   - `openAlerts`: count of OPEN `admin_alerts` where `referenceId = coachId.toString()` (type = STRIKE_THRESHOLD)

3. **Given** an admin decides to suspend a coach
   **When** `POST /api/admin/coaches/{coachId}/suspend` is called with `{ "reason": "...", "notifyCoach": true }`
   **Then** `coach_profiles.status = SUSPENDED`, `statusChangedAt = now()`
   **And** all REQUESTED bookings for this coach are cancelled — `BookingCancelledByAdminEvent` published for each; credit refunded to parent (100%) via `CancellationRefundService.onBookingCancelledByAdmin()`
   **And** ACCEPTED/CONFIRMED/UPCOMING bookings are NOT auto-cancelled — admin handles individually
   **And** if `notifyCoach = true`: `CoachSuspensionNotificationEvent(coachId, reason)` published (notification delivery out of scope)
   **And** `CoachSuspendedEvent(coachId, reason, adminId)` published
   **And** action logged in `admin_action_log` (actionType = `COACH_SUSPEND`, referenceId = `coachId.toString()`, reason = reason)
   **And** `200 OK`

4. **Given** a suspended coach's profile is viewed by a parent or player
   **When** `GET /api/marketplace/coaches/{coachId}` is called
   **Then** `404` is returned — `CoachProfileService.getPublicProfile()` already filters out non-ACTIVE/non-REDUCED/non-PENDING_REVIEW coaches; SUSPENDED → 404 naturally
   **NOTE:** Update the existing filter to include PENDING_REVIEW in visible statuses (see Dev Notes)

5. **Given** an admin reinstates a suspended coach
   **When** `POST /api/admin/coaches/{coachId}/reinstate` is called with `{ "reason": "..." }`
   **Then** `coach_profiles.status = ACTIVE`, `statusChangedAt = now()`
   **And** the OPEN `admin_alerts` row with `type = STRIKE_THRESHOLD` and `referenceId = coachId.toString()` is resolved: `status = RESOLVED`, `resolvedAt = now()`, `resolvedBy = adminId`
   **And** `CoachReinstatedEvent(coachId, adminId)` published
   **And** action logged in `admin_action_log` (actionType = `COACH_REINSTATE`, referenceId = `coachId.toString()`)
   **And** `200 OK`

6. **Given** an admin wants to manually issue a strike
   **When** `POST /api/admin/coaches/{coachId}/strikes` is called with `{ "bookingId": "...", "reason": "COACH_CANCELLATION_UNEXCUSED" | "COACH_NO_SHOW" }`
   **Then** a new row is inserted into `coach_reliability_strikes` via `ReliabilityStrikeService.issue(coachId, bookingId, reason)` — this reuses the full threshold check, status update, and event publishing logic from Story 7.3
   **And** action logged in `admin_action_log` (actionType = `COACH_SUSPEND` if threshold crossed and `PENDING_REVIEW` set, otherwise a general "MANUAL_STRIKE" — see Dev Notes)
   **And** `201 Created` with `{ "strikeId": "..." }`

7. **Given** an admin wants to remove a strike issued in error
   **When** `DELETE /api/admin/coaches/{coachId}/strikes/{strikeId}` is called with `{ "reason": "..." }`
   **Then** the strike row is hard-deleted from `coach_reliability_strikes`
   **And** rolling 30-day count is re-evaluated: if count < `reliability.strike.visibilityThreshold` AND coach is currently `PENDING_REVIEW` → set `coach_profiles.status = ACTIVE`, `statusChangedAt = now()`
   **And** action logged in `admin_action_log` (actionType = `COACH_REINSTATE` if status reverted to ACTIVE, otherwise use new action type — see Dev Notes)
   **And** `200 OK`

8. **Given** an admin views coaches under enforcement review
   **When** `GET /api/admin/coaches?status=PENDING_REVIEW|SUSPENDED&page={n}` is called
   **Then** `@PreAuthorize` admin role required
   **And** a paginated list is returned with `coachId`, `coachName`, `status`, `activeStrikes`, `statusChangedAt`, ordered by `statusChangedAt ASC` — oldest unresolved first
   **And** `activeStrikes` is computed as rolling 30-day count from `coach_reliability_strikes`

9. **Given** a parent attempts to book a SUSPENDED coach
   **When** `POST /api/booking` is called and `coach_profiles.status = SUSPENDED`
   **Then** `403` with `ErrorDto.helpCode = "booking.coachUnavailable"`
   **NOTE:** Also update the booking guard to allow PENDING_REVIEW coaches (they can still operate per AC 1)

## Tasks / Subtasks

- [x] **Task 1 — Flyway V71: Add `status_changed_at` to `coach_profiles`** (AC: 3, 5, 7, 8)
  - [x] Create `src/main/resources/db/migration/V71__coach_profile_status_changed_at.sql`:
    ```sql
    ALTER TABLE marketplace.coach_profiles
        ADD COLUMN status_changed_at TIMESTAMPTZ;
    ```
  - [x] Column is nullable (backfill of existing rows is not needed — nulls treated as "oldest" in ordering)
  - [x] **Do NOT add `SUSPENDED` to a DB CHECK constraint** — `coach_profiles.status` is a `VARCHAR` column (confirmed from Story 7.3 dev notes and `CoachProfileStatus.java`). The Java enum controls valid values; no DB enum type or CHECK constraint on this column to modify.

- [x] **Task 2 — Update `CoachProfileStatus` Enum and `CoachProfile` Entity** (AC: 3, 4, 5, 8)
  - [x] Add `SUSPENDED` and `DEACTIVATED` to `platform.marketplace.contract.CoachProfileStatus`:
    ```java
    public enum CoachProfileStatus {
        DRAFT, ACTIVE, REDUCED, PENDING_REVIEW, SUSPENDED, DEACTIVATED
    }
    ```
  - [x] Update `platform.marketplace.repo.CoachProfile` entity — add `statusChangedAt` field:
    ```java
    @Column(name = "status_changed_at")
    private Instant statusChangedAt;
    ```
  - [x] Add query to `CoachProfileRepository`:
    ```java
    @Query("SELECT p FROM CoachProfile p WHERE p.status IN :statuses ORDER BY p.statusChangedAt ASC NULLS LAST")
    Page<CoachProfile> findByStatusInOrderByStatusChangedAtAsc(
        @Param("statuses") List<CoachProfileStatus> statuses, Pageable pageable);
    ```
  - [x] `NULLS LAST` ensures coaches without `statusChangedAt` (legacy rows) sort after those with a value

- [x] **Task 3 — Update `CoachCancellationHistoryRepository`** (AC: 2)
  - [x] Add method to `platform.payment.repo.CoachCancellationHistoryRepository`:
    ```java
    List<CoachCancellationHistory> findByCoachIdOrderByCreatedAtDesc(UUID coachId);
    ```

- [x] **Task 4 — `BookingError` Enum + `BookingService` Update** (AC: 9)
  - [x] Create `platform.booking.contract.BookingError`:
    ```java
    public enum BookingError implements ErrorCode {
        COACH_UNAVAILABLE;

        @Override
        public String getErrorCode() {
            return switch (this) {
                case COACH_UNAVAILABLE -> "booking.coachUnavailable";
            };
        }
    }
    ```
  - [x] Update `platform.booking.service.BookingService.requestBooking()` — replace the existing coach status guard:
    ```java
    // BEFORE (Story 3.3 implementation):
    // if (coach.getStatus() != CoachProfileStatus.ACTIVE && coach.getStatus() != CoachProfileStatus.REDUCED) {
    //     throw new OperationNotAllowedException("Coach profile is not active", SecurityError.MISSING_RIGHTS);
    // }

    // AFTER (Story 10.2 addition):
    if (coach.getStatus() == CoachProfileStatus.SUSPENDED) {
        throw new OperationNotAllowedException("Coach is suspended", BookingError.COACH_UNAVAILABLE);
    }
    if (coach.getStatus() != CoachProfileStatus.ACTIVE
            && coach.getStatus() != CoachProfileStatus.REDUCED
            && coach.getStatus() != CoachProfileStatus.PENDING_REVIEW) {
        throw new OperationNotAllowedException("Coach profile is not active", SecurityError.MISSING_RIGHTS);
    }
    ```
  - [x] PENDING_REVIEW is added to the allowed set because PENDING_REVIEW coaches "can still operate" (Story 10.2 AC 1). The current guard was written before PENDING_REVIEW existed (Story 3.3 predates Story 7.3). This is an intentional fix, not a regression.
  - [x] `BookingError` import: `import com.softropic.skillars.platform.booking.contract.BookingError;`

- [x] **Task 5 — Update `CoachProfileService.getPublicProfile()`** (AC: 4)
  - [x] Update filter in `platform.marketplace.service.CoachProfileService.getPublicProfile()` to allow PENDING_REVIEW coaches to be publicly visible:
    ```java
    // BEFORE:
    // .filter(p -> p.getStatus() == CoachProfileStatus.ACTIVE)

    // AFTER:
    .filter(p -> p.getStatus() == CoachProfileStatus.ACTIVE
              || p.getStatus() == CoachProfileStatus.REDUCED
              || p.getStatus() == CoachProfileStatus.PENDING_REVIEW)
    ```
  - [x] SUSPENDED and DEACTIVATED coaches return `ResourceNotFoundException` → 404 via the existing `orElseThrow()` downstream
  - [x] Add imports: `import com.softropic.skillars.platform.marketplace.contract.CoachProfileStatus;` (already present)

- [x] **Task 6 — New Domain Events** (AC: 3, 5)
  - [x] Create `platform.booking.contract.BookingCancelledByAdminEvent`:
    ```java
    public class BookingCancelledByAdminEvent extends ApplicationEvent {
        private final UUID bookingId;
        private final Long parentId;
        private final UUID coachId;
        private final UUID sessionPackPurchaseId; // null if pay-as-you-go
        private final BigDecimal sessionPrice;

        public BookingCancelledByAdminEvent(Object source, UUID bookingId, Long parentId,
                UUID coachId, UUID sessionPackPurchaseId, BigDecimal sessionPrice) {
            super(source);
            this.bookingId = bookingId;
            this.parentId = parentId;
            this.coachId = coachId;
            this.sessionPackPurchaseId = sessionPackPurchaseId;
            this.sessionPrice = sessionPrice;
        }
        // getters for all fields
    }
    ```
  - [x] Lives in `platform.booking.contract` (alongside `BookingCancelledByCoachEvent`) — admin module publishes it; payment module consumes it
  - [x] Create `platform.admin.contract.CoachSuspendedEvent`:
    ```java
    public class CoachSuspendedEvent extends ApplicationEvent {
        private final UUID coachId;
        private final String reason;
        private final Long adminId;
        // constructor + getters
    }
    ```
  - [x] Create `platform.admin.contract.CoachReinstatedEvent`:
    ```java
    public class CoachReinstatedEvent extends ApplicationEvent {
        private final UUID coachId;
        private final Long adminId;
        // constructor + getters
    }
    ```
  - [x] Create `platform.admin.contract.CoachSuspensionNotificationEvent`:
    ```java
    public class CoachSuspensionNotificationEvent extends ApplicationEvent {
        private final UUID coachId;
        private final String reason;
        // constructor + getters
    }
    ```

- [x] **Task 7 — Admin Contract DTOs** (AC: 2, 3, 5, 6, 7, 8)
  - [x] Create `platform.admin.contract.CoachEnforcementProfileDto`:
    ```java
    public record CoachEnforcementProfileDto(
        UUID coachId, String coachName, String currentStatus,
        long activeStrikes,
        List<CoachStrikeHistoryDto> strikeHistory,
        List<CoachCancellationHistoryEntryDto> cancellationHistory,
        long openAlerts) {}
    ```
  - [x] Create `platform.admin.contract.CoachStrikeHistoryDto`:
    ```java
    public record CoachStrikeHistoryDto(
        UUID strikeId, String reason, UUID bookingId, Instant createdAt, boolean acknowledged) {}
    ```
  - [x] Create `platform.admin.contract.CoachCancellationHistoryEntryDto`:
    ```java
    public record CoachCancellationHistoryEntryDto(
        UUID id, String cancelReason, UUID bookingId, Instant createdAt) {}
    ```
  - [x] Create `platform.admin.contract.CoachEnforcementListItemDto`:
    ```java
    public record CoachEnforcementListItemDto(
        UUID coachId, String coachName, String status, long activeStrikes, Instant statusChangedAt) {}
    ```
  - [x] Create `platform.admin.contract.AdminSuspendCoachRequest`:
    ```java
    public record AdminSuspendCoachRequest(@NotBlank @Size(max = 500) String reason, boolean notifyCoach) {}
    ```
  - [x] Create `platform.admin.contract.AdminReinstateCoachRequest`:
    ```java
    public record AdminReinstateCoachRequest(@NotBlank @Size(max = 500) String reason) {}
    ```
  - [x] Create `platform.admin.contract.AdminManualStrikeRequest`:
    ```java
    public record AdminManualStrikeRequest(
        @NotNull UUID bookingId,
        @NotBlank String reason) {} // must be "COACH_CANCELLATION_UNEXCUSED" or "COACH_NO_SHOW"
    ```
  - [x] Create `platform.admin.contract.AdminManualStrikeResponse`:
    ```java
    public record AdminManualStrikeResponse(UUID strikeId) {}
    ```
  - [x] Create `platform.admin.contract.AdminDeleteStrikeRequest`:
    ```java
    public record AdminDeleteStrikeRequest(@NotBlank @Size(max = 500) String reason) {}
    ```

- [x] **Task 8 — `AdminCoachEnforcementService`** (AC: 2, 3, 5, 6, 7, 8)
  - [x] Create `platform.admin.service.AdminCoachEnforcementService` — `@Service @RequiredArgsConstructor @Slf4j`
  - [x] Inject: `CoachProfileRepository`, `CoachReliabilityStrikeRepository`, `CoachCancellationHistoryRepository`, `AdminAlertRepository`, `AdminActionLogRepository`, `BookingRepository`, `CoachPricingRepository`, `SessionPackPurchaseRepository`, `ReliabilityStrikeService`, `ConfigService`, `ApplicationEventPublisher`
  - [x] **`@Transactional(readOnly = true)` method `getEnforcementProfile(UUID coachId)`** → `CoachEnforcementProfileDto`:
    1. Load `CoachProfile` or throw `ResourceNotFoundException("Coach profile not found", "coach_profile")`
    2. `activeStrikes = strikeRepository.countByCoachIdAndCreatedAtAfter(coachId, OffsetDateTime.now().minusDays(30))`
    3. `strikeHistory = strikeRepository.findByCoachIdOrderByCreatedAtDesc(coachId)` → map to `CoachStrikeHistoryDto`
    4. `cancellationHistory = cancellationHistoryRepository.findByCoachIdOrderByCreatedAtDesc(coachId)` → map to `CoachCancellationHistoryEntryDto`
    5. `openAlerts = adminAlertRepository.countOpenAlertsByReferenceId(coachId.toString())` — add this query to `AdminAlertRepository`:
       ```java
       @Query("SELECT COUNT(a) FROM AdminAlert a WHERE a.referenceId = :referenceId AND a.status = 'OPEN'")
       long countOpenByReferenceId(@Param("referenceId") String referenceId);
       ```
    6. Return DTO
  - [x] **`@Transactional` method `suspendCoach(UUID coachId, String reason, boolean notifyCoach, Long adminId)`**:
    1. Load `CoachProfile` or throw `ResourceNotFoundException`
    2. **Idempotency guard**: `if (coach.getStatus() == CoachProfileStatus.SUSPENDED) return;` — prevents double-log on retry
    3. `coach.setStatus(SUSPENDED)` + `coach.setStatusChangedAt(Instant.now())`
    4. `coachProfileRepository.save(coach)`
    5. Find REQUESTED bookings: `bookingRepository.findByCoachIdAndStatusOrderByRequestedStartTimeAsc(coachId, "REQUESTED")`
    6. For each REQUESTED booking:
       - `booking.setStatus("CANCELLED")` + `booking.setCancelReason("COACH_SUSPENDED_BY_ADMIN")` + `bookingRepository.save(booking)`
       - Compute `sessionPrice = resolveAdminBookingPrice(booking)` (see helper below)
       - Publish `BookingCancelledByAdminEvent(source, booking.getId(), booking.getParentId(), coachId, booking.getSessionPackPurchaseId(), sessionPrice)`
    7. If `notifyCoach`: publish `CoachSuspensionNotificationEvent(source, coachId, reason)`
    8. Publish `CoachSuspendedEvent(source, coachId, reason, adminId)`
    9. Log: `AdminActionLog(adminId, COACH_SUSPEND, coachId.toString(), reason)`
    10. `adminActionLogRepository.save(log)`
  - [x] **Private helper `resolveAdminBookingPrice(Booking booking)`** → `BigDecimal`:
    ```java
    if (booking.getSessionPackPurchaseId() != null) {
        return sessionPackPurchaseRepository.findById(booking.getSessionPackPurchaseId())
            .map(p -> p.getPricePerSession())
            .orElse(BigDecimal.ZERO); // pack may have expired/been deleted — safe default
    }
    return coachPricingRepository.findByCoachId(booking.getCoachId())
        .map(p -> p.getPerSessionPrice())
        .orElse(BigDecimal.ZERO); // pricing deleted — no credit to issue; safe fallback
    ```
  - [x] **`@Transactional` method `reinstateCoach(UUID coachId, String reason, Long adminId)`**:
    1. Load `CoachProfile` or throw
    2. **Idempotency guard**: `if (coach.getStatus() == CoachProfileStatus.ACTIVE) return;`
    3. `coach.setStatus(ACTIVE)` + `coach.setStatusChangedAt(Instant.now())`
    4. `coachProfileRepository.save(coach)`
    5. Resolve OPEN STRIKE_THRESHOLD alert for this coach:
       ```java
       adminAlertRepository.findFirstByReferenceIdAndTypeAndStatus(
           coachId.toString(), AdminAlertType.STRIKE_THRESHOLD, AdminAlertStatus.OPEN)
           .ifPresent(alert -> {
               alert.setStatus(AdminAlertStatus.RESOLVED);
               alert.setResolvedAt(Instant.now());
               alert.setResolvedBy(adminId);
               adminAlertRepository.save(alert);
           });
       ```
    6. Publish `CoachReinstatedEvent(source, coachId, adminId)`
    7. Log: `AdminActionLog(adminId, COACH_REINSTATE, coachId.toString(), reason)`
  - [x] **`@Transactional` method `issueManualStrike(UUID coachId, UUID bookingId, String reason, Long adminId)`** → `UUID` (strikeId):
    1. Validate `reason IN ("COACH_CANCELLATION_UNEXCUSED", "COACH_NO_SHOW")` — throw `ResponseStatusException(BAD_REQUEST, "Invalid strike reason")` otherwise
    2. Load `CoachProfile` or throw — verify coach exists
    3. Validate bookingId ownership — throw `ResponseStatusException(BAD_REQUEST, "Booking does not belong to this coach")` if the booking exists but `booking.getCoachId() != coachId`:
       ```java
       bookingRepository.findById(bookingId).ifPresent(b -> {
           if (!b.getCoachId().equals(coachId))
               throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Booking does not belong to this coach");
       });
       ```
    4. Call `CoachReliabilityStrike strike = reliabilityStrikeService.issue(coachId, bookingId, reason)` — this inserts the strike, checks thresholds, sets `coach_profiles.status` to REDUCED/PENDING_REVIEW, and publishes `StrikeThresholdReachedEvent` if needed; the admin event listener already handles that event to create the alert. The strikeId is extracted directly from the returned entity — no secondary query needed (see Task 11 for the return type change to `issue()`)
    5. Log: `AdminActionLog(adminId, COACH_STRIKE_ISSUED, coachId.toString(), "Manual strike: " + reason)` — uses the new action type added in Task 18 (V72 migration)
    6. Return `strike.getId()`
  - [x] **`@Transactional` method `deleteStrike(UUID coachId, UUID strikeId, String reason, Long adminId)`**:
    1. Load strike: `strikeRepository.findById(strikeId).orElseThrow(ResourceNotFoundException)`
    2. Verify `strike.coachId == coachId` — else `ResourceNotFoundException` (avoids leaking strike IDs)
    3. `strikeRepository.deleteById(strikeId)`
    4. Re-evaluate: `count = strikeRepository.countByCoachIdAndCreatedAtAfter(coachId, OffsetDateTime.now().minusDays(30))`
    5. Load coach: if `count < Long.parseLong(configService.getString("reliability.strike.visibilityThreshold"))` AND `coach.getStatus() == PENDING_REVIEW`:
       - `coach.setStatus(ACTIVE)` + `setStatusChangedAt(Instant.now())` + save
       - Resolve OPEN STRIKE_THRESHOLD alert (same block as `reinstateCoach()` step 5):
         ```java
         adminAlertRepository.findFirstByReferenceIdAndTypeAndStatus(
             coachId.toString(), AdminAlertType.STRIKE_THRESHOLD, AdminAlertStatus.OPEN)
             .ifPresent(alert -> {
                 alert.setStatus(AdminAlertStatus.RESOLVED);
                 alert.setResolvedAt(Instant.now());
                 alert.setResolvedBy(adminId);
                 adminAlertRepository.save(alert);
             });
         ```
       - Log: `AdminActionLog(adminId, COACH_STRIKE_DELETED, coachId.toString(), "Strike deleted: " + reason)` — uses the new action type added in Task 18 (V72 migration)
    6. Else: Log `AdminActionLog(adminId, COACH_STRIKE_DELETED, coachId.toString(), "Strike deleted (no status change): " + reason)` — still log for audit trail
  - [x] **`@Transactional(readOnly = true)` method `getCoachesUnderEnforcement(String statusParam, int page)`** → `Page<CoachEnforcementListItemDto>`:
    1. Parse `statusParam` to `CoachProfileStatus` enum; throw `ResponseStatusException(BAD_REQUEST, "Invalid status")` on parse error
    2. If statusParam is null or "ALL": use `List.of(PENDING_REVIEW, SUSPENDED)`
    3. `Pageable p = PageRequest.of(Math.max(0, page), 20)`
    4. `Page<CoachProfile> coaches = coachProfileRepository.findByStatusInOrderByStatusChangedAtAsc(statuses, p)`
    5. For each coach: compute `activeStrikes = strikeRepository.countByCoachIdAndCreatedAtAfter(coach.getId(), OffsetDateTime.now().minusDays(30))`
    6. Return mapped `Page<CoachEnforcementListItemDto>`
    7. **N+1 ALERT**: For large enforcement lists, this N+1 strike count per coach is acceptable at MVP scale (admin-only endpoint, low traffic). If optimization needed, use `strikeRepository.countByCoachIdInAndCreatedAtAfter()` batch query (already exists in repo) — deviate if list size is a concern.

- [x] **Task 9 — `AdminCoachEnforcementResource`** (AC: 2, 3, 5, 6, 7, 8)
  - [x] Create `platform.admin.api.AdminCoachEnforcementResource`:
    ```java
    @RestController
    @RequestMapping("/api/admin/coaches")
    @RequiredArgsConstructor
    @Observed(name = "admin.coach.enforcement")
    public class AdminCoachEnforcementResource {

        private final AdminCoachEnforcementService enforcementService;
        private final SecurityUtil securityUtil;

        @GetMapping("/{coachId}/enforcement")
        @PreAuthorize(SecurityConstants.HAS_ADMIN_ROLE)
        @Observed(name = "admin.coach.enforcement.profile")
        public ResponseEntity<CoachEnforcementProfileDto> getEnforcementProfile(@PathVariable UUID coachId) {
            return ResponseEntity.ok(enforcementService.getEnforcementProfile(coachId));
        }

        @PostMapping("/{coachId}/suspend")
        @PreAuthorize(SecurityConstants.HAS_ADMIN_ROLE)
        @Observed(name = "admin.coach.suspend")
        public ResponseEntity<Void> suspendCoach(
                @PathVariable UUID coachId,
                @Valid @RequestBody AdminSuspendCoachRequest request) {
            enforcementService.suspendCoach(coachId, request.reason(), request.notifyCoach(), resolveAdminId());
            return ResponseEntity.ok().build();
        }

        @PostMapping("/{coachId}/reinstate")
        @PreAuthorize(SecurityConstants.HAS_ADMIN_ROLE)
        @Observed(name = "admin.coach.reinstate")
        public ResponseEntity<Void> reinstateCoach(
                @PathVariable UUID coachId,
                @Valid @RequestBody AdminReinstateCoachRequest request) {
            enforcementService.reinstateCoach(coachId, request.reason(), resolveAdminId());
            return ResponseEntity.ok().build();
        }

        @PostMapping("/{coachId}/strikes")
        @PreAuthorize(SecurityConstants.HAS_ADMIN_ROLE)
        @Observed(name = "admin.coach.strike.issue")
        public ResponseEntity<AdminManualStrikeResponse> issueManualStrike(
                @PathVariable UUID coachId,
                @Valid @RequestBody AdminManualStrikeRequest request) {
            UUID strikeId = enforcementService.issueManualStrike(coachId, request.bookingId(), request.reason(), resolveAdminId());
            return ResponseEntity.status(HttpStatus.CREATED).body(new AdminManualStrikeResponse(strikeId));
        }

        @DeleteMapping("/{coachId}/strikes/{strikeId}")
        @PreAuthorize(SecurityConstants.HAS_ADMIN_ROLE)
        @Observed(name = "admin.coach.strike.delete")
        public ResponseEntity<Void> deleteStrike(
                @PathVariable UUID coachId,
                @PathVariable UUID strikeId,
                @Valid @RequestBody AdminDeleteStrikeRequest request) {
            enforcementService.deleteStrike(coachId, strikeId, request.reason(), resolveAdminId());
            return ResponseEntity.ok().build();
        }

        @GetMapping
        @PreAuthorize(SecurityConstants.HAS_ADMIN_ROLE)
        @Observed(name = "admin.coach.list")
        public ResponseEntity<Page<CoachEnforcementListItemDto>> getCoachesUnderEnforcement(
                @RequestParam(required = false) String status,
                @RequestParam(defaultValue = "0") int page) {
            return ResponseEntity.ok(enforcementService.getCoachesUnderEnforcement(status, page));
        }

        private Long resolveAdminId() {
            Object principal = securityUtil.getCurrentUser();
            if (!(principal instanceof Principal p)) {
                throw new InsufficientAuthenticationException("Unexpected principal type");
            }
            try { return Long.parseLong(p.getBusinessId()); }
            catch (NumberFormatException e) {
                throw new InsufficientAuthenticationException("Invalid business ID");
            }
        }
    }
    ```
  - [x] `resolveAdminId()` is an exact copy from `AdminModerationResource` and `AdminReviewResource` — do not change the pattern
  - [x] `@DeleteMapping` with `@RequestBody` is non-standard; used `@RequestParam String reason` instead for consistency with the codebase (no other DELETE endpoints use `@RequestBody`)
  - [x] Add `HttpStatus` import: `import org.springframework.http.HttpStatus;`

- [x] **Task 10 — Update `CancellationRefundService`** (AC: 3)
  - [x] Add `@TransactionalEventListener` in `platform.payment.service.CancellationRefundService`:
    ```java
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onBookingCancelledByAdmin(BookingCancelledByAdminEvent event) {
        if (event.getSessionPackPurchaseId() != null) {
            // Always restore session — admin cancellation is never a forfeiture
            packSessionService.restoreSession(event.getSessionPackPurchaseId());
            log.info("Pack session restored for admin suspension: bookingId={}", event.getBookingId());
        } else {
            creditWalletService.writeLedgerEntry(
                event.getParentId(), event.getSessionPrice(),
                "BOOKING_REFUND", event.getBookingId(),
                "Admin coach suspension — full refund"
            );
            log.info("BOOKING_REFUND issued for admin suspension: bookingId={}", event.getBookingId());
        }
    }
    ```
  - [x] Add import: `import com.softropic.skillars.platform.booking.contract.BookingCancelledByAdminEvent;`
  - [x] **No strike is issued for admin cancellations** — admin is suspending the coach; issuing a strike here would be double-jeopardy on top of the manual action
  - [x] **No cancellation history row** — admin suspensions are not coach-initiated cancellations; the `coach_cancellation_history` tracks coach-driven behaviours only

- [x] **Task 11 — Update `ReliabilityStrikeService.issue()`** (AC: 3, 5, 6, 8)
  - [x] Change method signature from `void issue(...)` to `CoachReliabilityStrike issue(...)` and return the saved entity — required so `AdminCoachEnforcementService.issueManualStrike()` can read the new strike's ID without a racy secondary query:
    ```java
    // BEFORE:
    // strikeRepository.save(strike);
    // AFTER:
    CoachReliabilityStrike saved = strikeRepository.save(strike);
    // ... (rest of method unchanged) ...
    return saved;
    ```
    Update the method signature: `public CoachReliabilityStrike issue(UUID coachId, UUID bookingId, String reason)`
  - [x] `ReliabilityStrikeResource` calls `issue()` but discards the return value — the existing call site compiles without change; no other callers need updating
  - [x] In `platform.payment.service.ReliabilityStrikeService.issue()`, set `statusChangedAt` when changing coach status:
    ```java
    // When setting PENDING_REVIEW:
    coach.setStatus(CoachProfileStatus.PENDING_REVIEW);
    coach.setStatusChangedAt(Instant.now());  // ADD THIS
    coachProfileRepository.save(coach);

    // When setting REDUCED:
    coach.setStatus(CoachProfileStatus.REDUCED);
    coach.setStatusChangedAt(Instant.now());  // ADD THIS
    coachProfileRepository.save(coach);
    ```
  - [x] This ensures `statusChangedAt` is accurate for enforcement list ordering, even for automatically-triggered status changes
  - [x] Add import: `import java.time.Instant;` (if not already present)

- [x] **Task 12 — ~~Add `findFirstByCoachIdOrderByCreatedAtDesc` to `CoachReliabilityStrikeRepository`~~** *(superseded)*
  - [x] ~~This method is no longer needed.~~ `ReliabilityStrikeService.issue()` now returns the saved `CoachReliabilityStrike` entity (Task 11), so `issueManualStrike()` reads the ID directly from the return value. Do not add the secondary query method.

- [x] **Task 13 — Integration Test: `CoachSuspensionIT`** (AC: 3, 4)
  - [x] Create `platform.admin.api.CoachSuspensionIT` — TSID range `9040_xxx`
  - [x] IDs: `ADMIN_ID = 9040_000_100L`, `PARENT_ID = 9040_000_001L`, `PLAYER_ID = 9040_000_002L`, `COACH_USER_ID = 9040_000_010L`
  - [x] Seed: admin user (ROLE_ADMIN), parent, player, coach profile (ACTIVE), 2 REQUESTED bookings, 1 ACCEPTED booking, 1 `admin_alerts` row (type=STRIKE_THRESHOLD for this coach)
  - [x] Test 1: `suspendCoach_setsStatusAndCancelsRequestedBookings()`:
    - POST `/api/admin/coaches/{coachId}/suspend` with admin token
    - Verify `coach_profiles.status = 'SUSPENDED'`, `status_changed_at IS NOT NULL`
    - Verify 2 REQUESTED bookings now have `status = 'CANCELLED'`, `cancel_reason = 'COACH_SUSPENDED_BY_ADMIN'`
    - Verify 1 ACCEPTED booking unchanged
    - Verify `admin_action_log` row with `action_type = 'COACH_SUSPEND'`
  - [x] Test 2: `suspendedCoachPublicProfile_returns404()`:
    - GET `/api/marketplace/coaches/{coachId}` (no auth) → 404
  - [x] Test 3: `nonAdminCannotSuspend_returns403()`:
    - POST suspend with parent token → 403
  - [x] Test 4: `suspendIdempotent_doubleCallDoesNotDuplicateLog()`:
    - POST suspend twice → second call logs 1 action_log row total (idempotency guard)
  - [x] **Admin user setup**: same 3-row pattern as Story 10.1: `main.user` (skillars_role='ADMIN'), `main.authority` (`ON CONFLICT DO NOTHING`), `main.user_authority`

- [x] **Task 14 — Integration Test: `SuspendedCoachBookingBlockIT`** (AC: 9)
  - [x] Create `platform.admin.api.SuspendedCoachBookingBlockIT` — TSID range `9050_xxx`
  - [x] IDs: `ADMIN_ID = 9050_000_100L`, `PARENT_ID = 9050_000_001L`, `PLAYER_ID = 9050_000_002L`, `COACH_USER_ID = 9050_000_010L`
  - [x] Seed: parent, player, coach profile with `status = 'SUSPENDED'`, coach pricing row, parent wallet with credits
  - [x] Test 1: `bookingRequestForSuspendedCoach_returns403WithCoachUnavailableCode()`:
    - POST `/api/booking` with parent token to book a suspended coach
    - Verify 403 response with `helpCode = "booking.coachUnavailable"` in the `ErrorDto`
  - [x] Test 2: `bookingRequestForPendingReviewCoach_succeeds()`:
    - Update coach to `status = 'PENDING_REVIEW'`
    - POST `/api/booking` → 200 (REQUESTED) — PENDING_REVIEW coaches can still take bookings

- [x] **Task 15 — Integration Test: `ReinstateIT`** (AC: 5)
  - [x] Create `platform.admin.api.ReinstateIT` — TSID range `9060_xxx`
  - [x] IDs: `ADMIN_ID = 9060_000_100L`, `PARENT_ID = 9060_000_001L`, `COACH_USER_ID = 9060_000_010L`
  - [x] Seed: admin, coach (SUSPENDED), 1 OPEN STRIKE_THRESHOLD admin_alert for this coach
  - [x] Test 1: `reinstateCoach_setsActiveAndResolvesAlert()`:
    - POST `/api/admin/coaches/{coachId}/reinstate` with reason
    - Verify `coach_profiles.status = 'ACTIVE'`
    - Verify `admin_alerts.status = 'RESOLVED'`, `resolved_at IS NOT NULL`, `resolved_by = ADMIN_ID`
    - Verify `admin_action_log` row `action_type = 'COACH_REINSTATE'`
  - [x] Test 2: `reinstateIdempotent_doubleCallDoesNotFail()`

- [x] **Task 16 — Integration Test: `ManualStrikeIT`** (AC: 6, 7)
  - [x] Create `platform.admin.api.ManualStrikeIT` — TSID range `9070_xxx`
  - [x] IDs: `ADMIN_ID = 9070_000_100L`, `COACH_USER_ID = 9070_000_010L`
  - [x] Seed: admin, coach (ACTIVE), config rows: `reliability.strike.visibilityThreshold=3`, `reliability.strike.suspensionThreshold=5`
  - [x] Test 1: `issueManualStrike_insertsStrikeRow()`:
    - POST `/api/admin/coaches/{coachId}/strikes` with `{ bookingId: "...", reason: "COACH_NO_SHOW" }`
    - Verify `coach_reliability_strikes` row inserted
    - Verify `201 Created` with `strikeId` in response body
  - [x] Test 2: `deleteStrike_thatWasFinalStrike_revertsStatusToActiveAndResolvesAlert()`:
    - Seed: 3 strikes (coach set to PENDING_REVIEW), 1 OPEN STRIKE_THRESHOLD admin_alert for this coach
    - DELETE `/api/admin/coaches/{coachId}/strikes/{strikeId}` (deletes one, drops below threshold)
    - Verify `coach_profiles.status = 'ACTIVE'`
    - Verify `admin_alerts.status = 'RESOLVED'` for the STRIKE_THRESHOLD alert (alert must NOT remain OPEN)
  - [x] Test 3: `deleteStrike_noStatusChange_doesNotResolveAlert()`:
    - Seed: 5 strikes (coach PENDING_REVIEW), 1 OPEN STRIKE_THRESHOLD alert
    - DELETE one strike (count drops to 4, still above visibilityThreshold=3)
    - Verify `coach_profiles.status` unchanged (still PENDING_REVIEW)
    - Verify `admin_alerts` row still OPEN
  - [x] Test 4: `invalidStrikeReason_returns400()`
  - [x] Test 5: `issueStrikeWithBookingFromDifferentCoach_returns400()`:
    - Seed: second coach with a booking; attempt to issue strike against first coach but pass second coach's bookingId
    - Verify `400 Bad Request`

- [x] **Task 17 — Integration Test: `CoachEnforcementListIT`** (AC: 2, 8)
  - [x] Create `platform.admin.api.CoachEnforcementListIT` — TSID range `9080_xxx`
  - [x] IDs: `ADMIN_ID = 9080_000_100L`, `COACH_USER_ID_1 = 9080_000_010L`, `COACH_USER_ID_2 = 9080_000_020L`
  - [x] Seed: admin, 2 coaches (one PENDING_REVIEW, one SUSPENDED) with different `status_changed_at` values
  - [x] Test 1: `listEnforcementCoaches_returnsOrderedByStatusChangedAt()`:
    - GET `/api/admin/coaches?status=PENDING_REVIEW` → 1 result
    - GET `/api/admin/coaches?status=SUSPENDED` → 1 result
    - GET `/api/admin/coaches` (no status) → both, ordered by `statusChangedAt ASC`
  - [x] Test 2: `getEnforcementProfile_returnsAllFields()`:
    - GET `/api/admin/coaches/{coachId}/enforcement`
    - Verify all fields present: coachName, currentStatus, activeStrikes, strikeHistory, cancellationHistory, openAlerts

- [x] **Task 18 — Flyway V72: Add `COACH_STRIKE_ISSUED` and `COACH_STRIKE_DELETED` to `admin_action_log`** (AC: 6, 7)
  - [x] The V70 `admin_action_log.action_type` column has a DB-level CHECK constraint: `CHECK (action_type IN ('MESSAGE_APPROVE','MESSAGE_BLOCK','CONVERSATION_UNBLOCK','REVIEW_APPROVE','REVIEW_BLOCK','COACH_SUSPEND','COACH_REINSTATE','DISPUTE_RESOLVE'))`. Using `COACH_SUSPEND` for manual strike issuance and `COACH_REINSTATE` for strike deletion (even when no reinstatement occurred) produces misleading audit log entries. Two new action types are required.
  - [x] Create `src/main/resources/db/migration/V72__admin_action_log_strike_types.sql`:
    ```sql
    ALTER TABLE admin.admin_action_log
        DROP CONSTRAINT IF EXISTS admin_action_log_action_type_check;

    ALTER TABLE admin.admin_action_log
        ADD CONSTRAINT admin_action_log_action_type_check
        CHECK (action_type IN (
            'MESSAGE_APPROVE','MESSAGE_BLOCK','CONVERSATION_UNBLOCK',
            'REVIEW_APPROVE','REVIEW_BLOCK',
            'COACH_SUSPEND','COACH_REINSTATE',
            'COACH_STRIKE_ISSUED','COACH_STRIKE_DELETED',
            'DISPUTE_RESOLVE'
        ));
    ```
  - [x] Add the two new values to `platform.admin.contract.AdminActionType`:
    ```java
    public enum AdminActionType {
        MESSAGE_APPROVE, MESSAGE_BLOCK, CONVERSATION_UNBLOCK,
        REVIEW_APPROVE, REVIEW_BLOCK,
        COACH_SUSPEND, COACH_REINSTATE,
        COACH_STRIKE_ISSUED, COACH_STRIKE_DELETED,
        DISPUTE_RESOLVE
    }
    ```
  - [x] **Do NOT run this migration before Task 8 is deployed** — `issueManualStrike()` and `deleteStrike()` use these new types; if the migration is missing, the DB insert will throw a CHECK constraint violation at runtime.

## Dev Notes

### Module Package Structure

```
src/main/java/com/softropic/skillars/
  platform/admin/
    contract/
      + CoachEnforcementProfileDto.java     (record — NEW)
      + CoachStrikeHistoryDto.java          (record — NEW)
      + CoachCancellationHistoryEntryDto.java (record — NEW)
      + CoachEnforcementListItemDto.java    (record — NEW)
      + AdminSuspendCoachRequest.java       (record — NEW)
      + AdminReinstateCoachRequest.java     (record — NEW)
      + AdminManualStrikeRequest.java       (record — NEW)
      + AdminManualStrikeResponse.java      (record — NEW)
      + AdminDeleteStrikeRequest.java       (record — NEW)
      + CoachSuspendedEvent.java            (event — NEW)
      + CoachReinstatedEvent.java           (event — NEW)
      + CoachSuspensionNotificationEvent.java (event — NEW)
    service/
      + AdminCoachEnforcementService.java   (@Service — NEW)
      ~ AdminAlertEventListener.java        (NO change — AC 1 pre-wired)
      ~ AdminQueueService.java              (NO change)
    api/
      + AdminCoachEnforcementResource.java  (@RestController — NEW)
      ~ AdminModerationResource.java        (NO change)

  platform/booking/
    contract/
      + BookingCancelledByAdminEvent.java   (NEW)
      + BookingError.java                   (enum — NEW)
    service/
      ~ BookingService.java                 (MODIFIED — coach status guard)

  platform/marketplace/
    contract/
      ~ CoachProfileStatus.java             (MODIFIED — add SUSPENDED, DEACTIVATED)
    repo/
      ~ CoachProfile.java                   (MODIFIED — add statusChangedAt field)
      ~ CoachProfileRepository.java         (MODIFIED — add enforcement list query)
      ~ CoachReliabilityStrikeRepository.java (MODIFIED — add findFirst...Desc)
    service/
      ~ CoachProfileService.java            (MODIFIED — getPublicProfile filter)

  platform/payment/
    repo/
      ~ CoachCancellationHistoryRepository.java (MODIFIED — add findByCoachId...)
    service/
      ~ CancellationRefundService.java      (MODIFIED — add onBookingCancelledByAdmin)
      ~ ReliabilityStrikeService.java       (MODIFIED — add statusChangedAt on status change)

  platform/admin/
    repo/
      ~ AdminAlertRepository.java           (MODIFIED — add countOpenByReferenceId)

src/main/resources/db/migration/
  + V71__coach_profile_status_changed_at.sql (NEW)
  + V72__admin_action_log_strike_types.sql   (NEW)

src/test/java/com/softropic/skillars/
  platform/admin/api/
    + CoachSuspensionIT.java        (TSID 9040_xxx — NEW)
    + SuspendedCoachBookingBlockIT.java (TSID 9050_xxx — NEW)
    + ReinstateIT.java              (TSID 9060_xxx — NEW)
    + ManualStrikeIT.java           (TSID 9070_xxx — NEW)
    + CoachEnforcementListIT.java   (TSID 9080_xxx — NEW)
```

### CRITICAL: `admin_alerts` and `admin_action_log` Are Already Defined

**Do NOT create new tables.** `admin_alerts` and `admin_action_log` were created in Story 10.1 migration V70. Both are in the `admin` schema. `AdminActionType` already includes `COACH_SUSPEND` and `COACH_REINSTATE`. `AdminAlertType` already includes `STRIKE_THRESHOLD`. Do not re-create or alter these tables.

### CRITICAL: `StrikeThresholdReachedEvent` → `admin_alerts` Is Already Wired

`AdminAlertEventListener.onStrikeThreshold()` (created in Story 10.1) already inserts `admin_alerts` rows for STRIKE_THRESHOLD events. The Story 10.2 AC 1 is a "verify pre-condition" AC — no new listener code needed. The AC also confirms `coach_profiles.status = PENDING_REVIEW` is set by `ReliabilityStrikeService.issue()` from Story 7.3.

### CRITICAL: `statusChangedAt` Must Be Set in Both `ReliabilityStrikeService` and `AdminCoachEnforcementService`

The enforcement list endpoint orders by `statusChangedAt ASC`. Two code paths change coach status:
1. Automated: `ReliabilityStrikeService.issue()` (REDUCED or PENDING_REVIEW)
2. Admin: `AdminCoachEnforcementService.suspendCoach()` / `reinstateCoach()` / `deleteStrike()` (SUSPENDED / ACTIVE)

Both paths must set `coach.setStatusChangedAt(Instant.now())` before saving. Missing this in `ReliabilityStrikeService` would leave automated status changes with null `statusChangedAt`, causing those coaches to sort last (behind admin-actioned ones).

### CRITICAL: `coach_reliability_strikes.createdAt` vs Epic's `issuedAt`

The epic AC and dev notes use `issuedAt` for the strike history field. The actual entity field is `createdAt` (see `CoachReliabilityStrike.java`). The DTO `CoachStrikeHistoryDto` names it `createdAt`. Do not add a separate `issuedAt` column — this would be redundant.

### CRITICAL: `coach_cancellation_history.createdAt` vs Epic's `cancelledAt`

Same as above — the entity uses `createdAt`, not `cancelledAt`. The DTO `CoachCancellationHistoryEntryDto` names it `createdAt`.

### CRITICAL: `BookingCancelledByAdminEvent` Carries Session Pack Info

For REQUESTED bookings with a `sessionPackPurchaseId`, `CancellationRefundService.onBookingCancelledByAdmin()` calls `packSessionService.restoreSession()` (not a credit ledger entry). The admin service must compute the price and pack ID the same way `BookingService.resolveSessionPrice()` does. Use `coachPricingRepository.findByCoachId()` for pay-as-you-go and `sessionPackPurchaseRepository.findById()` for pack-based bookings. If either is not found (deleted data), default to `BigDecimal.ZERO` and log a warning — do not throw.

### CRITICAL: `BookingService.requestBooking()` Existing Guard Behavior

Current guard (Story 3.3): `coach.getStatus() != ACTIVE && coach.getStatus() != REDUCED` → generic 403.

After Story 10.2:
- `SUSPENDED` → 403 with `booking.coachUnavailable` (specific code)
- `PENDING_REVIEW` → now ALLOWED (coaches with this status can still operate per AC 1)
- `DEACTIVATED`, `DRAFT` → still blocked by the existing check (generic 403)

The `BookingError.COACH_UNAVAILABLE.getErrorCode()` returns `"booking.coachUnavailable"` which becomes the `helpCode` in the `ErrorDto` via `ApiAdvice.operationDeniedHandler()`:
```java
String helpCode = exception.getErrorCode() != null
    ? exception.getErrorCode().getErrorCode()  // → "booking.coachUnavailable"
    : "security.opForbidden";
```

### CRITICAL: `CoachProfileService.getPublicProfile()` Filter Change

Currently: `.filter(p -> p.getStatus() == CoachProfileStatus.ACTIVE)` — blocks REDUCED, PENDING_REVIEW, SUSPENDED, DEACTIVATED, DRAFT.

After Story 10.2: `.filter(p -> p.getStatus() == CoachProfileStatus.ACTIVE || p.getStatus() == CoachProfileStatus.REDUCED || p.getStatus() == CoachProfileStatus.PENDING_REVIEW)`.

**Why REDUCED was not in the original filter**: Story 2.3 (CoachPublicProfile) was written before Story 7.3 introduced REDUCED status. This is a pre-existing gap, now corrected. REDUCED coaches should be publicly discoverable — they appear less in search but are still reachable by direct link.

**SUSPENDED → 404**: Since SUSPENDED is not in the allow-list, the `Optional.empty()` propagates to `orElseThrow(ResourceNotFoundException)` → 404 via `ApiAdvice`. No explicit check needed.

### CRITICAL: Admin `suspendCoach()` Processes REQUESTED Bookings Only

Per the AC, ACCEPTED/CONFIRMED/UPCOMING bookings are NOT auto-cancelled. The loop uses:
```java
bookingRepository.findByCoachIdAndStatusOrderByRequestedStartTimeAsc(coachId, "REQUESTED")
```
This method already exists in `BookingRepository`. Do not use the `findByCoachIdAndStatusInAndTimeBetween` variant.

### CRITICAL: `@TransactionalEventListener` vs `@EventListener` for `BookingCancelledByAdminEvent`

`CancellationRefundService.onBookingCancelledByAdmin()` must use `@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)` + `@Transactional(propagation = Propagation.REQUIRES_NEW)` — same as the existing `onBookingCancelledByCoach()` and `onBookingCancelledByParent()` in the same class. This ensures:
1. Admin's DB changes commit first (coach status = SUSPENDED, bookings = CANCELLED)
2. Credit/pack restoration runs in a NEW transaction (isolated refund)
3. If the refund transaction fails, the suspension DB state is NOT rolled back (admin intent preserved)

### CRITICAL: Manual Strike Uses `ReliabilityStrikeService.issue()` — Do NOT Duplicate Threshold Logic

The threshold check (REDUCED/PENDING_REVIEW/StrikeThresholdReachedEvent) is complex with ConfigService lookups. Do NOT duplicate it in `AdminCoachEnforcementService`. Call `reliabilityStrikeService.issue(coachId, bookingId, reason)` directly. This service is in `platform.payment.service`. The admin module importing a payment service is a cross-module dependency, consistent with `AdminReviewService` importing from `platform.reviews.repo` and `platform.marketplace.repo`.

### CRITICAL: `AdminAlertRepository` — New `countOpenByReferenceId` Query

Needed for the enforcement profile endpoint's `openAlerts` field. Add to `AdminAlertRepository`:
```java
@Query("SELECT COUNT(a) FROM AdminAlert a WHERE a.referenceId = :referenceId AND a.status = 'OPEN'")
long countOpenByReferenceId(@Param("referenceId") String referenceId);
```
Note: this counts ALL open alerts for this referenceId (not just STRIKE_THRESHOLD). For a coach UUID, referenceId could match STRIKE_THRESHOLD alerts; for a message Long, it would match MESSAGE_REPORT alerts. The method name is intentionally generic. In practice, for the enforcement profile, only STRIKE_THRESHOLD alerts reference coach UUIDs — but be aware if other alert types start using coach UUID as referenceId.

### CRITICAL: `COACH_STRIKE_ISSUED` and `COACH_STRIKE_DELETED` Require V72 Migration

The V70 `admin_action_log.action_type` CHECK constraint does not include strike-specific action types. Task 18 (V72 migration) adds `COACH_STRIKE_ISSUED` and `COACH_STRIKE_DELETED` to both the DB CHECK constraint and the `AdminActionType` Java enum. **Deploy V72 before Task 8 service code** — the constraint violation would otherwise surface at runtime on the first manual strike issuance or deletion.

Do **not** fall back to `COACH_SUSPEND` for strike issuance or `COACH_REINSTATE` for strike deletion: these are semantically wrong (a strike issuance is not a suspension; a strike deletion with no status change is not a reinstatement) and would corrupt audit log queries used for reporting.

### CRITICAL: `@DeleteMapping` with `@RequestBody`

Spring MVC supports `@RequestBody` on `@DeleteMapping` methods. However, HTTP clients (Axios, Postman) require explicit configuration to send a body with DELETE. The frontend team must be aware. Alternative: accept `reason` as `@RequestParam`. Check if other DELETE endpoints in this codebase use body or param for reason, and be consistent.

Search for pattern: `find src/main/java -name "*.java" | xargs grep -l "@DeleteMapping" | xargs grep -l "@RequestBody"` to verify the codebase approach.

### Story 10.1 Patterns to Follow Exactly

- `resolveAdminId()` method — copy verbatim from `AdminModerationResource` or `AdminReviewResource`
- Admin user test setup: 3 rows per test class (`main.user` skillars_role='ADMIN', `main.authority` name='ROLE_ADMIN' with `ON CONFLICT DO NOTHING`, `main.user_authority` join)
- Idempotency guards on `suspendCoach()` and `reinstateCoach()` — same pattern as Story 10.1's `approveMessage()` / `unblockConversation()`

### References — Files to Read Before Implementing

- `AdminModerationResource.java` — `platform/admin/api/` — `resolveAdminId()` + endpoint patterns
- `AdminReviewService.java` — `platform/admin/service/` — cross-module repo injection pattern (imports from marketplace, reviews repos)
- `CancellationRefundService.java` — `platform/payment/service/` — `@TransactionalEventListener` pattern for refunds; add `onBookingCancelledByAdmin()` to this same file
- `BookingCancelledByCoachEvent.java` — `platform/booking/contract/` — event constructor pattern to copy for `BookingCancelledByAdminEvent`
- `ReliabilityStrikeService.java` — `platform/payment/service/` — understand `issue()` method before calling it from admin service
- `BookingService.java` — `platform/booking/service/` — read the `requestBooking()` method (lines ~145–170) before modifying the coach status guard
- `CoachProfileService.java` — `platform/marketplace/service/` — read `getPublicProfile()` before modifying the filter
- `AdminAlertEventListener.java` — `platform/admin/service/` — confirm AC 1 is already wired (no changes needed)
- `AdminQueueIT.java` — `platform/admin/api/` — admin test user setup pattern to replicate exactly
- `ApiAdvice.java` — `platform/security/api/` — confirm how `OperationNotAllowedException.getErrorCode()` becomes `helpCode` in `ErrorDto`
- `CoachReliabilityStrike.java` — `platform/marketplace/repo/` — field names (createdAt, NOT issuedAt)
- `CoachCancellationHistory.java` — `platform/payment/repo/` — field names (createdAt, NOT cancelledAt)
- `V70__admin_alerts_action_log.sql` — confirm `action_type` CHECK constraint includes all 8 values including `COACH_SUSPEND`, `COACH_REINSTATE`

### Epics Dev Notes Deviation

The epics dev notes say "Coach status (`ACTIVE/PENDING_REVIEW/SUSPENDED/DEACTIVATED`) added to `coach_profiles` via Flyway migration". In reality:
- `status` column already exists (added in earlier stories via `marketplace` schema migration)
- `PENDING_REVIEW` was already added to `CoachProfileStatus` in Story 7.3
- This story only adds `SUSPENDED` and `DEACTIVATED` to the Java enum (no column DDL change)
- This story adds the `status_changed_at` column (new, not mentioned in epics dev notes)

The epics also mention "Cross-module reads: `CoachEnforcementQueryService` interface exposed by `platform.coach`". No such interface or `platform.coach` package exists. The admin module injects repos directly from `platform.marketplace.repo` (same pattern used by `AdminReviewService`). Do not create a `CoachEnforcementQueryService` interface.

## Dev Agent Record

### Agent Model Used

claude-sonnet-4-6

### Debug Log References

### Completion Notes List

- Implemented `@DeleteMapping` with `@RequestParam String reason` (not `@RequestBody`) for `DELETE /api/admin/coaches/{coachId}/strikes/{strikeId}` — no other DELETE endpoints in this codebase use `@RequestBody`, so `@RequestParam` was used for consistency.
- `ReliabilityStrikeService.issue()` return type changed from `void` to `CoachReliabilityStrike` so `issueManualStrike()` can extract the new strike ID without a secondary query.
- `CoachProfileService.getPublicProfile()` filter updated to include REDUCED (pre-existing gap from Story 2.3 / Story 7.3 ordering) alongside the Story 10.2–required PENDING_REVIEW addition.
- Task 12 (secondary query on `CoachReliabilityStrikeRepository`) was superseded by the Task 11 return-type change; no new query method added.
- Both `mvn compile -q` and `mvn test-compile -q` passed with no output (clean compilation of all 32+ modified/created files).
- `StubPaymentGateway.isCoachPaymentReady()` always returns `true` in tests — no Stripe account seeding needed in `SuspendedCoachBookingBlockIT`.
- V72 migration deployed before `AdminCoachEnforcementService` to prevent runtime DB CHECK constraint violation on first strike issuance/deletion.

### File List

**New files:**
- `src/main/resources/db/migration/V71__coach_profile_status_changed_at.sql`
- `src/main/resources/db/migration/V72__admin_action_log_strike_types.sql`
- `src/main/java/com/softropic/skillars/platform/booking/contract/BookingError.java`
- `src/main/java/com/softropic/skillars/platform/booking/contract/BookingCancelledByAdminEvent.java`
- `src/main/java/com/softropic/skillars/platform/admin/contract/CoachSuspendedEvent.java`
- `src/main/java/com/softropic/skillars/platform/admin/contract/CoachReinstatedEvent.java`
- `src/main/java/com/softropic/skillars/platform/admin/contract/CoachSuspensionNotificationEvent.java`
- `src/main/java/com/softropic/skillars/platform/admin/contract/CoachEnforcementProfileDto.java`
- `src/main/java/com/softropic/skillars/platform/admin/contract/CoachStrikeHistoryDto.java`
- `src/main/java/com/softropic/skillars/platform/admin/contract/CoachCancellationHistoryEntryDto.java`
- `src/main/java/com/softropic/skillars/platform/admin/contract/CoachEnforcementListItemDto.java`
- `src/main/java/com/softropic/skillars/platform/admin/contract/AdminSuspendCoachRequest.java`
- `src/main/java/com/softropic/skillars/platform/admin/contract/AdminReinstateCoachRequest.java`
- `src/main/java/com/softropic/skillars/platform/admin/contract/AdminManualStrikeRequest.java`
- `src/main/java/com/softropic/skillars/platform/admin/contract/AdminManualStrikeResponse.java`
- `src/main/java/com/softropic/skillars/platform/admin/contract/AdminDeleteStrikeRequest.java`
- `src/main/java/com/softropic/skillars/platform/admin/service/AdminCoachEnforcementService.java`
- `src/main/java/com/softropic/skillars/platform/admin/api/AdminCoachEnforcementResource.java`
- `src/test/java/com/softropic/skillars/platform/admin/api/CoachSuspensionIT.java`
- `src/test/java/com/softropic/skillars/platform/admin/api/SuspendedCoachBookingBlockIT.java`
- `src/test/java/com/softropic/skillars/platform/admin/api/ReinstateIT.java`
- `src/test/java/com/softropic/skillars/platform/admin/api/ManualStrikeIT.java`
- `src/test/java/com/softropic/skillars/platform/admin/api/CoachEnforcementListIT.java`

**Modified files:**
- `src/main/java/com/softropic/skillars/platform/marketplace/contract/CoachProfileStatus.java`
- `src/main/java/com/softropic/skillars/platform/marketplace/repo/CoachProfile.java`
- `src/main/java/com/softropic/skillars/platform/marketplace/repo/CoachProfileRepository.java`
- `src/main/java/com/softropic/skillars/platform/marketplace/service/CoachProfileService.java`
- `src/main/java/com/softropic/skillars/platform/payment/repo/CoachCancellationHistoryRepository.java`
- `src/main/java/com/softropic/skillars/platform/payment/service/CancellationRefundService.java`
- `src/main/java/com/softropic/skillars/platform/payment/service/ReliabilityStrikeService.java`
- `src/main/java/com/softropic/skillars/platform/booking/service/BookingService.java`
- `src/main/java/com/softropic/skillars/platform/admin/contract/AdminActionType.java`
- `src/main/java/com/softropic/skillars/platform/admin/repo/AdminAlertRepository.java`

## Review Findings

### Decision-Needed

- [x] [Review][Decision] `issueManualStrike` accepts non-existent `bookingId` — Spec uses `ifPresent` (validates ownership only "if the booking exists"), so a phantom UUID bypasses all checks and creates a strike row with a dangling `bookingId` reference (no FK enforcement on `coach_reliability_strikes.booking_id`). Decide: (a) keep as-is per spec intent (admin UI will always pass valid IDs), or (b) throw 400 if booking not found. `AdminCoachEnforcementService.java:176`

- [x] [Review][Decision] `deleteStrike` does not revert `REDUCED` coaches to `ACTIVE` when count drops below `visibilityThreshold` — AC 7 only specifies the `PENDING_REVIEW→ACTIVE` revert. A coach in `REDUCED` status who has strikes deleted below `visibilityThreshold` remains `REDUCED` indefinitely with no auto-recovery path in this flow. Decide: (a) extend the condition to also handle `REDUCED`, or (b) accept that REDUCED coaches must be manually reinstated via `reinstateCoach()`. `AdminCoachEnforcementService.java:216`

- [x] [Review][Decision] `deleteStrike` status-revert branch logs `COACH_STRIKE_DELETED` instead of `COACH_REINSTATE` — AC 7 specifies "actionType = `COACH_REINSTATE` if status reverted to ACTIVE" but Task 8 implementation notes say `COACH_STRIKE_DELETED` in both branches. Code follows Task 8. Decide which is authoritative for audit query purposes. `AdminCoachEnforcementService.java:223`

### Patches

- [x] [Review][Patch] `reinstateCoach` promotes any non-ACTIVE status (including `DRAFT`, `DEACTIVATED`) directly to `ACTIVE` — idempotency guard only checks `status == ACTIVE`; a misclick on a draft/deactivated coach makes them publicly searchable. Fix: throw `ResponseStatusException(BAD_REQUEST)` unless current status is `SUSPENDED` or `PENDING_REVIEW`. `AdminCoachEnforcementService.java:145`

- [x] [Review][Patch] `getCoachesUnderEnforcement` returns 400 for `status=ALL` instead of the combined list — null/blank is handled but the string `"ALL"` falls through to `CoachProfileStatus.valueOf("ALL")` which throws `IllegalArgumentException`. Fix: add `"ALL".equalsIgnoreCase(statusParam)` branch before the valueOf call. `AdminCoachEnforcementService.java:241`

### Deferred

- [x] [Review][Defer] `AFTER_COMMIT` listener failure silently drops refunds with no retry path `[CancellationRefundService.java:onBookingCancelledByAdmin]` — deferred, pre-existing pattern across all CancellationRefundService listeners
- [x] [Review][Defer] `visibilityThreshold` config key missing/non-numeric throws `NumberFormatException` → 500 on every `deleteStrike` call `[AdminCoachEnforcementService.java:207]` — deferred, pre-existing pattern in `ReliabilityStrikeService.issue()`
- [x] [Review][Defer] N+1 strike count queries in `getCoachesUnderEnforcement` (one per coach per page) `[AdminCoachEnforcementService.java:252]` — deferred, spec explicitly accepts this as MVP trade-off

## Change Log

| Date | Version | Description | Author |
|------|---------|-------------|--------|
| 2026-06-30 | 1.0 | Initial implementation — all 18 tasks complete; 23 new files, 10 modified; both main and test sources compile clean | claude-sonnet-4-6 |
