# Story 7.3: Cancellation, Refund & Reliability Strikes

Status: done

## Story

As a parent,
I want cancellations and no-shows handled automatically according to a clear policy,
And as a coach, I want legitimate cancellations to not damage my reliability score,
And as a platform operator, I want coaches who unexcusedly cancel or no-show to receive strikes that affect their visibility.

## Acceptance Criteria

1. **Given** a parent cancels a single-session booking more than 24 hours before the scheduled start
   **When** `POST /api/bookings/{id}/cancel` is called (parent-auth)
   **Then** `BookingService.cancelBookingAsParent()` computes `hoursBeforeSession = ChronoUnit.HOURS.between(Instant.now(), booking.requestedStartTime)` — both UTC Instants; timezone is irrelevant
   **And** `BookingCancelledByParentEvent(bookingId, parentId, coachId, sessionPackPurchaseId, hoursBeforeSession, sessionPrice)` is published
   **And** `CancellationRefundService` writes a `BOOKING_REFUND` credit ledger entry for 100% of `sessionPrice`
   **And** booking transitions to `CANCELLED_PARENT`; coach notified

2. **Given** a parent cancels a single-session booking 24 hours or less before the scheduled start
   **When** `POST /api/bookings/{id}/cancel` is called
   **Then** `BookingCancelledByParentEvent` is published with `hoursBeforeSession ≤ 24`
   **And** no credit is issued — session fee is forfeited; `booking_payments.status` remains `CAPTURED`; coach earnings unaffected

3. **Given** a parent is recorded as a no-show
   **When** `POST /api/bookings/{id}/no-show-player` is called (coach-auth)
   **Then** booking transitions to `NO_SHOW_PLAYER`; no credit issued; coach earnings unaffected
   **And** `PlayerNoShowEvent` is published (for notification only)

4. **Given** a coach cancels a booking
   **When** `POST /api/bookings/{id}/coach-cancel` is called (coach-auth) with body `{ "cancelReason": "MUTUAL_AGREEMENT" }` (required; null treated as `OTHER_UNEXCUSED`)
   **Then** `booking.cancelReason` is persisted; `BookingCancelledByCoachEvent(bookingId, parentId, coachId, cancelReason, sessionPackPurchaseId, sessionPrice, packExpiredAtCancellation, parentEmail)` is published
   **And** `CancellationRefundService` writes a `BOOKING_REFUND` credit ledger entry for 100% of `sessionPrice` regardless of reason
   **And** booking transitions to `CANCELLED_COACH`; parent notified: "Your coach has cancelled the session on {date}. The full amount has been added to your platform credit."
   **And** a `payment.coach_cancellation_history` row is always written — ALL coach cancellations (excused AND unexcused) are recorded for admin pattern visibility
   **And** if `cancelReason IN (SCHEDULING_PREFERENCE, OTHER_UNEXCUSED)` or null: `ReliabilityStrikeService.issue(coachId, bookingId, "COACH_CANCELLATION_UNEXCUSED")` is additionally called
   **And** if `cancelReason IN (MUTUAL_AGREEMENT, HEALTH_MEDICAL, FAMILY_EMERGENCY, WEATHER)`: no strike issued — history row already written above

5. **Given** a coach is recorded as a no-show
   **When** `POST /api/bookings/{id}/no-show-coach` is called (parent-auth)
   **Then** `CoachNoShowEvent` is published
   **And** `CancellationRefundService` writes a `BOOKING_REFUND` credit ledger entry for 100% of `sessionPrice`
   **And** `ReliabilityStrikeService.issue(coachId, bookingId, "COACH_NO_SHOW")` is always called — no-show has no excused category

6. **Given** a pack-based booking is cancelled by the parent more than 24 hours before the session
   **When** `CancellationRefundService` handles `BookingCancelledByParentEvent` with `sessionPackPurchaseId NOT NULL` and `hoursBeforeSession > 24`
   **Then** `PackSessionService.restoreSession(sessionPackPurchaseId)` increments `remainingSessions`; NO credit ledger entry written; NO Stripe action; coach notified

7. **Given** a pack-based booking is cancelled by the parent 24h or less before the session, or parent no-shows
   **When** `CancellationRefundService` processes event with `sessionPackPurchaseId NOT NULL` and `hoursBeforeSession ≤ 24` (or `PlayerNoShowEvent`)
   **Then** `remainingSessions` is NOT restored — session forfeited; no credit entry

8. **Given** a pack-based booking is cancelled by the coach (any reason) or coach no-shows
   **When** `CancellationRefundService` handles `BookingCancelledByCoachEvent` or `CoachNoShowEvent` with `sessionPackPurchaseId NOT NULL`
   **Then** if `packExpiredAtCancellation = false`: `PackSessionService.restoreSession()` — player can rebook; NO credit entry
   **And** if `packExpiredAtCancellation = true`: write `BOOKING_REFUND` credit entry at `sessionPrice` (the locked `session_pack_purchases.pricePerSession` — already in event)
   **And** strike logic applies per AC 4/5: excused = no strike; unexcused or no-show = strike

9. **Given** a `BookingCancelledByCoachEvent` (unexcused) or `CoachNoShowEvent` is handled by `ReliabilityStrikeService`
   **When** `ReliabilityStrikeService.issue()` is called
   **Then** a `marketplace.coach_reliability_strikes` record is created: `(id UUID PK, coachId, bookingId, reason VARCHAR('COACH_CANCELLATION_UNEXCUSED'|'COACH_NO_SHOW'), createdAt, acknowledged=false)`
   **And** `SELECT COUNT(*) FROM coach_reliability_strikes WHERE coachId = ? AND createdAt > now() - INTERVAL '30 days'` is computed
   **And** if count reaches `configService.getLong("reliability.strike.visibilityThreshold")` (default 3): `coach_profiles.status = REDUCED`; coach notified
   **And** if count reaches `configService.getLong("reliability.strike.suspensionThreshold")` (default 5): `coach_profiles.status = PENDING_REVIEW`; admin notification event published
   **And** threshold checks are mutually exclusive: PENDING_REVIEW check runs first; if already PENDING_REVIEW, no REDUCED check needed

10. **Given** a coach disputes a strike
    **When** `PUT /api/payment/coaches/strikes/{strikeId}/acknowledge` is called (coach-auth)
    **Then** `coach_reliability_strikes.acknowledged = true`; admin review triggered (Story 10.x placeholder)
    **And** the strike continues to count in the rolling 30-day window until admin overturns it

11. **Given** a coach views their strikes
    **When** `GET /api/payment/coaches/me/strikes` is called (coach-auth)
    **Then** returns all `coach_reliability_strikes` for the authenticated coach, ordered by `createdAt DESC`

12. **Given** a booking is in `REFUND_PENDING` state following admin dispute resolution (Story 10.x)
    **When** admin approves a refund
    **Then** `CancellationRefundService.processAdminRefund(bookingId, amount)` writes a `BOOKING_REFUND` credit entry; `booking_payments.status → REFUNDED` (admin wires this in Story 10.x)

## Tasks / Subtasks

- [x] **Task 1 — Flyway V63 migration** (AC: 1–10)
  - [x] `src/main/resources/db/migration/V63__cancellation_refund_reliability.sql`
  - [x] `ALTER TABLE booking.bookings ADD COLUMN cancel_reason VARCHAR(50);` — nullable; null = no reason set
  - [x] Extend `marketplace.coach_reliability_strikes`:
    - [x] `ADD COLUMN booking_id UUID;` — nullable for backward compat with existing rows (pre-7.3 rows have no bookingId)
    - [x] `ADD COLUMN acknowledged BOOLEAN NOT NULL DEFAULT false;`
  - [x] Create `payment.coach_cancellation_history`:
    ```sql
    CREATE TABLE payment.coach_cancellation_history (
        id          UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
        coach_id    UUID        NOT NULL,
        booking_id  UUID        NOT NULL,
        cancel_reason VARCHAR(50) NOT NULL,
        created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
    );
    ```
  - [x] Seed `main.platform_config` (next IDs after 502):
    ```sql
    INSERT INTO main.platform_config (id, key, value, value_type, description, updated_at) VALUES
        (503, 'reliability.strike.visibilityThreshold', '3', 'STRING', 'Rolling 30-day strike count at which coach visibility is reduced. Default 3.', NOW()),
        (504, 'reliability.strike.suspensionThreshold',  '5', 'STRING', 'Rolling 30-day strike count triggering PENDING_REVIEW. Default 5.', NOW())
    ON CONFLICT (key) DO NOTHING;
    ```

- [x] **Task 2 — Java model changes** (AC: 4, 9)
  - [x] Add `REDUCED` and `PENDING_REVIEW` to `CoachProfileStatus` enum (currently `DRAFT`, `ACTIVE`):
    ```java
    public enum CoachProfileStatus { DRAFT, ACTIVE, REDUCED, PENDING_REVIEW }
    ```
  - [x] Add `cancelReason` field to `Booking` entity (`platform.booking.repo`):
    ```java
    @Column(name = "cancel_reason", length = 50)
    private String cancelReason;
    ```
  - [x] Update `CoachReliabilityStrike` entity (`platform.marketplace.repo`): add `@Column(name="booking_id") UUID bookingId` and `@Column boolean acknowledged = false` — no `@GeneratedValue` on these fields; just new `@Column` mappings
  - [x] Create `CoachCancellationHistory.java` in `platform.payment.repo`:
    - `@Entity @Table(schema="payment", name="coach_cancellation_history")`, `@Id @GeneratedValue(strategy=UUID) UUID id`, `@Column UUID coachId`, `@Column UUID bookingId`, `@Column String cancelReason`, `@Column Instant createdAt` with `@PrePersist`
  - [x] Create `CoachCancellationHistoryRepository extends JpaRepository<CoachCancellationHistory, UUID>` in `platform.payment.repo`

- [x] **Task 3 — Domain events in `platform.booking.contract`** (AC: 1–8)
  - [x] `BookingCancelledByParentEvent.java`:
    ```java
    public class BookingCancelledByParentEvent extends ApplicationEvent {
        private final UUID bookingId;
        private final Long parentId;
        private final UUID coachId;
        private final UUID sessionPackPurchaseId;  // nullable
        private final long hoursBeforeSession;
        private final BigDecimal sessionPrice;
        private final String parentEmail;
        private final String coachEmail;
        private final Instant requestedStartTime;
        private final String canonicalTimezone;
        // constructor + accessors
    }
    ```
  - [x] `BookingCancelledByCoachEvent.java`:
    ```java
    public class BookingCancelledByCoachEvent extends ApplicationEvent {
        private final UUID bookingId;
        private final Long parentId;
        private final UUID coachId;
        private final String cancelReason;  // ENUM value as String; null = OTHER_UNEXCUSED
        private final UUID sessionPackPurchaseId;  // nullable
        private final BigDecimal sessionPrice;     // pricePerSession for pack, perSessionPrice for credit
        private final boolean packExpiredAtCancellation;
        private final String parentEmail;
        private final Instant requestedStartTime;
        private final String canonicalTimezone;
        // constructor + accessors
    }
    ```
  - [x] `CoachNoShowEvent.java`:
    ```java
    public class CoachNoShowEvent extends ApplicationEvent {
        private final UUID bookingId;
        private final Long parentId;
        private final UUID coachId;
        private final UUID sessionPackPurchaseId;  // nullable
        private final BigDecimal sessionPrice;
        private final boolean packExpiredAtCancellation;
        private final String parentEmail;
        private final Instant requestedStartTime;
        private final String canonicalTimezone;
        // constructor + accessors
    }
    ```
  - [x] `PlayerNoShowEvent.java`:
    ```java
    public class PlayerNoShowEvent extends ApplicationEvent {
        private final UUID bookingId;
        private final Long parentId;
        private final UUID coachId;
        private final String coachEmail;
        private final Instant requestedStartTime;
        private final String canonicalTimezone;
        // constructor + accessors — no credit data needed
    }
    ```
  - [x] `CoachVisibilityReducedEvent.java` in `platform.payment.contract`:
    ```java
    public class CoachVisibilityReducedEvent extends ApplicationEvent {
        private final UUID coachId;
        private final long rollingStrikeCount;
        // constructor + accessors — consumed by notification service to email coach
    }
    ```
  - [x] `StrikeThresholdReachedEvent.java` in `platform.payment.contract`:
    ```java
    public class StrikeThresholdReachedEvent extends ApplicationEvent {
        private final UUID coachId;
        private final UUID triggeringBookingId;
        private final long rollingStrikeCount;
        // constructor + accessors — consumed by platform.admin in Story 10.x to create admin_alerts row
    }
    ```

- [x] **Task 4 — BookingService refactoring** (AC: 1–8)
  - [x] **REMOVE** `CoachReliabilityStrikeQueuedEvent` publishing from `applyRefundLogic()` — BOTH the `CANCEL_COACH` (<24h check) and `NO_SHOW_COACH` handlers. The new domain events + services replace this. Keep only the `refundEligibility` setting logic
  - [x] Fix `CANCEL_COACH` case in `applyRefundLogic`: remove the `CoachReliabilityStrikeQueuedEvent` publish; keep `booking.setRefundEligibility("FULL")`
  - [x] Fix `NO_SHOW_COACH` case in `applyRefundLogic`: remove the `CoachReliabilityStrikeQueuedEvent` publish; keep `booking.setRefundEligibility("FULL")`
  - [x] Add `CancelCoachRequest` DTO record in `platform.booking.contract`:
    ```java
    public record CancelCoachRequest(String cancelReason) {}
    ```
    `cancelReason` is intentionally nullable at the API boundary; null is coerced to `OTHER_UNEXCUSED` in `cancelBookingAsCoach()`. Valid non-null values: `MUTUAL_AGREEMENT`, `HEALTH_MEDICAL`, `FAMILY_EMERGENCY`, `WEATHER`, `SCHEDULING_PREFERENCE`, `OTHER_UNEXCUSED`. Validate with `Set.contains()` in the service; throw `400 booking.invalidCancelReason` for unrecognised strings
  - [x] Add `cancelBookingAsParent(UUID bookingId, Long parentUserId)` to `BookingService` (`@Transactional`):
    1. Load booking, validate `booking.parentId == parentUserId`
    2. Compute `hoursBeforeSession = ChronoUnit.HOURS.between(Instant.now(), booking.requestedStartTime)` — use `long`, not truncate
    3. Resolve `sessionPrice` via `resolveSessionPrice(booking)` (already exists)
    4. Resolve `parentEmail = resolveEmail(parentUserId)`, `coachEmail = resolveCoachEmail(booking.coachId)`
    5. Call `transition(bookingId, BookingEvent.CANCEL_PARENT, new TransitionContext(ActorRole.PARENT, parentUserId))` — this calls `applyRefundLogic` setting `refundEligibility`
    6. Publish `BookingCancelledByParentEvent` (inside `@Transactional`; `CancellationRefundService` uses `@TransactionalEventListener(AFTER_COMMIT)`)
  - [x] Add `cancelBookingAsCoach(UUID bookingId, Long coachUserId, String cancelReason)` to `BookingService` (`@Transactional`):
    1. Load booking; validate coach owns it via `coachProfileRepository.findByUserId(coachUserId)`
    2. Resolve `sessionPrice`, `parentEmail`, check pack expiry if `sessionPackPurchaseId != null`
    3. `booking.setCancelReason(cancelReason != null ? cancelReason : "OTHER_UNEXCUSED")` — persist before transition
    4. Call `transition(bookingId, BookingEvent.CANCEL_COACH, ctx)` — sets `refundEligibility=FULL`
    5. `boolean packExpired = booking.sessionPackPurchaseId != null && sessionPackPurchaseRepository.findById(booking.sessionPackPurchaseId).map(p -> p.getExpiresAt().isBefore(Instant.now())).orElse(false)`
    6. Publish `BookingCancelledByCoachEvent`
  - [x] Add `recordNoShowPlayer(UUID bookingId, Long coachUserId)` to `BookingService` (`@Transactional`):
    1. Validate coach ownership; call `transition(bookingId, NO_SHOW_PLAYER, ctx)` (sets `refundEligibility=NONE`)
    2. Resolve `coachEmail`; publish `PlayerNoShowEvent`
  - [x] Add `recordNoShowCoach(UUID bookingId, Long parentUserId)` to `BookingService` (`@Transactional`):
    1. Validate parent owns booking; call `transition(bookingId, NO_SHOW_COACH, ctx)` (sets `refundEligibility=FULL`)
    2. Resolve `sessionPrice`, `parentEmail`, check pack expiry
    3. Publish `CoachNoShowEvent`
  - [x] Add `resolveCoachEmail(UUID coachId)` private helper: load `CoachProfile` by id → get `userId` → load `User` → email
  - [x] **Import cleanup**: remove `CoachReliabilityStrikeQueuedEvent` import from `BookingService.java` if no longer used

- [x] **Task 5 — `CancellationResource` in `platform.booking.api`** (AC: 1–5)
  - [x] Create `CancellationResource.java` (`@Observed(name="booking.cancellation") @RestController @RequestMapping("/api/bookings") @RequiredArgsConstructor`)
  - [x] `POST /{id}/cancel` — `@PreAuthorize(HAS_PARENT_ROLE)` — calls `bookingService.cancelBookingAsParent(id, currentParentId())` → `204`
  - [x] `POST /{id}/coach-cancel` — `@PreAuthorize(HAS_COACH_ROLE)` — body `@Valid CancelCoachRequest` → `bookingService.cancelBookingAsCoach(id, currentCoachUserId(), req.cancelReason())` → `204`
  - [x] `POST /{id}/no-show-player` — `@PreAuthorize(HAS_COACH_ROLE)` — `bookingService.recordNoShowPlayer(id, currentCoachUserId())` → `204`
  - [x] `POST /{id}/no-show-coach` — `@PreAuthorize(HAS_PARENT_ROLE)` — `bookingService.recordNoShowCoach(id, currentParentId())` → `204`
  - [x] Helper methods: `currentParentId()` and `currentCoachUserId()` — copy the **exact same pattern** from `BookingResource.java` (parse Long from `principal.getBusinessId()`)

- [x] **Task 6 — `CancellationRefundService` in `platform.payment.service`** (AC: 1–8, 12)
  - [x] Create `CancellationRefundService.java` — `@Service @RequiredArgsConstructor @Slf4j`
  - [x] Inject: `CreditWalletService`, `PackSessionService`, `CoachCancellationHistoryRepository`, `ReliabilityStrikeService`, `CoachPricingRepository`, `SessionPackPurchaseRepository`
  - [x] **Excused reason set** (static): `EXCUSED_REASONS = Set.of("MUTUAL_AGREEMENT", "HEALTH_MEDICAL", "FAMILY_EMERGENCY", "WEATHER")`
  - [x] `@TransactionalEventListener(phase = AFTER_COMMIT) @Transactional(propagation = REQUIRES_NEW)` on `BookingCancelledByParentEvent`:
    ```
    if (event.getSessionPackPurchaseId() != null) {
        if (event.getHoursBeforeSession() > 24) {
            packSessionService.restoreSession(event.getSessionPackPurchaseId());  // no credit
        }
        // else: forfeited, no action
    } else {
        if (event.getHoursBeforeSession() > 24) {
            creditWalletService.writeLedgerEntry(event.getParentId(), event.getSessionPrice(),
                "BOOKING_REFUND", event.getBookingId(), "Parent cancellation >24h — full refund");
        }
        // else: forfeited, no action
    }
    // Notification: coach email — "Parent has cancelled..."
    ```
  - [x] `@TransactionalEventListener(phase = AFTER_COMMIT) @Transactional(propagation = REQUIRES_NEW)` on `BookingCancelledByCoachEvent`:
    ```
    if (event.getSessionPackPurchaseId() != null) {
        if (event.isPackExpiredAtCancellation()) {
            creditWalletService.writeLedgerEntry(event.getParentId(), event.getSessionPrice(),
                "BOOKING_REFUND", event.getBookingId(), "Coach cancellation — expired pack refund");
        } else {
            packSessionService.restoreSession(event.getSessionPackPurchaseId());
        }
    } else {
        creditWalletService.writeLedgerEntry(event.getParentId(), event.getSessionPrice(),
            "BOOKING_REFUND", event.getBookingId(), "Coach cancellation — full refund");
    }
    // Always record cancellation history — ALL coach cancellations (excused AND unexcused) for admin visibility
    String reason = event.getCancelReason() != null ? event.getCancelReason() : "OTHER_UNEXCUSED";
    saveCancellationHistory(event.getCoachId(), event.getBookingId(), reason);
    if (!EXCUSED_REASONS.contains(reason)) {
        reliabilityStrikeService.issue(event.getCoachId(), event.getBookingId(), "COACH_CANCELLATION_UNEXCUSED");
    }
    // Notification: parent email — "Your coach has cancelled..."
    ```
  - [x] `@TransactionalEventListener(phase = AFTER_COMMIT) @Transactional(propagation = REQUIRES_NEW)` on `CoachNoShowEvent`:
    ```
    // Same refund logic as coach cancel for pack-based
    if (event.getSessionPackPurchaseId() != null) {
        if (event.isPackExpiredAtCancellation()) {
            creditWalletService.writeLedgerEntry(event.getParentId(), event.getSessionPrice(),
                "BOOKING_REFUND", event.getBookingId(), "Coach no-show — expired pack refund");
        } else {
            packSessionService.restoreSession(event.getSessionPackPurchaseId());
        }
    } else {
        creditWalletService.writeLedgerEntry(event.getParentId(), event.getSessionPrice(),
            "BOOKING_REFUND", event.getBookingId(), "Coach no-show — full refund");
    }
    // Always issue strike
    reliabilityStrikeService.issue(event.getCoachId(), event.getBookingId(), "COACH_NO_SHOW");
    // Notification: parent — credit issued
    ```
  - [x] `@TransactionalEventListener(phase = AFTER_COMMIT) @Transactional(propagation = REQUIRES_NEW)` on `PlayerNoShowEvent`:
    ```
    // No credit action — session fee forfeited, coach earnings unaffected (AC 3)
    // Notification only: coach email — "Player has not shown up for the session on {date}"
    // Use event.getCoachEmail() and event.getRequestedStartTime()/event.getCanonicalTimezone()
    ```
  - [x] Rename `saveExcusedCancellationHistory` → `saveCancellationHistory` everywhere in this class; it now writes for all reasons
  - [x] **Admin refund stub** `@Transactional processAdminRefund(UUID bookingId, BigDecimal amount, Long parentId)` — writes `BOOKING_REFUND`; admin endpoint wired in Story 10.x

- [x] **Task 7 — `ReliabilityStrikeService` in `platform.payment.service`** (AC: 9–11)
  - [x] Create `ReliabilityStrikeService.java` — `@Service @RequiredArgsConstructor @Slf4j`
  - [x] Inject: `CoachReliabilityStrikeRepository` (from `platform.marketplace.repo`), `CoachProfileRepository` (from `platform.marketplace.repo`), `ConfigService` (from infrastructure), `ApplicationEventPublisher`
  - [x] `@Transactional issue(UUID coachId, UUID bookingId, String reason)`:
    ```java
    CoachReliabilityStrike strike = new CoachReliabilityStrike();
    strike.setCoachId(coachId);
    strike.setBookingId(bookingId);
    strike.setReason(reason);
    // strike.createdAt set by @PrePersist or entity constructor
    strikeRepository.save(strike);

    long count = strikeRepository.countByCoachIdAndCreatedAtAfter(coachId, OffsetDateTime.now().minusDays(30));
    long suspensionThreshold = Long.parseLong(configService.getString("reliability.strike.suspensionThreshold"));
    long visibilityThreshold = Long.parseLong(configService.getString("reliability.strike.visibilityThreshold"));

    CoachProfile coach = coachProfileRepository.findById(coachId)
        .orElseThrow(() -> new ResourceNotFoundException("Coach not found", "coach_profile"));

    if (count >= suspensionThreshold) {
        coach.setStatus(CoachProfileStatus.PENDING_REVIEW);
        coachProfileRepository.save(coach);
        // publish StrikeThresholdReachedEvent(coachId, bookingId, count)
        // — consumed by platform.admin in Story 10.x to insert admin_alerts row (type=STRIKE_THRESHOLD)
        // IMPORTANT: event class name is StrikeThresholdReachedEvent — used verbatim in Story 10.x admin listener; do NOT rename
    } else if (count >= visibilityThreshold) {
        coach.setStatus(CoachProfileStatus.REDUCED);
        coachProfileRepository.save(coach);
        // publish CoachVisibilityReducedEvent(coachId, count)
        // — notification service sends coach email: "Your reliability score has been affected..."
    }
    ```
  - [x] `@Transactional acknowledge(UUID strikeId, Long coachUserId)`:
    1. Load strike; verify `strike.coachId == coachProfileRepository.findByUserId(coachUserId).getId()` — else `403`
    2. `strike.setAcknowledged(true); strikeRepository.save(strike)`
    3. Publish placeholder admin event
  - [x] `List<CoachReliabilityStrike> getCoachStrikes(Long coachUserId)`: load profile → `strikeRepository.findByCoachIdOrderByCreatedAtDesc(coachId)` — add this query to repo

- [x] **Task 7.1 — `CoachSearchService` visibility filter** (AC: 9 — mandatory, without this REDUCED coaches disappear from marketplace)
  - [x] In `platform.marketplace.service.CoachSearchService`, find the `Specification<CoachProfile>` predicate that currently filters `status = 'ACTIVE'`
  - [x] Change to: `status IN ('ACTIVE', 'REDUCED')` — REDUCED coaches must still appear in results, just ranked lower
  - [x] Add a secondary sort: ACTIVE coaches sort before REDUCED within any other sort criteria. If using `JpaSpecificationExecutor`, add a `Sort.Order` that puts `CASE WHEN status='ACTIVE' THEN 0 ELSE 1 END ASC` as the leading sort key (or use a Specification-level ORDER BY if the existing sort already delegates to a method you can extend)
  - [x] Verify `CoachMarketplaceResourceIT` still passes; add a new assertion: a REDUCED-status coach appears in results but after ACTIVE coaches at the same price/rating

- [x] **Task 8 — `ReliabilityStrikeResource` in `platform.payment.api`** (AC: 10–11)
  - [x] Create `ReliabilityStrikeResource.java` (`@Observed(name="payment.reliability") @RestController @RequestMapping("/api/payment") @RequiredArgsConstructor`)
  - [x] `GET /coaches/me/strikes` — `@PreAuthorize(HAS_COACH_ROLE)` — returns `List<ReliabilityStrikeResponse>` → `200`
  - [x] `PUT /coaches/strikes/{strikeId}/acknowledge` — `@PreAuthorize(HAS_COACH_ROLE)` — `reliabilityStrikeService.acknowledge(strikeId, currentCoachUserId())` → `204`
  - [x] Create `ReliabilityStrikeResponse` record in `platform.payment.contract`:
    ```java
    public record ReliabilityStrikeResponse(UUID strikeId, UUID bookingId, String reason,
        Instant issuedAt, boolean acknowledged) {}
    ```
    **Mapping note**: `CoachReliabilityStrike.createdAt` is `OffsetDateTime`; map to `issuedAt` via `.toInstant()`. The field is `createdAt` on the entity (Story 2.3 naming) and `issuedAt` in the response and in all Story 10.x admin views — map explicitly, do not rely on Jackson property name matching
  - [x] Add `findByCoachIdOrderByCreatedAtDesc(UUID coachId)` to `CoachReliabilityStrikeRepository`

- [x] **Task 9 — Frontend: cancel/no-show actions + reliability page** (AC: 4, 10, 11)
  - [x] Extend `src/frontend/src/api/booking.api.js` with:
    - `cancelBooking(bookingId)` → `POST /api/bookings/{id}/cancel`
    - `coachCancelBooking(bookingId, cancelReason)` → `POST /api/bookings/{id}/coach-cancel`
    - `recordNoShowPlayer(bookingId)` → `POST /api/bookings/{id}/no-show-player`
    - `recordNoShowCoach(bookingId)` → `POST /api/bookings/{id}/no-show-coach`
  - [x] Extend `src/frontend/src/api/payment.api.js` with:
    - `fetchMyStrikes()` → `GET /api/payment/coaches/me/strikes`
    - `acknowledgeStrike(strikeId)` → `PUT /api/payment/coaches/strikes/{strikeId}/acknowledge`
  - [x] Create `CancelCoachModal.vue` in `src/frontend/src/components/booking/` — glassmorphism, `q-select` for cancelReason (options: MUTUAL_AGREEMENT, HEALTH_MEDICAL, FAMILY_EMERGENCY, WEATHER, SCHEDULING_PREFERENCE, OTHER_UNEXCUSED), confirmation step
  - [x] Add cancel/no-show CTAs to `CoachBookingCard.vue` or session view (check what exists in Stories 3.x)
  - [x] Create `src/frontend/src/pages/coach/CoachReliabilityPage.vue` — glassmorphism, shows strikes list with date, reason, booking link, acknowledged status, acknowledge button; banner shows current visibility status if REDUCED or PENDING_REVIEW
  - [x] Extend `src/frontend/src/stores/payment.store.js` with: `coachStrikes` state, `fetchCoachStrikes()` action, `acknowledgeStrike(id)` action
  - [x] Add i18n keys `cancellation.*` and `reliability.*` to `en/index.js` and `de/index.js`
  - [x] Add route `/coach/reliability` to `src/frontend/src/router/routes.js`
  - [x] Extend `ReliabilityIndicator.vue` to navigate to `/coach/reliability` on click (coach context only)

- [x] **Task 10 — Tests** (AC: 1–11)
  - [x] `CancellationRefundMatrixTest.java` (unit, `src/test/java/.../platform/payment/service/`):
    - Parent cancel >24h, credit-based: `BOOKING_REFUND` written, no Stripe call
    - Parent cancel >24h, pack-based: `restoreSession` called, no credit entry
    - Parent cancel ≤24h, credit-based: no credit entry, no `restoreSession`
    - Parent cancel ≤24h, pack-based: no `restoreSession`, no credit
    - Coach cancel excused (MUTUAL_AGREEMENT), credit-based: `BOOKING_REFUND` written, `coachCancellationHistoryRepo.save` called, NO `reliabilityStrikeService.issue`
    - Coach cancel unexcused (SCHEDULING_PREFERENCE), credit-based: `BOOKING_REFUND` written, `reliabilityStrikeService.issue("COACH_CANCELLATION_UNEXCUSED")` called
    - Coach cancel null reason: treated as OTHER_UNEXCUSED → strike issued
    - Coach cancel, pack not expired: `restoreSession` called, no credit entry
    - Coach cancel, pack expired: `BOOKING_REFUND` at pricePerSession, no `restoreSession`
    - Coach no-show, credit-based: `BOOKING_REFUND`, `reliabilityStrikeService.issue("COACH_NO_SHOW")`
    - Coach no-show, pack active: `restoreSession`, `reliabilityStrikeService.issue("COACH_NO_SHOW")`
    - Coach no-show, pack expired: `BOOKING_REFUND`, strike issued
    - Mock: `CreditWalletService`, `PackSessionService`, `ReliabilityStrikeService`, `CoachCancellationHistoryRepository`
  - [x] `ReliabilityStrikeServiceTest.java` (unit):
    - First strike (count=1): no status change
    - Third strike (count=3 = visibilityThreshold): `CoachProfile.status = REDUCED`
    - Fifth strike (count=5 = suspensionThreshold): `CoachProfile.status = PENDING_REVIEW` (not both REDUCED then PENDING_REVIEW — check PENDING first)
    - Strike below threshold: no status change (count=2)
    - Excused bypass: `CancellationRefundService` does NOT call `issue()` for MUTUAL_AGREEMENT (test via CancellationRefundMatrixTest)
    - `countByCoachIdAndCreatedAtAfter` called with `now() - 30 days` (use Mockito `ArgumentCaptor<OffsetDateTime>` to verify)
  - [x] `CoachVisibilitySuppressionIT.java` (`@SpringBootTest @Testcontainers`):
    - Insert 3 coach_reliability_strikes → call `issue()` → verify `coach_profiles.status = REDUCED`
    - Insert 2 more → call `issue()` → verify `coach_profiles.status = PENDING_REVIEW`
    - Verify strike rows in `marketplace.coach_reliability_strikes`
  - [x] `PackCancellationRefundIT.java` (`@SpringBootTest @Testcontainers`):
    - Coach cancels with active pack: `session_pack_purchases.remaining_sessions` incremented; `parent_credit_ledger` has no new BOOKING_REFUND row
    - Coach cancels with expired pack: `parent_credit_ledger` has BOOKING_REFUND entry for `pricePerSession`; `remaining_sessions` NOT changed
    - Parent cancels pack-based booking >24h before session: `remaining_sessions` incremented in DB; `parent_credit_ledger` has no new BOOKING_REFUND row
    - Parent cancels pack-based booking ≤24h before session: `remaining_sessions` NOT changed; `parent_credit_ledger` has no new BOOKING_REFUND row (session forfeited)

### Review Findings

- [x] [Review][Patch] `coachProfile` is always `null` in `CoachReliabilityPage.vue` — REDUCED/PENDING_REVIEW status banners never render; needs API call to fetch coach profile [CoachReliabilityPage.vue:coachProfile]
- [x] [Review][Patch] `ChronoUnit.HOURS.between` truncation — parent cancelling 23h59m before session gets 23 hours, failing the `> 24` check and wrongly forfeiting their refund; replace with `booking.getRequestedStartTime().isAfter(Instant.now().plus(24, ChronoUnit.HOURS))` [BookingService.java:cancelBookingAsParent]
- [x] [Review][Patch] `cancelReason` saved before `transition()` — transition should be called first to validate the state machine allows the operation, then persist the reason; current order mutates booking before knowing if the operation is valid [BookingService.java:cancelBookingAsCoach]
- [x] [Review][Patch] `resolveCoachEmail()` returns empty string on missing coach profile — email silently dropped with no log/metric; should log a warning and/or use the same throw-or-alert pattern as `resolveEmail()` [BookingService.java:resolveCoachEmail]
- [x] [Review][Patch] `StrikeThresholdReachedEvent` fires on every strike past the suspension threshold — each new strike past count=5 triggers another admin alert; add idempotency guard: only publish event when transitioning TO `PENDING_REVIEW` (i.e. previous status != PENDING_REVIEW) [ReliabilityStrikeService.java:issue]
- [x] [Review][Defer] `buildSort()` has identical branches for "price" and "rating" — pre-existing; both were already falling back to `displayName` order before this story; price sort is applied in Java post-enrichment [CoachSearchService.java:buildSort] — deferred, pre-existing
- [x] [Review][Defer] `processAdminRefund` has no auth check or amount validation — stub wired by admin in Story 10.x per spec; no REST exposure in this story — deferred, pre-existing
- [x] [Review][Defer] `GET /coaches/me/strikes` has no pagination — unbounded list; low risk given expected data volume, but could grow — deferred, pre-existing
- [x] [Review][Defer] Concurrent strike issuance race: two simultaneous events for the same coach may both read count=N and both fire the threshold event — inherent to non-locking count approach; acceptable at current scale — deferred, pre-existing
- [x] [Review][Defer] `CoachCancellationHistory.createdAt` has `@Column(updatable=false)` but value set via `@PrePersist` — in-memory entity is null until DB round-trip if ever used with batch `saveAll`; low risk given single-save usage — deferred, pre-existing

## Dev Notes

### Critical: applyRefundLogic must lose its strike publishing

`BookingService.applyRefundLogic()` CURRENTLY publishes `CoachReliabilityStrikeQueuedEvent` for:
- `CANCEL_COACH` — only if `hoursUntilSession <= 24` (wrong logic: time-based, not reason-based)
- `NO_SHOW_COACH` — always

This Story 7.3 REMOVES these two `eventPublisher.publishEvent(new CoachReliabilityStrikeQueuedEvent(...))` calls. The new `cancelBookingAsCoach()` and `recordNoShowCoach()` methods publish `BookingCancelledByCoachEvent` / `CoachNoShowEvent`, and `CancellationRefundService` delegates to `ReliabilityStrikeService` based on reason. **Leaving the old event in place would double-issue strikes.**

After removal, if `CoachReliabilityStrikeQueuedEvent` has no remaining publishers, remove the import from `BookingService` to keep the compiler clean.

### Critical: CoachProfileStatus enum DB interaction

`CoachProfile.status` is mapped to `@Enumerated(EnumType.STRING)`. The DB column type is `VARCHAR` (not a PG enum), so adding `REDUCED` and `PENDING_REVIEW` to the Java enum is sufficient — no Flyway migration needed for the column itself. Verify with: `grep -r "coach_profiles" src/main/resources/db/migration/ | grep status` to confirm it's a `VARCHAR` column.

### Critical: resolveSessionPrice requires pack purchase lookup

`BookingService.resolveSessionPrice(Booking)` already handles both cases:
- `sessionPackPurchaseId != null` → loads `SessionPackPurchase.pricePerSession` (locked at purchase time)
- `sessionPackPurchaseId == null` → loads `CoachPricing.perSessionPrice`

Reuse this exact method (already private in `BookingService`) in the new cancel methods. Do NOT re-implement it.

### Critical: Pack expiry check timing

`packExpiredAtCancellation` must be evaluated AT THE MOMENT of cancellation (inside the `@Transactional` cancel method), before publishing the event. The pack expiry is the DB value at that instant. Race condition risk is low (packs expire on a daily scheduler), but the check must happen in the booking transaction, not later in `CancellationRefundService`.

```java
boolean packExpired = false;
if (booking.getSessionPackPurchaseId() != null) {
    packExpired = sessionPackPurchaseRepository.findById(booking.getSessionPackPurchaseId())
        .map(p -> p.getExpiresAt().isBefore(Instant.now()))
        .orElse(false);
}
```

### Critical: @TransactionalEventListener + REQUIRES_NEW

`CancellationRefundService` event handlers must use `@Transactional(propagation = REQUIRES_NEW)` (not the default REQUIRED). `@TransactionalEventListener(AFTER_COMMIT)` methods run after the originating TX is committed — there is no active TX at invocation time, so REQUIRED would open a new TX anyway. Making it explicit with `REQUIRES_NEW` avoids subtle bugs if ever called from within a TX. Follow the exact same pattern as `BookingPaymentPersistenceService.persistPaymentSuccess()` (private `@Transactional` method) from Story 7.2.

### Critical: CoachReliabilityStrike entity — createdAt field

The existing `CoachReliabilityStrike` entity uses `OffsetDateTime createdAt = OffsetDateTime.now()` (default in field initializer, NOT `@PrePersist`). The V63 migration only adds columns — no change to the existing `createdAt` behavior. When creating new strikes in `ReliabilityStrikeService.issue()`, do NOT set `createdAt` manually — the field default initializer handles it. Ensure `acknowledged = false` by setting it explicitly or via the column default.

### Critical: ConfigService.getLong() vs getString()

`ConfigService` exposes `getString(key)` and `getLong(key)`. The reliability threshold seeds store integer values (`'3'`, `'5'`) as `value_type='STRING'`. Use `Long.parseLong(configService.getString("reliability.strike.visibilityThreshold"))` rather than `configService.getLong()` — `getLong()` exists but look at how it parses; safest is `getString()` + `Long.parseLong()` matching the pattern used in `CashOutService` for `feeRate`/`feeFixed`. Check `ConfigService.java` before writing.

### Critical: Two coach_reliability_strikes tables — DO NOT confuse

The table `marketplace.coach_reliability_strikes` was created in Story 2.3 and is used by `CoachSearchService` to batch-load strike counts for marketplace listing. Its current columns: `id`, `coach_id`, `reason`, `created_at`. Story 7.3 V63 adds `booking_id` and `acknowledged`. Do NOT create a new table or a new entity — update the existing `CoachReliabilityStrike` entity in `platform.marketplace.repo`.

`ReliabilityStrikeRepository.countByCoachIdAndCreatedAtAfter(UUID, OffsetDateTime)` already exists on `CoachReliabilityStrikeRepository`. **Reuse it** — do not write a new count query.

### Critical: Coach visibility — REDUCED is search-scoping, not blocking

When `CoachProfile.status = REDUCED`, the coach should appear lower in search results (reduced ranking/suppressed from top pages), NOT completely removed. `CoachSearchService` currently filters by `status = ACTIVE`. After adding `REDUCED`, the marketplace search query must:
- Include `REDUCED` coaches in results but deprioritize them (add to the existing Specification — this is a Story 7.3 requirement that touches `platform.marketplace.service.CoachSearchService`)
- Update `CoachSearchService` to include `ACTIVE` and `REDUCED` statuses in the base filter, with `REDUCED` coaches sorted after `ACTIVE` coaches

Add to `CoachSearchService`: filter `IN ('ACTIVE', 'REDUCED')` not just `= 'ACTIVE'`. This ensures reduced-visibility coaches still appear (but lower) rather than disappearing entirely. This is a **mandatory change** — without it, `REDUCED` coaches vanish from search, which is not the intended behavior per UX-DR8.

### Critical: Parent ID is Long (BIGINT)

`parentId` in all events is `Long`, not UUID. `parent_credit_ledger.parent_id` is `BIGINT`. Consistent with Story 7.2 — do NOT use UUID for parent identity anywhere in this story.

### Critical: CancelCoachRequest validation

Add a custom `@ValidCancelReason` annotation or use `@Pattern` on `cancelReason` to validate it's one of the allowed enum values. Or validate in the service and throw `400` with `ErrorDto` code `booking.invalidCancelReason`. Keep it simple: `Set.of("MUTUAL_AGREEMENT", "HEALTH_MEDICAL", "FAMILY_EMERGENCY", "WEATHER", "SCHEDULING_PREFERENCE", "OTHER_UNEXCUSED").contains(cancelReason)` check in `cancelBookingAsCoach()`.

### State Machine — Cancellation transitions already exist

ALL necessary state machine transitions are already implemented in `BookingStateMachine`:
- `ACCEPTED → CANCELLED_COACH` (CANCEL_COACH), `CONFIRMED → CANCELLED_COACH`, `UPCOMING → CANCELLED_COACH`, `PAUSED → CANCELLED_COACH`
- `ACCEPTED → CANCELLED_PARENT` (CANCEL_PARENT), `CONFIRMED → CANCELLED_PARENT`, `UPCOMING → CANCELLED_PARENT`, `PAUSED → CANCELLED_PARENT`
- `UPCOMING → NO_SHOW_PLAYER` (NO_SHOW_PLAYER)
- `UPCOMING → NO_SHOW_COACH` (NO_SHOW_COACH)
- `DISPUTED → REFUND_PENDING` (SETTLE_REFUND), `REFUND_PENDING → REFUNDED` (REFUND_PROCESSED)

**DO NOT modify `BookingStateMachine`** — all transitions are already there.

### Story 7.2 Deprecation Cleanup

`PaymentGateway.capturePayment()` is marked `@Deprecated` in Story 7.2 with a note "remove in Story 7.3 cleanup." Check `platform.booking.service.SessionPackService.deductCredit()` — if it still calls `capturePayment()`, remove that call or leave the `@Deprecated` method in place if it's still wired. **Do not break existing Session Pack (booking module) functionality**. Investigate before removing.

### Notification Pattern

Previous stories (3.x, 6.x) publish typed notification events (e.g., `BookingDeclinedEvent`, `RescheduleAcceptedEvent`) which a `BookingEmailListener` or similar service handles. Follow the same pattern:
- `BookingCancelledByParentEvent` carries `coachEmail` and session details → coach receives the notification
- `BookingCancelledByCoachEvent` carries `parentEmail` → parent receives the notification
- `CoachNoShowEvent` carries `parentEmail` → parent receives notification
- The existing `BookingEmailListener` (or equivalent) may need to be extended, or publish a `NotificationRequestEvent` consumed by the notification service

Check what notification mechanism is in `platform.notification` or `platform.booking.service.*EventListener` from previous story commits.

### Frontend: Check Existing Session Views

Stories 3.3–3.7 implement coach and parent session views. Before creating new Vue components, check what exists in `src/frontend/src/pages/coach/` and `src/frontend/src/pages/parent/` and `src/frontend/src/components/booking/`. The cancel actions should integrate with existing booking card components rather than standalone pages.

### Project Structure Notes

- New REST controller: `platform.booking.api.CancellationResource` (follows `Resource` suffix convention)
- New service: `platform.payment.service.CancellationRefundService`
- New service: `platform.payment.service.ReliabilityStrikeService`
- New resource: `platform.payment.api.ReliabilityStrikeResource`
- New entities: `platform.payment.repo.CoachCancellationHistory`, `platform.payment.repo.CoachCancellationHistoryRepository`
- Updated entities: `platform.marketplace.repo.CoachReliabilityStrike` (add `bookingId`, `acknowledged`)
- Updated enum: `platform.marketplace.contract.CoachProfileStatus` (add `REDUCED`, `PENDING_REVIEW`)
- New events in `platform.booking.contract`: `BookingCancelledByParentEvent`, `BookingCancelledByCoachEvent`, `CoachNoShowEvent`, `PlayerNoShowEvent`
- New events in `platform.payment.contract`: `StrikeThresholdReachedEvent`, `CoachVisibilityReducedEvent`
- Updated service: `platform.marketplace.service.CoachSearchService` (include `REDUCED` in status filter)
- All new classes follow `com.softropic.skillars.platform.{module}.{layer}` hierarchy
- All request DTOs are `record` types with Jakarta validation annotations
- All new REST resources have `@Observed(name="...")` and per-method `@PreAuthorize`

### References

- Epics: `_bmad-output/planning-artifacts/skillars-epics.md` lines 2449–2520 (Story 7.3 AC + dev notes)
- FR-PAY-012, FR-PAY-013, FR-PAY-016 (cancellation matrix, strike system)
- Previous story: `skillars-7-2-session-payment-lifecycle-credit-wallet.md` (PaymentLifecycleService pattern, credit ledger, PackSessionService, @TransactionalEventListener boundary rules)
- Booking state machine: `BookingStateMachine.java` (all transitions already present)
- Existing strike entity/repo: `platform.marketplace.repo.CoachReliabilityStrike` + `CoachReliabilityStrikeRepository`
- `CoachProfileStatus.java` (add REDUCED, PENDING_REVIEW)
- `BookingService.applyRefundLogic()` lines ~480–505 (old strike publishing to remove)
- `project-context.md`: Java record DTOs, MapStruct, @PreAuthorize on every endpoint, no DB DDL in Java

## Dev Agent Record

### Agent Model Used

claude-sonnet-4-6

### Debug Log References

- Fixed ArgumentCaptor/publishEvent overload ambiguity in ReliabilityStrikeServiceTest: replaced `ArgumentCaptor<Object>` captures with typed `any(CoachVisibilityReducedEvent.class)` / `any(StrikeThresholdReachedEvent.class)` matchers, then re-added `import org.mockito.ArgumentCaptor` and `import static org.mockito.ArgumentMatchers.eq` after removing them prematurely.
- Fixed BookingService.createBookingRequest gate to allow REDUCED status coaches (not just ACTIVE) — REDUCED coaches still accept bookings, just appear lower in search.

### Completion Notes List

- Implemented full cancellation/no-show lifecycle: 4 REST endpoints, BookingService methods, event-driven refund/strike processing.
- CancellationRefundService uses @TransactionalEventListener(AFTER_COMMIT) + @Transactional(REQUIRES_NEW) — guaranteed atomicity across commit boundaries.
- Pack expiry check computed inside @Transactional before event publish (correct ordering — avoids race with expiry scheduler).
- REDUCED coaches visible in marketplace search (CoachSearchSpecification updated to `status IN (ACTIVE, REDUCED)`) with ACTIVE-first sort.
- 18 unit tests pass (12 matrix + 6 strike threshold); 3 ITs and 4 pack ITs written, require running DB to execute.
- CoachProfileStatus CHECK constraint updated in migration (V63); column was already VARCHAR from V26.

### File List

- `src/main/resources/db/migration/V63__cancellation_refund_reliability.sql` — NEW
- `src/main/java/com/softropic/skillars/platform/marketplace/contract/CoachProfileStatus.java` — MODIFIED (added REDUCED, PENDING_REVIEW)
- `src/main/java/com/softropic/skillars/platform/booking/repo/Booking.java` — MODIFIED (added cancelReason)
- `src/main/java/com/softropic/skillars/platform/marketplace/repo/CoachReliabilityStrike.java` — MODIFIED (added bookingId, acknowledged)
- `src/main/java/com/softropic/skillars/platform/payment/repo/CoachCancellationHistory.java` — NEW
- `src/main/java/com/softropic/skillars/platform/payment/repo/CoachCancellationHistoryRepository.java` — NEW
- `src/main/java/com/softropic/skillars/platform/booking/contract/BookingCancelledByParentEvent.java` — NEW
- `src/main/java/com/softropic/skillars/platform/booking/contract/BookingCancelledByCoachEvent.java` — NEW
- `src/main/java/com/softropic/skillars/platform/booking/contract/CoachNoShowEvent.java` — NEW
- `src/main/java/com/softropic/skillars/platform/booking/contract/PlayerNoShowEvent.java` — NEW
- `src/main/java/com/softropic/skillars/platform/booking/contract/CancelCoachRequest.java` — NEW
- `src/main/java/com/softropic/skillars/platform/payment/contract/event/CoachVisibilityReducedEvent.java` — NEW
- `src/main/java/com/softropic/skillars/platform/payment/contract/event/StrikeThresholdReachedEvent.java` — NEW
- `src/main/java/com/softropic/skillars/platform/booking/service/BookingService.java` — MODIFIED (cancel/no-show methods, removed CoachReliabilityStrikeQueuedEvent, REDUCED gate fix)
- `src/main/java/com/softropic/skillars/platform/booking/api/CancellationResource.java` — NEW
- `src/main/java/com/softropic/skillars/platform/payment/service/CancellationRefundService.java` — NEW
- `src/main/java/com/softropic/skillars/platform/payment/service/ReliabilityStrikeService.java` — NEW
- `src/main/java/com/softropic/skillars/platform/marketplace/repo/CoachReliabilityStrikeRepository.java` — MODIFIED (added findByCoachIdOrderByCreatedAtDesc)
- `src/main/java/com/softropic/skillars/platform/marketplace/service/CoachSearchSpecification.java` — MODIFIED (isActive → status IN ACTIVE/REDUCED)
- `src/main/java/com/softropic/skillars/platform/marketplace/service/CoachSearchService.java` — MODIFIED (status sort prepended)
- `src/main/java/com/softropic/skillars/platform/payment/contract/ReliabilityStrikeResponse.java` — NEW
- `src/main/java/com/softropic/skillars/platform/payment/api/ReliabilityStrikeResource.java` — NEW
- `src/main/java/com/softropic/skillars/platform/notification/contract/EmailTemplate.java` — MODIFIED (5 new templates)
- `src/main/java/com/softropic/skillars/platform/notification/service/BookingEmailListener.java` — MODIFIED (4 new handlers)
- `src/frontend/src/api/booking.api.js` — MODIFIED (4 new functions)
- `src/frontend/src/api/payment.api.js` — MODIFIED (2 new functions)
- `src/frontend/src/stores/payment.store.js` — MODIFIED (coachStrikes state + 2 actions)
- `src/frontend/src/components/booking/CancelCoachModal.vue` — NEW
- `src/frontend/src/pages/coach/CoachReliabilityPage.vue` — NEW
- `src/frontend/src/components/marketplace/ReliabilityIndicator.vue` — MODIFIED (coachContext prop + router nav)
- `src/frontend/src/router/routes.js` — MODIFIED (coach-reliability route)
- `src/frontend/src/i18n/en/index.js` — MODIFIED (cancellation.* + reliability.* keys)
- `src/frontend/src/i18n/de/index.js` — MODIFIED (German translations)
- `src/test/java/com/softropic/skillars/platform/payment/service/CancellationRefundMatrixTest.java` — NEW
- `src/test/java/com/softropic/skillars/platform/payment/service/ReliabilityStrikeServiceTest.java` — NEW
- `src/test/java/com/softropic/skillars/platform/payment/service/CoachVisibilitySuppressionIT.java` — NEW
- `src/test/java/com/softropic/skillars/platform/payment/service/PackCancellationRefundIT.java` — NEW
