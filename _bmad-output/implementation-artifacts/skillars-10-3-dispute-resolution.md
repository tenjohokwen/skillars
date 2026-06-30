# Story 10.3: Dispute Resolution

Status: done

## Story

As a parent or player,
I want to raise a formal dispute when a session did not go as expected and I believe I am owed a refund,
so that I have a fair resolution path beyond the standard cancellation policy.

## Acceptance Criteria

1. **Given** a parent or player wants to raise a dispute
   **When** `POST /api/disputes` is called with `{ "bookingId": "...", "reason": "COACH_NO_SHOW" | "SESSION_QUALITY" | "SAFETY_CONCERN" | "UNAUTHORISED_CHARGE" | "OTHER", "details": "..." }`
   **Then** eligibility is checked: booking must exist, its `status IN ('COMPLETED', 'CANCELLED', 'CANCELLED_PARENT', 'CANCELLED_COACH', 'NO_SHOW_PLAYER', 'NO_SHOW_COACH')` AND `(booking.parentId == authenticatedUserId OR booking.playerId == authenticatedUserId)` — `403` with `ErrorDto.helpCode = "disputes.notEligible"` if not met
   **And** a dispute can only be raised within `disputes.submissionWindowDays` (ConfigService default 14) days of `booking.updatedAt` (the proxy for completedAt/cancelledAt since no dedicated column exists) — `403` with `ErrorDto.helpCode = "disputes.windowExpired"` if outside the window
   **And** only one open dispute per booking — `409` if a dispute with `status NOT IN ('RESOLVED', 'DISMISSED')` already exists for this booking
   **And** a row is inserted into `admin.disputes` (see Task 1 for schema)
   **And** a `DisputeRaisedEvent(disputeId, bookingId, raisedBy, reason)` is published
   **And** `AdminAlertEventListener.onDisputeRaised()` inserts an `admin_alerts` row: `type = DISPUTE_RAISED`, `referenceId = bookingId.toString()`, `referenceType = BOOKING`
   **And** `201 Created` with `{ "disputeId": "..." }`

2. **Given** a parent or player views their own dispute
   **When** `GET /api/disputes/{disputeId}` is called
   **Then** `@PreAuthorize` parent or player role required
   **And** service verifies `dispute.raisedBy == authenticatedUserId` — throw `OperationNotAllowedException` (→ 403) on mismatch
   **And** the response includes: `disputeId`, `bookingId`, `reason`, `details`, `status`, `resolution` (nullable), `resolutionNote` (nullable), `createdAt`
   **And** admin-only fields (`resolvedAt`, `resolvedBy`) are NOT included

3. **Given** an admin views the full dispute record
   **When** `GET /api/admin/disputes/{disputeId}` is called
   **Then** `@PreAuthorize` admin role required
   **And** the full record is returned including:
   - `dispute`: all fields including `disputeId`, `bookingId`, `raisedBy`, `raisedByRole`, `reason`, `details`, `status`, `resolution`, `resolutionNote`, `createdAt`, `resolvedAt`, `resolvedBy`
   - `booking`: `coachId`, `coachName` (from `coach_profiles.displayName`), `sessionDate` (`booking.requestedStartTime`), `status`
   - `paymentRecord`: `creditDebited` and `stripeCharged` from `booking_payments` (nullable if no payment record exists — e.g., pack-based sessions with zero Stripe charge)
   - `sessionPrice`: `paymentRecord.creditDebited + paymentRecord.stripeCharged` (the total parent paid)
   - `cancellationHistory`: all rows from `coach_cancellation_history` where `bookingId` matches (may be empty)

4. **Given** an admin resolves a dispute with full credit
   **When** `POST /api/admin/disputes/{disputeId}/resolve` is called with `{ "resolution": "FULL_CREDIT", "resolutionNote": "..." }`
   **Then** `disputes.status = RESOLVED`, `resolution = FULL_CREDIT`, `resolvedAt = now()`, `resolvedBy = adminId`
   **And** the full session price (`creditDebited + stripeCharged`) is credited via `creditWalletService.writeLedgerEntry(booking.parentId, sessionPrice, "BOOKING_REFUND", bookingId, "Admin dispute resolution — full refund")`
   **And** the `admin_alerts` row with `type = DISPUTE_RAISED` and `referenceId = bookingId.toString()` is resolved (`status = RESOLVED`, `resolvedAt = now()`, `resolvedBy = adminId`)
   **And** action logged in `admin_action_log` (`actionType = DISPUTE_RESOLVE`, `referenceId = disputeId.toString()`, `reason = resolutionNote`)
   **And** `DisputeResolvedEvent(disputeId, bookingId, resolution, booking.raisedBy)` is published
   **And** `200 OK`

5. **Given** an admin resolves a dispute with partial credit
   **When** `POST /api/admin/disputes/{disputeId}/resolve` is called with `{ "resolution": "PARTIAL_CREDIT", "creditAmount": 30.00, "resolutionNote": "..." }`
   **Then** `creditAmount` must be > 0 and ≤ `sessionPrice` — `400` with `ErrorDto.helpCode = "disputes.invalidCreditAmount"` otherwise (thrown as `ResponseStatusException(HttpStatus.BAD_REQUEST, "disputes.invalidCreditAmount")` — NOT `OperationNotAllowedException`, which maps to 403)
   **And** if `sessionPrice = 0.00` (no payment record exists — pack-based booking with absent `booking_payments` row), any positive `creditAmount` fails the ceiling check and `PARTIAL_CREDIT` is blocked; admin must use `COACH_WARNING` or `NO_ACTION` instead — document this in the error message or UI hint
   **And** `creditAmount` is credited via `creditWalletService.writeLedgerEntry(booking.parentId, creditAmount, "BOOKING_REFUND", bookingId, "Admin dispute resolution — partial refund")`
   **And** same status/alert/log/event flow as full credit

6. **Given** an admin finds no grounds for a refund
   **When** `POST /api/admin/disputes/{disputeId}/resolve` is called with `{ "resolution": "NO_ACTION", "resolutionNote": "..." }`
   **Then** `disputes.status = RESOLVED`, `resolution = NO_ACTION`
   **And** no credit or payment operation is performed
   **And** same alert/log/event flow

7. **Given** an admin resolves a dispute with a coach warning
   **When** `POST /api/admin/disputes/{disputeId}/resolve` is called with `{ "resolution": "COACH_WARNING", "resolutionNote": "...", "creditAmount": 0 | positive }`
   **Then** `CoachWarningIssuedEvent(coachId, disputeId, reason)` is published — warning is informational only; no automatic strike is issued
   **And** if `creditAmount > 0`: `creditAmount` must be ≤ `sessionPrice` — `400` with `ErrorDto.helpCode = "disputes.invalidCreditAmount"` otherwise (same ceiling validation as `PARTIAL_CREDIT`; thrown as `ResponseStatusException(HttpStatus.BAD_REQUEST, …)`)
   **And** if `creditAmount > 0` and passes ceiling check: credit via `creditWalletService.writeLedgerEntry(booking.parentId, creditAmount, "BOOKING_REFUND", bookingId, "Admin dispute resolution — coach warning + partial refund")`
   **And** same status/alert/log/event flow

8. **Given** an admin dismisses a frivolous dispute
   **When** `POST /api/admin/disputes/{disputeId}/dismiss` is called with `{ "reason": "..." }`
   **Then** `disputes.status = DISMISSED`
   **And** no credit or payment operation is performed
   **And** `admin_alerts` row resolved; action logged (actionType = DISPUTE_RESOLVE)
   **And** `200 OK`

## Tasks / Subtasks

- [x] **Task 1 — Flyway V74: Create `admin.disputes` table** (AC: 1)
  - [x] Create `src/main/resources/db/migration/V74__disputes_table.sql`:
    ```sql
    CREATE TABLE admin.disputes (
        id              UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
        booking_id      UUID         NOT NULL,
        raised_by       BIGINT       NOT NULL,
        raised_by_role  VARCHAR(10)  NOT NULL CHECK (raised_by_role IN ('PARENT', 'PLAYER')),
        reason          VARCHAR(30)  NOT NULL CHECK (reason IN ('COACH_NO_SHOW','SESSION_QUALITY','SAFETY_CONCERN','UNAUTHORISED_CHARGE','OTHER')),
        details         VARCHAR(2000) NOT NULL,
        status          VARCHAR(15)  NOT NULL DEFAULT 'OPEN' CHECK (status IN ('OPEN','UNDER_REVIEW','RESOLVED','DISMISSED')),
        -- NOTE: 'UNDER_REVIEW' is reserved for a future story (admin acknowledges/claims a dispute).
        -- No endpoint transitions to it in this story. Do NOT remove it from the constraint.
        resolution      VARCHAR(20)  CHECK (resolution IN ('FULL_CREDIT','PARTIAL_CREDIT','NO_ACTION','COACH_WARNING')),
        resolution_note VARCHAR(1000),
        credit_amount   NUMERIC(10,2),
        created_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
        resolved_at     TIMESTAMPTZ,
        resolved_by     BIGINT,
        version         BIGINT       NOT NULL DEFAULT 0
    );

    CREATE INDEX idx_disputes_booking_id ON admin.disputes(booking_id);
    CREATE INDEX idx_disputes_raised_by  ON admin.disputes(raised_by);
    CREATE INDEX idx_disputes_status     ON admin.disputes(status, created_at);

    -- Platform config: dispute submission window
    INSERT INTO main.platform_config (id, key, value, value_type, description, updated_at)
    VALUES (nextval('main.platform_config_id_seq'), 'disputes.submissionWindowDays', '14', 'LONG',
            'Days after booking completion/cancellation within which a dispute can be raised', NOW())
    ON CONFLICT DO NOTHING;
    ```
  - [x] `raised_by` is BIGINT (Long), NOT UUID — the system uses Long for parentId/playerId (see Booking entity and Principal.businessId)
  - [x] `credit_amount` is nullable — only set for PARTIAL_CREDIT and COACH_WARNING resolutions where credit > 0
  - [x] No FK on `booking_id` since the booking is in a different schema; validated at application level
  - [x] No FK on `raised_by` since user IDs are in `main.user` — consistent with other tables

- [x] **Task 2 — `Dispute` Entity and Repository** (AC: 1–8)
  - [x] Create `platform.admin.repo.Dispute`:
    ```java
    @Entity
    @Table(schema = "admin", name = "disputes")
    @Getter @Setter @NoArgsConstructor
    public class Dispute {

        @Id
        @GeneratedValue(strategy = GenerationType.UUID)
        private UUID id;

        @Column(name = "booking_id", nullable = false)
        private UUID bookingId;

        @Column(name = "raised_by", nullable = false)
        private Long raisedBy;

        @Column(name = "raised_by_role", nullable = false, length = 10)
        private String raisedByRole;   // "PARENT" or "PLAYER"

        @Column(nullable = false, length = 30)
        private String reason;

        @Column(nullable = false, length = 2000)
        private String details;

        @Column(nullable = false, length = 15)
        private String status = "OPEN";

        @Column(length = 20)
        private String resolution;

        @Column(name = "resolution_note", length = 1000)
        private String resolutionNote;

        @Column(name = "credit_amount", precision = 10, scale = 2)
        private BigDecimal creditAmount;

        @Column(name = "created_at", nullable = false, updatable = false)
        private Instant createdAt = Instant.now();

        @Column(name = "resolved_at")
        private Instant resolvedAt;

        @Column(name = "resolved_by")
        private Long resolvedBy;

        @Version
        private Long version;   // optimistic lock — prevents double-resolve/double-credit under concurrent admin actions
    }
    ```
  - [x] Create `platform.admin.repo.DisputeRepository`:
    ```java
    public interface DisputeRepository extends JpaRepository<Dispute, UUID> {

        @Query("""
            SELECT d FROM Dispute d
            WHERE d.bookingId = :bookingId
              AND d.status NOT IN ('RESOLVED', 'DISMISSED')
            """)
        Optional<Dispute> findOpenByBookingId(@Param("bookingId") UUID bookingId);

        List<Dispute> findByRaisedBy(Long raisedBy);
    }
    ```

- [x] **Task 3 — Domain Events** (AC: 1, 4–8)
  - [x] Create `platform.admin.contract.DisputeRaisedEvent`:
    ```java
    public class DisputeRaisedEvent extends ApplicationEvent {
        private final UUID disputeId;
        private final UUID bookingId;
        private final Long raisedBy;
        private final String reason;
        // constructor + getters
    }
    ```
  - [x] Create `platform.admin.contract.DisputeResolvedEvent`:
    ```java
    public class DisputeResolvedEvent extends ApplicationEvent {
        private final UUID disputeId;
        private final UUID bookingId;
        private final String resolution;
        private final Long raisedBy;
        // constructor + getters
    }
    ```
  - [x] Create `platform.admin.contract.CoachWarningIssuedEvent`:
    ```java
    public class CoachWarningIssuedEvent extends ApplicationEvent {
        private final Long coachId;   // VERIFY: must match the type returned by booking.getCoachId()
        private final UUID disputeId;
        private final String reason;  // the dispute's reason field
        // constructor + getters
    }
    ```
  - [x] **CRITICAL — verify `coachId` type before implementing**: All user-level IDs in this codebase are `Long` (auto-increment BIGINT). `Booking.coachId` is likely `Long`. **Read `Booking.java` and confirm the return type of `getCoachId()` before writing the event class.** If it returns `Long`, the field above is correct. If it returns a `UUID` (coach profile PK), change to `UUID coachId` — but this would be inconsistent with all other ID types in the system and would be surprising. Do not assume.
  - [x] All three in `platform.admin.contract` — they extend `ApplicationEvent` like all other events in this module

- [x] **Task 4 — `DisputeError` enum (ErrorCode)** (AC: 1, 5)
  - [x] Create `platform.admin.contract.DisputeError`:
    ```java
    public enum DisputeError implements ErrorCode {
        NOT_ELIGIBLE, WINDOW_EXPIRED, INVALID_CREDIT_AMOUNT;

        @Override
        public String getErrorCode() {
            return switch (this) {
                case NOT_ELIGIBLE          -> "disputes.notEligible";
                case WINDOW_EXPIRED        -> "disputes.windowExpired";
                case INVALID_CREDIT_AMOUNT -> "disputes.invalidCreditAmount";
            };
        }
    }
    ```
  - [x] Import path: `com.softropic.skillars.infrastructure.exception.ErrorCode` (same as `BookingError`)
  - [x] `NOT_ELIGIBLE` and `WINDOW_EXPIRED` → thrown as `OperationNotAllowedException(msg, DisputeError.XXX)` → 403 via `ApiAdvice.operationDeniedHandler()`
  - [x] `INVALID_CREDIT_AMOUNT` → thrown as `ResponseStatusException(HttpStatus.BAD_REQUEST, "disputes.invalidCreditAmount")` → **400** (NOT `OperationNotAllowedException` — invalid credit amount is a bad-request validation error, not an authorization failure; using `OperationNotAllowedException` would incorrectly return 403)
  - [x] Duplicate dispute (`disputes.alreadyRaised`) → thrown as `ResponseStatusException(HttpStatus.CONFLICT, "disputes.alreadyRaised")` → 409 (cannot use `OperationNotAllowedException` which maps to 403)

- [x] **Task 5 — Request/Response DTOs** (AC: 1–8)
  - [x] Create `platform.admin.contract.RaiseDisputeRequest`:
    ```java
    public record RaiseDisputeRequest(
        @NotNull UUID bookingId,
        @NotBlank String reason,    // validated against DisputeReason enum in service
        @NotBlank @Size(max = 2000) String details) {}
    ```
  - [x] Create `platform.admin.contract.DisputeCreatedResponse`:
    ```java
    public record DisputeCreatedResponse(UUID disputeId) {}
    ```
  - [x] Create `platform.admin.contract.DisputeResponse` (parent/player view — no admin fields):
    ```java
    public record DisputeResponse(
        UUID disputeId, UUID bookingId, String reason, String details,
        String status, String resolution, String resolutionNote, Instant createdAt) {}
    ```
  - [x] Create `platform.admin.contract.AdminDisputeDetailDto`:
    ```java
    public record AdminDisputeDetailDto(
        UUID disputeId, UUID bookingId, Long raisedBy, String raisedByRole,
        String reason, String details, String status, String resolution,
        String resolutionNote, BigDecimal creditAmount,
        Instant createdAt, Instant resolvedAt, Long resolvedBy,
        // booking context
        UUID coachId, String coachName, Instant sessionDate, String bookingStatus,
        // payment context
        BigDecimal creditDebited, BigDecimal stripeCharged, BigDecimal sessionPrice,
        // cancellation context (may be empty list)
        List<CoachCancellationHistoryEntryDto> cancellationHistory) {}
    ```
    - `CoachCancellationHistoryEntryDto` is already defined in `platform.admin.contract` from Story 10.2 — **do not create a new one**
  - [x] Create `platform.admin.contract.AdminResolveDisputeRequest`:
    ```java
    public record AdminResolveDisputeRequest(
        @NotBlank String resolution,         // FULL_CREDIT / PARTIAL_CREDIT / NO_ACTION / COACH_WARNING
        BigDecimal creditAmount,             // nullable; required for PARTIAL_CREDIT; optional for COACH_WARNING
        @NotBlank @Size(max = 1000) String resolutionNote) {}
    ```
  - [x] Create `platform.admin.contract.AdminDismissDisputeRequest`:
    ```java
    public record AdminDismissDisputeRequest(
        @NotBlank @Size(max = 500) String reason) {}
    ```

- [x] **Task 6 — `DisputeService`** (AC: 1–8)
  - [x] Create `platform.admin.service.DisputeService` — `@Service @RequiredArgsConstructor @Slf4j`
  - [x] Inject: `DisputeRepository`, `BookingRepository`, `BookingPaymentRepository`, `CoachProfileRepository`, `CoachCancellationHistoryRepository`, `AdminAlertRepository`, `AdminActionLogRepository`, `ConfigService`, `CreditWalletService`, `ApplicationEventPublisher`
  - [x] **`@Transactional` method `raiseDispute(UUID bookingId, String reason, String details, Long raisedBy, String raisedByRole)`** → `UUID` (disputeId):
    1. Load `Booking` or throw `ResourceNotFoundException("Booking", bookingId.toString())`
    2. Eligibility: booking status must be in `List.of("COMPLETED","CANCELLED","CANCELLED_PARENT","CANCELLED_COACH","NO_SHOW_PLAYER","NO_SHOW_COACH")` AND (`booking.parentId == raisedBy` OR `booking.playerId == raisedBy`) — throw `OperationNotAllowedException("Not eligible to raise dispute for this booking", DisputeError.NOT_ELIGIBLE)` if not met
    3. Window check: `long windowDays = configService.getLong("disputes.submissionWindowDays", 14L)` → if `booking.getUpdatedAt().isBefore(Instant.now().minus(windowDays, ChronoUnit.DAYS))` throw `OperationNotAllowedException("Dispute window expired", DisputeError.WINDOW_EXPIRED)`
    4. Duplicate check: `disputeRepository.findOpenByBookingId(bookingId).ifPresent(d -> { throw new ResponseStatusException(HttpStatus.CONFLICT, "disputes.alreadyRaised"); })`
    5. Insert dispute row + save
    6. Publish `DisputeRaisedEvent(source, dispute.getId(), bookingId, raisedBy, reason)`
    7. Return `dispute.getId()`
  - [x] **`@Transactional(readOnly = true)` method `getDispute(UUID disputeId, Long requesterId)`** → `DisputeResponse`:
    1. Load dispute or throw `ResourceNotFoundException("Dispute", disputeId.toString())`
    2. Verify `dispute.getRaisedBy().equals(requesterId)` — throw `OperationNotAllowedException("Cannot view another user's dispute", SecurityError.MISSING_RIGHTS)` on mismatch
    3. Return `DisputeResponse` (fields listed in AC 2)
  - [x] **`@Transactional(readOnly = true)` method `getAdminDisputeDetail(UUID disputeId)`** → `AdminDisputeDetailDto`:
    1. Load dispute or throw
    2. Load booking: `bookingRepository.findById(dispute.getBookingId()).orElseThrow(...)`
    3. Load coach profile: `coachProfileRepository.findById(booking.getCoachId())` — `.displayName` for coachName; use `"[coach not found]"` fallback if not present
    4. Load payment record: `bookingPaymentRepository.findById(dispute.getBookingId())` — nullable (pack-based sessions may not have Stripe charges)
    5. Compute `sessionPrice = payment.map(p -> p.getCreditDebited().add(p.getStripeCharged())).orElse(BigDecimal.ZERO)`
    6. Load cancellation history scoped to this booking: `coachCancellationHistoryRepository.findByBookingId(dispute.getBookingId())` — AC3 specifies rows where `bookingId` matches, not all of the coach's history. If `findByBookingId()` does not exist in `CoachCancellationHistoryRepository`, add it (Spring Data derived query: `List<CoachCancellationHistory> findByBookingId(UUID bookingId)`). Do NOT use `findByCoachIdOrderByCreatedAtDesc()` here — that returns every cancellation ever made by the coach across all bookings, which is both over-fetching and incorrect for this endpoint.
    7. Map and return `AdminDisputeDetailDto`
  - [x] **`@Transactional` method `resolveDispute(UUID disputeId, String resolution, BigDecimal creditAmount, String resolutionNote, Long adminId)`**:
    1. Load dispute or throw `ResourceNotFoundException`
    2. Guard: if `dispute.getStatus()` is already `RESOLVED` or `DISMISSED` → throw `ResponseStatusException(CONFLICT, "disputes.alreadyResolved")` (idempotency). The `@Version` field on `Dispute` provides optimistic locking — if two admins resolve simultaneously, the second commit throws `ObjectOptimisticLockingFailureException`; let this propagate (Spring's default exception translation maps it to a 500, which is acceptable for this race; alternatively catch and re-throw as 409).
    3. Load booking (needed for parentId and sessionPrice)
    4. Load payment record for sessionPrice
    5. Parse `resolution` enum — throw `ResponseStatusException(BAD_REQUEST, "Invalid resolution")` on parse error
    6. **Switch on resolution:**
       - `FULL_CREDIT`:
         - `BigDecimal price = payment.map(p -> p.getCreditDebited().add(p.getStripeCharged())).orElse(BigDecimal.ZERO)`
         - If `price.compareTo(BigDecimal.ZERO) > 0`: `creditWalletService.writeLedgerEntry(booking.getParentId(), price, "BOOKING_REFUND", bookingId, "Admin dispute resolution — full refund")`
       - `PARTIAL_CREDIT`:
         - Validate `creditAmount != null && creditAmount.compareTo(BigDecimal.ZERO) > 0 && creditAmount.compareTo(sessionPrice) <= 0` — throw `new ResponseStatusException(HttpStatus.BAD_REQUEST, "disputes.invalidCreditAmount")` if not met (400, NOT OperationNotAllowedException)
         - `creditWalletService.writeLedgerEntry(booking.getParentId(), creditAmount, "BOOKING_REFUND", bookingId, "Admin dispute resolution — partial refund")`
       - `NO_ACTION`: no credit or payment operation
       - `COACH_WARNING`:
         - Publish `CoachWarningIssuedEvent(source, booking.getCoachId(), disputeId, dispute.getReason())`
         - If `creditAmount != null && creditAmount.compareTo(BigDecimal.ZERO) > 0`:
           - Validate `creditAmount.compareTo(sessionPrice) <= 0` — throw `new ResponseStatusException(HttpStatus.BAD_REQUEST, "disputes.invalidCreditAmount")` if exceeded (same ceiling as `PARTIAL_CREDIT`)
           - `creditWalletService.writeLedgerEntry(booking.getParentId(), creditAmount, "BOOKING_REFUND", bookingId, "Admin dispute resolution — coach warning + partial refund")`
    7. Update dispute: `status = RESOLVED`, `resolution = <value>`, `resolutionNote = resolutionNote`, `creditAmount = creditAmount`, `resolvedAt = Instant.now()`, `resolvedBy = adminId`; save
    8. Resolve `admin_alerts` row: `adminAlertRepository.findFirstByReferenceIdAndTypeAndStatus(bookingId.toString(), AdminAlertType.DISPUTE_RAISED, AdminAlertStatus.OPEN).ifPresent(a -> { a.setStatus(RESOLVED); a.setResolvedAt(now); a.setResolvedBy(adminId); adminAlertRepository.save(a); })`
    9. Log: `AdminActionLog(adminId, AdminActionType.DISPUTE_RESOLVE, disputeId.toString(), resolutionNote)`; save
    10. Publish `DisputeResolvedEvent(source, disputeId, bookingId, resolution, dispute.getRaisedBy())`
  - [x] **`@Transactional` method `dismissDispute(UUID disputeId, String reason, Long adminId)`**:
    1. Load dispute or throw
    2. Guard: if already RESOLVED or DISMISSED → throw `ResponseStatusException(CONFLICT, "disputes.alreadyResolved")`. Optimistic locking via `@Version` also applies here.
    3. `dispute.setStatus("DISMISSED")`; save
    4. Resolve admin_alerts row (same as resolveDispute step 8, using bookingId)
    5. Log: `AdminActionLog(adminId, AdminActionType.DISPUTE_RESOLVE, disputeId.toString(), reason)`; save
  - [x] **CRITICAL — `CreditWalletService` cross-module injection**: The admin module importing `platform.payment.service.CreditWalletService` is a cross-module dependency — same pattern as `AdminCoachEnforcementService` importing `ReliabilityStrikeService` from `platform.payment.service`. **Do NOT create a new payment service or duplicate credit logic.** Import directly.
  - [x] **CRITICAL — `CreditWalletService.writeLedgerEntry()` signature**: `writeLedgerEntry(Long parentId, BigDecimal amount, String type, UUID referenceId, String description)` — the `amount` must be positive (method throws `IllegalArgumentException` if zero). Guard for this before calling.

- [x] **Task 7 — `DisputeResource`** (parent/player endpoints) (AC: 1, 2)
  - [x] Create `platform.admin.api.DisputeResource`:
    ```java
    @RestController
    @RequestMapping("/api/disputes")
    @RequiredArgsConstructor
    @Observed(name = "disputes")
    public class DisputeResource {

        private final DisputeService disputeService;
        private final SecurityUtil securityUtil;

        @PostMapping
        @PreAuthorize(SecurityConstants.HAS_PARENT_ROLE + " or " + SecurityConstants.HAS_PLAYER_ROLE_EXPR)
        @Observed(name = "disputes.raise")
        public ResponseEntity<DisputeCreatedResponse> raiseDispute(
                @Valid @RequestBody RaiseDisputeRequest request) {
            Long userId = resolveCurrentUserId();
            String role = resolveCurrentRole();
            UUID disputeId = disputeService.raiseDispute(
                request.bookingId(), request.reason(), request.details(), userId, role);
            return ResponseEntity.status(HttpStatus.CREATED).body(new DisputeCreatedResponse(disputeId));
        }

        @GetMapping("/{disputeId}")
        @PreAuthorize(SecurityConstants.HAS_PARENT_ROLE + " or " + SecurityConstants.HAS_PLAYER_ROLE_EXPR)
        @Observed(name = "disputes.get")
        public ResponseEntity<DisputeResponse> getDispute(@PathVariable UUID disputeId) {
            Long userId = resolveCurrentUserId();
            return ResponseEntity.ok(disputeService.getDispute(disputeId, userId));
        }

        private Long resolveCurrentUserId() { ... }
        private String resolveCurrentRole() { ... }
    }
    ```
  - [x] **CRITICAL — No `HAS_PLAYER_ROLE` constant in `SecurityConstants`**: Check whether a player-specific role constant exists. Looking at `SecurityConstants`: only `HAS_PARENT_ROLE = "hasRole('ROLE_PARENT')"` is defined. The player role might use `IS_AUTHENTICATED` or a different role. **Read `SecurityConstants.java` before implementing the `@PreAuthorize` expression to verify.** If players use `ROLE_PARENT` (family account) or a different role, adjust accordingly.
  - [x] `resolveCurrentUserId()` — same pattern as `BookingResource.currentParentId()`: `Long.parseLong(((Principal) securityUtil.getCurrentUser()).getBusinessId())`
  - [x] `resolveCurrentRole()` — derive from `Principal.getSkillarsRole()` or `getAuthorities()`: return `"PARENT"` or `"PLAYER"` string for `raisedByRole` column
  - [x] **Read `BookingResource.java` lines ~75–100 for the exact userId extraction pattern before implementing**

- [x] **Task 8 — `AdminDisputeResource`** (admin endpoints) (AC: 3–8)
  - [x] Create `platform.admin.api.AdminDisputeResource`:
    ```java
    @RestController
    @RequestMapping("/api/admin/disputes")
    @RequiredArgsConstructor
    @Observed(name = "admin.disputes")
    public class AdminDisputeResource {

        private final DisputeService disputeService;
        private final SecurityUtil securityUtil;

        @GetMapping("/{disputeId}")
        @PreAuthorize(SecurityConstants.HAS_ADMIN_ROLE)
        @Observed(name = "admin.disputes.detail")
        public ResponseEntity<AdminDisputeDetailDto> getDisputeDetail(@PathVariable UUID disputeId) {
            return ResponseEntity.ok(disputeService.getAdminDisputeDetail(disputeId));
        }

        @PostMapping("/{disputeId}/resolve")
        @PreAuthorize(SecurityConstants.HAS_ADMIN_ROLE)
        @Observed(name = "admin.disputes.resolve")
        public ResponseEntity<Void> resolveDispute(
                @PathVariable UUID disputeId,
                @Valid @RequestBody AdminResolveDisputeRequest request) {
            disputeService.resolveDispute(disputeId, request.resolution(),
                request.creditAmount(), request.resolutionNote(), resolveAdminId());
            return ResponseEntity.ok().build();
        }

        @PostMapping("/{disputeId}/dismiss")
        @PreAuthorize(SecurityConstants.HAS_ADMIN_ROLE)
        @Observed(name = "admin.disputes.dismiss")
        public ResponseEntity<Void> dismissDispute(
                @PathVariable UUID disputeId,
                @Valid @RequestBody AdminDismissDisputeRequest request) {
            disputeService.dismissDispute(disputeId, request.reason(), resolveAdminId());
            return ResponseEntity.ok().build();
        }

        private Long resolveAdminId() { ... }
    }
    ```
  - [x] `resolveAdminId()` — **copy verbatim from `AdminReviewResource.resolveAdminId()`** — identical pattern

- [x] **Task 9 — Update `AdminAlertEventListener`** (AC: 1)
  - [x] Add import: `import com.softropic.skillars.platform.admin.contract.DisputeRaisedEvent;`
  - [x] Add handler:
    ```java
    @EventListener
    @Transactional
    public void onDisputeRaised(DisputeRaisedEvent event) {
        insertAlert(AdminAlertType.DISPUTE_RAISED,
            event.getBookingId().toString(),
            AdminAlertReferenceType.BOOKING);
    }
    ```
  - [x] `referenceId = bookingId.toString()` (not disputeId) — this allows the admin to navigate to the booking context from the alert, and the admin dispute detail endpoint can then query by bookingId
  - [x] The existing `AdminQueueService.buildSummary()` has `default -> ""` which covers DISPUTE_RAISED — no change needed there; the summary already counts DISPUTE_RAISED alerts via `countOpenByType()` query and `getSummary()` already maps `DISPUTE_RAISED` to `disputes` field in `AdminQueueSummaryDto`

- [x] **Task 10 — Integration Test: `DisputeSubmissionIT`** (AC: 1, 2)
  - [x] Create `platform.admin.api.DisputeSubmissionIT` — TSID range `9100_xxx`
  - [x] IDs: `ADMIN_ID = 9100_000_100L`, `PARENT_ID = 9100_000_001L`, `PLAYER_ID = 9100_000_002L`, `COACH_USER_ID = 9100_000_010L`
  - [x] Seed: parent (ROLE_PARENT), player, coach user + coach profile, booking (`status = 'COMPLETED'`, `parent_id = PARENT_ID`, `player_id = PLAYER_ID`, `coach_id = coachProfileId`), `config dispute.submissionWindowDays=14`
  - [x] Test 1: `raiseDispute_eligible_returns201WithDisputeId()`:
    - POST `/api/disputes` with parent token; verify 201, `disputeId` UUID returned, `admin.disputes` row inserted with `status = 'OPEN'`
  - [x] Test 2: `raiseDispute_ineligible_notOwner_returns403()`:
    - Seed a second parent; POST with their token → 403 `disputes.notEligible`
  - [x] Test 3: `raiseDispute_windowExpired_returns403()`:
    - Set `updated_at` of booking to `now() - INTERVAL '15 days'`; POST → 403 `disputes.windowExpired`
  - [x] Test 4: `raiseDispute_duplicateOpenDispute_returns409()`:
    - POST dispute twice; second call → 409
  - [x] Test 5: `raiseDispute_bookingInWrongStatus_returns403()`:
    - Seed booking with `status = 'REQUESTED'`; POST → 403 `disputes.notEligible`
  - [x] Test 6: `getDispute_ownDispute_returns200WithDetails()`:
    - POST then GET; verify status/reason/details in response; verify `resolvedAt`/`resolvedBy` NOT in response
  - [x] Test 7: `getDispute_otherUsersDispute_returns403()`:
    - POST with parentId=PARENT, GET with player token → 403
  - [x] **Admin alert verification** (reuse TSID 9100_xxx block): verify `admin_alerts` row inserted with `type = 'DISPUTE_RAISED'`, `reference_id = bookingId`, `reference_type = 'BOOKING'`, `status = 'OPEN'`

- [x] **Task 11 — Integration Test: `AdminDisputeResolveIT`** (AC: 3–7)
  - [x] Create `platform.admin.api.AdminDisputeResolveIT` — TSID range `9110_xxx`
  - [x] IDs: `ADMIN_ID = 9110_000_100L`, `PARENT_ID = 9110_000_001L`, `COACH_USER_ID = 9110_000_010L`
  - [x] Seed: admin (3-row pattern: `main.user` skillars_role='ADMIN', `main.authority` `ON CONFLICT DO NOTHING`, `main.user_authority`), parent, coach, booking (COMPLETED), `booking_payments` row (`credit_debited=20.00`, `stripe_charged=30.00`), OPEN DISPUTE_RAISED admin_alert for this bookingId, dispute row (status=OPEN)
  - [x] Test 1: `resolveFullCredit_credits50ToParentWallet()`:
    - POST `/api/admin/disputes/{id}/resolve` `{ resolution: "FULL_CREDIT", resolutionNote: "..." }` with admin token
    - Verify `disputes.status = 'RESOLVED'`, `resolution = 'FULL_CREDIT'`
    - Verify `parent_credit_ledger` row: `amount = 50.00`, `type = 'BOOKING_REFUND'`
    - Verify `admin_alerts` row `status = 'RESOLVED'`
    - Verify `admin_action_log` row `action_type = 'DISPUTE_RESOLVE'`, `reference_id = disputeId`
  - [x] Test 2: `resolvePartialCredit_validAmount_creditsCorrectly()`:
    - POST with `{ resolution: "PARTIAL_CREDIT", creditAmount: 25.00, resolutionNote: "..." }`
    - Verify ledger entry `amount = 25.00`
  - [x] Test 3: `resolvePartialCredit_amountExceedsSessionPrice_returns400()`:
    - POST with `creditAmount = 999.00` (> sessionPrice 50.00) → **400** `disputes.invalidCreditAmount` (thrown as `ResponseStatusException(BAD_REQUEST, …)`)
  - [x] Test 4: `resolvePartialCredit_amountZero_returns400()`:
    - POST with `creditAmount = 0` → **400** `disputes.invalidCreditAmount`
  - [x] Test 5: `resolveNoAction_noLedgerEntry()`:
    - POST with `{ resolution: "NO_ACTION", resolutionNote: "..." }`
    - Verify NO `parent_credit_ledger` rows for this booking
    - Verify `disputes.status = 'RESOLVED'`, `resolution = 'NO_ACTION'`
  - [x] Test 6: `resolveCoachWarning_withCredit_creditsAndPublishesEvent()`:
    - POST with `{ resolution: "COACH_WARNING", creditAmount: 20.00, resolutionNote: "..." }`
    - Verify ledger entry `amount = 20.00`; no automatic strike (`coach_reliability_strikes` count unchanged)
  - [x] Test 7: `getAdminDisputeDetail_returnsFullContext()`:
    - GET `/api/admin/disputes/{id}` → verify coachId, coachName, sessionDate, creditDebited, stripeCharged, sessionPrice, dispute fields
  - [x] Test 8: `resolveAlreadyResolved_returns409()`:
    - Resolve twice → second call 409

- [x] **Task 12 — Integration Test: `DisputeDismissIT`** (AC: 8)
  - [x] Create `platform.admin.api.DisputeDismissIT` — TSID range `9120_xxx`
  - [x] IDs: `ADMIN_ID = 9120_000_100L`, `PARENT_ID = 9120_000_001L`
  - [x] Seed: admin, parent, booking (COMPLETED), dispute (OPEN), OPEN DISPUTE_RAISED admin_alert
  - [x] Test 1: `dismissDispute_setsDismissedAndResolvesAlert()`:
    - POST `/api/admin/disputes/{id}/dismiss` with `{ reason: "Frivolous" }`
    - Verify `disputes.status = 'DISMISSED'`
    - Verify `admin_alerts.status = 'RESOLVED'`
    - Verify `admin_action_log` row `action_type = 'DISPUTE_RESOLVE'`
    - Verify NO `parent_credit_ledger` entry
  - [x] Test 2: `dismissNonAdminRole_returns403()`:
    - POST with parent token → 403
  - [x] Test 3: `dismissAlreadyDismissed_returns409()`

## Dev Notes

### Module Package Structure

```
src/main/java/com/softropic/skillars/
  platform/admin/
    contract/
      + DisputeRaisedEvent.java         (event — NEW)
      + DisputeResolvedEvent.java       (event — NEW)
      + CoachWarningIssuedEvent.java    (event — NEW)
      + DisputeError.java               (ErrorCode enum — NEW)
      + RaiseDisputeRequest.java        (record — NEW)
      + DisputeCreatedResponse.java     (record — NEW)
      + DisputeResponse.java            (record — NEW, parent/player view)
      + AdminDisputeDetailDto.java      (record — NEW, admin view)
      + AdminResolveDisputeRequest.java (record — NEW)
      + AdminDismissDisputeRequest.java (record — NEW)
      ~ AdminActionType.java            (NO change — DISPUTE_RESOLVE already present from V70)
      ~ AdminAlertType.java             (NO change — DISPUTE_RAISED already present from V70)
      ~ AdminAlertReferenceType.java    (NO change — BOOKING already present from V70)
    repo/
      + Dispute.java                    (@Entity — NEW)
      + DisputeRepository.java          (JpaRepository — NEW)
    service/
      + DisputeService.java             (@Service — NEW)
      ~ AdminAlertEventListener.java    (MODIFIED — add onDisputeRaised handler)
    api/
      + DisputeResource.java            (@RestController — NEW, /api/disputes)
      + AdminDisputeResource.java       (@RestController — NEW, /api/admin/disputes)

src/main/resources/db/migration/
  + V74__disputes_table.sql             (NEW)

src/test/java/com/softropic/skillars/
  platform/admin/api/
    + DisputeSubmissionIT.java          (TSID 9100_xxx — NEW)
    + AdminDisputeResolveIT.java        (TSID 9110_xxx — NEW)
    + DisputeDismissIT.java             (TSID 9120_xxx — NEW)
```

### CRITICAL: `raisedBy` Is BIGINT (Long), NOT UUID

The epic says `disputes.raisedBy UUID NOT NULL` — **this is wrong for this codebase**. The `parentId` and `playerId` fields in `Booking` are `Long`. The `Principal.getBusinessId()` returns a String representation of a `Long`. All user IDs in `main.user` are auto-increment `BIGINT`. The `disputes.raised_by` column must be `BIGINT` to match `booking.parent_id` and `booking.player_id`. The story uses `Long raisedBy` throughout.

### CRITICAL: No `completedAt`/`cancelledAt` Columns on Booking

The `Booking` entity has no `completedAt` or `cancelledAt` field. The dispute submission window is checked against `booking.updatedAt` (the `Instant updatedAt` field, set by `@PreUpdate`). Since COMPLETED/CANCELLED are terminal states that do not get further updates, `updatedAt` is the effective finalization timestamp. Do NOT add new columns to the bookings table for this story.

### CRITICAL: Eligible Booking Statuses for Dispute

The epic says `COMPLETED, CANCELLED` but the booking state machine has multiple cancelled variants. All of the following are eligible for dispute:
- `COMPLETED`
- `CANCELLED` (admin/system cancellation from pause/REQUESTED states)
- `CANCELLED_PARENT`
- `CANCELLED_COACH`
- `NO_SHOW_PLAYER`
- `NO_SHOW_COACH`

**Do NOT check against just `COMPLETED, CANCELLED`** — parents with `CANCELLED_COACH` or `NO_SHOW_COACH` bookings should absolutely be able to dispute. Check using `List.of("COMPLETED","CANCELLED","CANCELLED_PARENT","CANCELLED_COACH","NO_SHOW_PLAYER","NO_SHOW_COACH")`.

### CRITICAL: Story 7.2's `BookingDisputedEvent` vs Story 10.3's `DisputeRaisedEvent`

These are TWO DIFFERENT dispute mechanisms:
- **Story 7.2**: `BookingDisputedEvent` transitions a booking to `DISPUTED` status mid-session. Payment is frozen. This is the in-session dispute for concurrent session problems.
- **Story 10.3**: `DisputeRaisedEvent` creates a row in `admin.disputes` for a booking that is already `COMPLETED` or `CANCELLED`. The booking status does NOT change. This is the post-session formal dispute process.

**Do NOT transition the booking to `DISPUTED` status in Story 10.3.** The booking remains in its terminal state.

### CRITICAL: `admin_alerts` referenceType for Disputes

`AdminAlertReferenceType` already has `BOOKING` — this is the correct referenceType to use. `referenceId = bookingId.toString()`. The unique index `admin_alerts_unique_open_per_ref (reference_id, type) WHERE status = 'OPEN'` ensures only one OPEN DISPUTE_RAISED alert per booking.

The `AdminAlertReferenceType` enum **must NOT be updated** (no DISPUTE value needed). Do NOT add a new DB migration for this.

### CRITICAL: `AdminActionType.DISPUTE_RESOLVE` Already Exists

From V70 migration: `'DISPUTE_RESOLVE'` is already in the `admin_action_log.action_type` CHECK constraint, and `DISPUTE_RESOLVE` is already in the `AdminActionType` Java enum. **No new migration or enum update needed** for this.

### CRITICAL: `UNDER_REVIEW` Status — No Transition in This Story

The `disputes` table includes `UNDER_REVIEW` in its status CHECK constraint. No endpoint or service method in this story transitions a dispute to that state — it is reserved for a future story (e.g., an admin "claims" a dispute before resolving it). Do NOT add a transition to `UNDER_REVIEW` in this story, and do NOT remove the value from the constraint.

### CRITICAL: Concurrency Guard on Dispute Resolution

Two admins resolving the same dispute simultaneously can both pass the `status = OPEN` guard before either commits, leading to double credit issuance. The `@Version Long version` field on the `Dispute` entity provides Hibernate optimistic locking. When both try to commit, the second update fails with `ObjectOptimisticLockingFailureException`. Let this propagate — Spring's default mapping is a 500 (acceptable for an extremely rare race), or catch and re-throw as `ResponseStatusException(CONFLICT, "disputes.alreadyResolved")` for a cleaner response. Do NOT remove the `version` column from the Flyway migration.

### CRITICAL: `PARTIAL_CREDIT` Blocked for Zero-Payment Sessions

If no `booking_payments` row exists (pack-based booking where payment tracking was omitted), `sessionPrice = 0.00`. The `PARTIAL_CREDIT` ceiling check (`creditAmount ≤ sessionPrice`) blocks any positive amount. Admins should use `COACH_WARNING` (with `creditAmount = 0`) or `NO_ACTION` for such sessions. This does not affect `FULL_CREDIT` — the zero-price guard for that case is handled separately.

### CRITICAL: `CoachWarningIssuedEvent` — Verify `coachId` Type

The `Booking` entity's `coachId` field type must be confirmed before writing `CoachWarningIssuedEvent`. All other user IDs in this system are `Long` (BIGINT). If `Booking.getCoachId()` returns `Long`, declare `private final Long coachId` in the event. **Read `Booking.java` before writing this class.**

### CRITICAL: `CreditWalletService.writeLedgerEntry()` Amount Must Be Non-Zero

`writeLedgerEntry()` throws `IllegalArgumentException` if `amount == BigDecimal.ZERO`. Always guard before calling:
```java
if (price.compareTo(BigDecimal.ZERO) > 0) {
    creditWalletService.writeLedgerEntry(...);
}
```
This guard is needed for FULL_CREDIT when `sessionPrice = 0` (pack-based session with no payment record).

### CRITICAL: `AdminQueueSummaryDto` Already References `DISPUTE_RAISED`

`AdminQueueService.getSummary()` already maps `DISPUTE_RAISED` to the `disputes` field in `AdminQueueSummaryDto`. The `buildSummary()` switch has `default -> ""` which handles `DISPUTE_RAISED` alerts with an empty summary. Both are already correct — no changes to `AdminQueueService` needed.

### CRITICAL: `AdminAlertRepository.findFirstByReferenceIdAndTypeAndStatus()` Already Exists

This method was added in Story 10.2. Use it to find and resolve the DISPUTE_RAISED alert when resolving/dismissing a dispute. Check the existing method signature in `AdminAlertRepository.java` before use.

### CRITICAL: Admin User Test Setup (3-Row Pattern)

Copied from Story 10.1 and 10.2. Every IT test class that calls admin endpoints must seed:
```sql
INSERT INTO main.user (id, ..., skillars_role) VALUES (ADMIN_ID, ..., 'ADMIN');
INSERT INTO main.authority (name) VALUES ('ROLE_ADMIN') ON CONFLICT DO NOTHING;
INSERT INTO main.user_authority (user_id, authority_name) VALUES (ADMIN_ID, 'ROLE_ADMIN');
```
See `AdminQueueIT.java` for the exact seed pattern.

### CRITICAL: Player Role for `DisputeResource`

**Before implementing `@PreAuthorize` on `DisputeResource`**, run:
```
grep -r "ROLE_PLAYER\|HAS_PLAYER\|PLAYER_ROLE" src/main/java/com/softropic/skillars/infrastructure/security/SecurityConstants.java
```
`SecurityConstants` only defines `HAS_PARENT_ROLE`. If players share `ROLE_PARENT` (family account) or use a different role, the `@PreAuthorize` expression will differ. Verify the actual authority granted to player users before writing the annotation.

### CRITICAL: `CoachProfile.displayName` for Admin Dispute Detail

`CoachProfile.displayName` is the field used for `coachName` in the admin dispute detail response. Repository lookup: `coachProfileRepository.findById(booking.getCoachId())`. The admin module already injects `CoachProfileRepository` (from Story 10.2's `AdminCoachEnforcementService`) — use the same injection pattern.

### Session Price from `booking_payments`

`sessionPrice = paymentRecord.creditDebited + paymentRecord.stripeCharged`. The `BookingPayment` entity has `creditDebited` and `stripeCharged` as `BigDecimal`. Sum these for the total amount the parent paid. This is the ceiling for `PARTIAL_CREDIT` validation.

For pack-based bookings where `sessionPackPurchaseId != null`, the Stripe charge may be zero (credit only), but the credit debited is still non-zero. The price ceiling should still use the full `creditDebited + stripeCharged` sum.

### References — Files to Read Before Implementing

- `AdminReviewResource.java` — `platform/admin/api/` — `resolveAdminId()` + endpoint pattern to copy for `AdminDisputeResource`
- `Booking.java` — `platform/booking/repo/` — **read first**: (1) confirm return type of `getCoachId()` to match `CoachWarningIssuedEvent.coachId` field type; (2) confirm `updatedAt` field name and `@PreUpdate` annotation
- `BookingResource.java` — `platform/booking/api/` — `currentParentId()` userId resolution pattern for `DisputeResource`; also check player role handling
- `CreditWalletService.java` — `platform/payment/service/` — `writeLedgerEntry()` signature; must import for credit issuance
- `AdminAlertEventListener.java` — `platform/admin/service/` — `insertAlert()` helper to reuse in new `onDisputeRaised()` handler
- `AdminAlertRepository.java` — `platform/admin/repo/` — `findFirstByReferenceIdAndTypeAndStatus()` for alert resolution
- `BookingPayment.java` — `platform/payment/repo/` — field names `creditDebited`, `stripeCharged` for session price computation
- `CoachCancellationHistoryRepository.java` — `platform/payment/repo/` — `findByCoachIdOrderByCreatedAtDesc()` added in Story 10.2 for dispute detail
- `AdminCoachEnforcementService.java` — `platform/admin/service/` — cross-module injection pattern (payment + marketplace repos injected into admin service)
- `AdminQueueIT.java` — `platform/admin/api/` — admin user seeding (3-row pattern) for IT tests
- `ReviewSubmissionService.java` — `platform/reviews/service/` — `OperationNotAllowedException` + `ErrorCode` pattern for eligibility guards
- `BookingError.java` — `platform/booking/contract/` — enum structure to copy for `DisputeError`
- `SecurityConstants.java` — `infrastructure/security/` — verify player role before writing `@PreAuthorize`
- `V70__admin_alerts_action_log.sql` — confirm `DISPUTE_RAISED` and `DISPUTE_RESOLVE` are already in constraints

### Story 10.2 Patterns to Reuse Exactly

- `resolveAdminId()` → copy from `AdminCoachEnforcementResource` or `AdminReviewResource`
- `@Transactional(propagation = REQUIRES_NEW)` NOT needed for dispute credit — this is a synchronous admin action, not a `@TransactionalEventListener`; run credit issuance inside the same `@Transactional` as the dispute status update
- `AdminActionLog` construction and save — copy pattern from `AdminCoachEnforcementService`

## Dev Agent Record

### Agent Model Used

claude-sonnet-4-6

### Debug Log References

None.

### Completion Notes List

- Confirmed `Booking.coachId` is `UUID` (coach profile PK) — `CoachWarningIssuedEvent.coachId` set to `UUID` accordingly.
- No `ROLE_PLAYER` exists in `SecurityConstants`. `DisputeResource` uses `IS_AUTHENTICATED` with role derived from granted authorities (ROLE_PARENT → "PARENT", fallback → "PLAYER"), matching the pattern in `ReviewResource` and `MessagingResource`.
- `platform_config` uses hardcoded sequential IDs; used ID 515 for `disputes.submissionWindowDays`.
- Added `findByBookingId(UUID bookingId)` to `CoachCancellationHistoryRepository` since it only had `findByCoachIdOrderByCreatedAtDesc()`.
- All 3 integration test classes use unique TSID ranges (9100_xxx, 9110_xxx, 9120_xxx) and clean up after themselves.
- `PARTIAL_CREDIT` with `creditAmount = 0` correctly returns 400 since `creditAmount.compareTo(BigDecimal.ZERO) <= 0` guard triggers first.

### File List

src/main/resources/db/migration/V74__disputes_table.sql
src/main/java/com/softropic/skillars/platform/admin/repo/Dispute.java
src/main/java/com/softropic/skillars/platform/admin/repo/DisputeRepository.java
src/main/java/com/softropic/skillars/platform/admin/contract/DisputeRaisedEvent.java
src/main/java/com/softropic/skillars/platform/admin/contract/DisputeResolvedEvent.java
src/main/java/com/softropic/skillars/platform/admin/contract/CoachWarningIssuedEvent.java
src/main/java/com/softropic/skillars/platform/admin/contract/DisputeError.java
src/main/java/com/softropic/skillars/platform/admin/contract/RaiseDisputeRequest.java
src/main/java/com/softropic/skillars/platform/admin/contract/DisputeCreatedResponse.java
src/main/java/com/softropic/skillars/platform/admin/contract/DisputeResponse.java
src/main/java/com/softropic/skillars/platform/admin/contract/AdminDisputeDetailDto.java
src/main/java/com/softropic/skillars/platform/admin/contract/AdminResolveDisputeRequest.java
src/main/java/com/softropic/skillars/platform/admin/contract/AdminDismissDisputeRequest.java
src/main/java/com/softropic/skillars/platform/admin/service/DisputeService.java
src/main/java/com/softropic/skillars/platform/admin/api/DisputeResource.java
src/main/java/com/softropic/skillars/platform/admin/api/AdminDisputeResource.java
src/main/java/com/softropic/skillars/platform/admin/service/AdminAlertEventListener.java (modified — added onDisputeRaised handler)
src/main/java/com/softropic/skillars/platform/payment/repo/CoachCancellationHistoryRepository.java (modified — added findByBookingId)
src/test/java/com/softropic/skillars/platform/admin/api/DisputeSubmissionIT.java
src/test/java/com/softropic/skillars/platform/admin/api/AdminDisputeResolveIT.java
src/test/java/com/softropic/skillars/platform/admin/api/DisputeDismissIT.java

### Review Findings

- [x] [Review][Decision] `ResponseStatusException` body not included by default — Spring Boot 2.3+ omits the reason string unless `server.error.include-message=always` is set; `AdminDisputeResolveIT` asserts `.contains("disputes.invalidCreditAmount")` on the response body, which will silently always-fail if this property is absent; needs decision: configure the property or change assertion strategy [AdminDisputeResolveIT.java]
- [x] [Review][Patch] Race condition — no unique partial index on open disputes; concurrent POSTs bypass the `findOpenByBookingId` check and create two OPEN disputes for the same booking [V74__disputes_table.sql]
- [x] [Review][Patch] `dismissDispute` does not persist `resolvedAt`, `resolvedBy`, or dismiss reason on the `Dispute` entity — `admin.disputes` row has nulls, breaking the audit trail [DisputeService.java]
- [x] [Review][Patch] `CoachWarningIssuedEvent` published before `creditAmount` ceiling validation in `COACH_WARNING` branch — event fires on requests that subsequently throw 400, causing unrollable side-effects when a listener is added [DisputeService.java]
- [x] [Review][Patch] `reason` field in `RaiseDisputeRequest` validated only with `@NotBlank`; invalid values reach the DB CHECK constraint and bubble up as unhandled 500 instead of a client-facing 400 [RaiseDisputeRequest.java, DisputeService.java]
- [x] [Review][Patch] `FULL_CREDIT` silently issues no credit for zero-payment (pack-based) sessions with no log warning; admin receives 200 OK with no indication the refund did not execute [DisputeService.java]
- [x] [Review][Patch] `DisputeError.INVALID_CREDIT_AMOUNT` is dead code — throw sites use the literal string `"disputes.invalidCreditAmount"` instead of the enum constant [DisputeService.java, DisputeError.java]
- [x] [Review][Patch] AC7 test `resolveCoachWarning_withCredit_creditsAndPublishesEvent` does not assert that `CoachWarningIssuedEvent` was published [AdminDisputeResolveIT.java]
- [x] [Review][Patch] No IT test covers the player-raised dispute path (submission and GET); `booking.playerId` ownership branch has zero integration coverage [DisputeSubmissionIT.java]
- [x] [Review][Patch] Vacuous test assertions — `doesNotContainKey("resolvedAt")` / `doesNotContainKey("resolvedBy")` always pass because `DisputeResponse` record never includes those fields [DisputeSubmissionIT.java]
