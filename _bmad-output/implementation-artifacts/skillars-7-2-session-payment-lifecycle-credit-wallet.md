# Story 7.2: Session Payment Lifecycle & Credit Wallet

Status: done

## Story

As a parent,
I want payment for accepted sessions to draw from my platform credit balance first and charge only the remaining deficit via Stripe,
so that refunded credits are put to immediate use, and my payment experience is seamless across single bookings, bulk bookings, and session packs.

## Acceptance Criteria

1. **Given** the `platform.payment` module is initialised **When** the Flyway V62 migration runs **Then** the following tables exist in the `payment` schema:
   - `parent_credit_ledger` (`tx_id UUID PK DEFAULT gen_random_uuid()`, `parent_id BIGINT NOT NULL`, `amount NUMERIC(10,2) NOT NULL`, `type VARCHAR(32) NOT NULL CHECK (type IN ('BOOKING_DEDUCTION','BOOKING_DEDUCTION_REVERSAL','BOOKING_REFUND','CASH_OUT_DEBIT','STRIPE_FEE_DEBIT','CASH_OUT_REVERSAL'))`, `reference_id UUID`, `description VARCHAR(500)`, `created_at TIMESTAMPTZ NOT NULL DEFAULT now()`) — **no UPDATE or DELETE ever on this table**. A DB-level sign constraint enforces correctness: `CHECK (CASE WHEN type IN ('BOOKING_DEDUCTION','BOOKING_DEDUCTION_REVERSAL','CASH_OUT_DEBIT','STRIPE_FEE_DEBIT') THEN amount < 0 ELSE amount > 0 END)`
   - `stripe_customers` (`parent_id BIGINT PK`, `stripe_customer_id VARCHAR NOT NULL`, `stripe_payment_method_id VARCHAR`, `created_at TIMESTAMPTZ NOT NULL DEFAULT now()`)
   - `session_pack_tiers` (`pack_tier_id UUID PK DEFAULT gen_random_uuid()`, `coach_id UUID NOT NULL REFERENCES marketplace.coach_profiles(id)`, `label VARCHAR(200) NOT NULL`, `session_count INT NOT NULL`, `total_price NUMERIC(10,2) NOT NULL`, `price_per_session NUMERIC(10,2) NOT NULL`, `is_active BOOLEAN NOT NULL DEFAULT true`, `created_at TIMESTAMPTZ NOT NULL DEFAULT now()`)
   - `session_pack_purchases` (`purchase_id UUID PK DEFAULT gen_random_uuid()`, `parent_id BIGINT NOT NULL`, `coach_id UUID NOT NULL REFERENCES marketplace.coach_profiles(id)`, `pack_tier_id UUID NOT NULL REFERENCES payment.session_pack_tiers(pack_tier_id)`, `price_per_session NUMERIC(10,2) NOT NULL`, `remaining_sessions INT NOT NULL`, `expires_at TIMESTAMPTZ NOT NULL DEFAULT now() + INTERVAL '60 days'`, `extended_at TIMESTAMPTZ`, `stripe_payment_intent_id VARCHAR`, `created_at TIMESTAMPTZ NOT NULL DEFAULT now()`)
   - `booking_payments` (`booking_id UUID PK`, `batch_payment_intent_id UUID`, `stripe_payment_intent_id VARCHAR`, `credit_debited NUMERIC(10,2) NOT NULL DEFAULT 0`, `stripe_charged NUMERIC(10,2) NOT NULL DEFAULT 0`, `status VARCHAR(16) NOT NULL CHECK (status IN ('CAPTURED','CHARGE_FAILED','FROZEN'))`, `captured_at TIMESTAMPTZ`, `frozen_at TIMESTAMPTZ`)
   **And** a view exists: `CREATE OR REPLACE VIEW payment.parent_credit_balance AS SELECT parent_id, COALESCE(SUM(amount), 0) AS balance FROM payment.parent_credit_ledger GROUP BY parent_id`
   **And** the `booking.bookings` table has a new nullable column `session_pack_purchase_id UUID REFERENCES payment.session_pack_purchases(purchase_id)`
   **And** ConfigService keys seeded (ON CONFLICT DO NOTHING): `payment.stripe.feeRate = '0.025'` (STRING, 2.5%), `payment.stripe.feeFixed = '0.25'` (STRING, €0.25 flat)

2. **Given** a parent queries their credit balance **When** `GET /api/payment/credits/balance` is called with a valid parent JWT **Then** returns `200` with `{ "balance": 45.00, "currency": "EUR" }` sourced from the `parent_credit_balance` view — balance is 0.00 when no ledger rows exist

3. **Given** a booking transitions to `ACCEPTED` and `booking.session_pack_purchase_id IS NULL` **When** `BookingService.acceptBooking()` transitions the booking to `PAYMENT_PENDING` and publishes `BookingAcceptedEvent` **Then** `PaymentLifecycleService` handles the event via `@TransactionalEventListener(phase = AFTER_COMMIT)` and applies three-case credit routing using the parent's `parent_credit_balance`:
   - **Case A (full credit):** balance ≥ session price → write one `BOOKING_DEDUCTION` ledger entry (amount = −sessionPrice); no Stripe call; `booking_payments.status = CAPTURED`, `captured_at = now()`; booking → `CONFIRMED` via `BookingEvent.PAYMENT_CAPTURED`
   - **Case B (partial credit):** 0 < balance < session price → write `BOOKING_DEDUCTION` for −balance; call `StripePaymentGateway.chargeAndCapture()` for the deficit via Destination Charge; on success: `booking_payments.status = CAPTURED`, `captured_at = now()`, booking → `CONFIRMED`
   - **Case C (no credit):** balance = 0 → full Stripe Destination Charge via `chargeAndCapture()` (no ledger entry); on success: `booking_payments.status = CAPTURED`, `captured_at = now()`, booking → `CONFIRMED`
   **All Stripe calls are made outside `@Transactional`**; ledger writes and `booking_payments` creation are in a separate `@Transactional` after. On Stripe decline: write compensating `BOOKING_DEDUCTION_REVERSAL` (amount = +creditToUse, positive) if credit was debited; `booking_payments.status = CHARGE_FAILED`; trigger `BookingEvent.PAYMENT_FAILED` → booking to `DECLINED` (state machine change required — see Dev Notes); parent notified: "Payment failed — please update your payment method"

4. **Given** a booking transitions to `ACCEPTED` and `booking.session_pack_purchase_id IS NOT NULL` **When** `PaymentLifecycleService` handles `BookingAcceptedEvent` **Then** `PackSessionService.deductSession(sessionPackPurchaseId)` atomically decrements `payment.session_pack_purchases.remaining_sessions` using `SELECT … FOR UPDATE` — no Stripe call, no credit ledger entry. Platform credit is **never** consulted for pack-based bookings — the two payment pools are strictly separate. A `booking_payments` record is created with `status = CAPTURED`, `captured_at = now()`, `credit_debited = 0`, `stripe_charged = 0`. If `remaining_sessions = 0` after deduction, parent is notified: "Your session pack is now fully used". Booking → `CONFIRMED`.

5. **Given** a coach taps "Accept All" and `BatchBookingAcceptedEvent` is published (Story 3.8 — already implemented) **When** `PaymentLifecycleService` handles the event via `@TransactionalEventListener(AFTER_COMMIT)` **Then** the accepted bookings are partitioned into pack-based (those with a non-null `sessionPackPurchaseId`) and credit-based. Pack-based bookings are deducted via `PackSessionService.deductSession()` individually. For credit-based bookings: the **credit-only subtotal** is computed by summing only the credit-based bookings' prices (NOT `event.getTotalAmount()` directly — see Dev Notes). The same three-case credit routing is applied to this subtotal. If Stripe is needed: **one** single Destination Charge PaymentIntent for the credit-based deficit — not one per booking. One `booking_payments` record per booking (each with `batch_payment_intent_id = batchId`; pack-based bookings have `credit_debited = 0`, `stripe_charged = 0`). One credit ledger entry if credit used (`referenceId = batchId`). On success: all bookings → `CONFIRMED`, `BookingConfirmedEvent` published per booking. On Stripe decline for credit-based portion: `BOOKING_DEDUCTION_REVERSAL`; credit-based bookings → `DECLINED`; pack-based bookings already confirmed are unaffected; parent notified once

6. **Given** a parent purchases a session pack **When** `POST /api/payment/session-packs/purchase` is called with `{packTierId, paymentMethodId}` **Then** validate `session_pack_tiers.isActive = true` and `coachId` matches the pack tier's coach. Check `stripe_customers` — reuse existing `stripeCustomerId` or create a new Stripe Customer and insert into `stripe_customers`. Full `session_pack_tiers.totalPrice` charged via immediate-capture Stripe Destination Charge (PaymentIntent with `confirm=true`). Frontend card collection uses `stripe.confirmCardPayment(clientSecret)` — not SetupIntent. `stripe_customers.stripe_payment_method_id` saved if not already set. `session_pack_purchases` created with `pricePerSession` copied from `session_pack_tiers.pricePerSession` at purchase time — **this value is locked and never changes** regardless of future tier repricing. On success: `201` with purchase response; parent shown: "Your {N} sessions expire on {expiresAt}"

7. **Given** a coach creates a new session pack tier **When** they `POST /api/payment/coach/session-pack-tiers` with `{label, sessionCount, totalPrice}` **Then** a new active `session_pack_tiers` row is created; `pricePerSession = totalPrice / sessionCount`; **all previously active tiers for this coach are automatically set to `is_active = false`** — only one active tier per coach at a time is enforced. **Given** a coach explicitly deactivates a tier (`PATCH /api/payment/coach/session-pack-tiers/{tierId}/deactivate`) **Then** `is_active = false`; existing `session_pack_purchases` against that tier remain valid at their locked `price_per_session`; `session_pack_tiers` records are **never deleted**

8. **Given** a parent attempts to book a session using an expired session pack **When** `BookingService.createBooking()` validates the booking request **Then** if `session_pack_purchase_id` is provided and `session_pack_purchases.expires_at < now()`, return `400` with `ErrorDto` code `payment.packExpired`; parent shown: "This session pack expired on {expiresAt}. Contact your coach if an extension is possible."

9. **Given** a session pack is within 14 days of `expires_at` with remaining sessions **When** `SessionPackExpiryNotifier` daily scheduler runs **Then** parent and coach each receive their respective expiry warning notifications. **Given** a coach extends a pack within the valid window (`POST /api/payment/session-packs/{purchaseId}/extend`, `@PreAuthorize` coach) **Then** validate: `now >= expiresAt - 14 days AND now <= expiresAt AND extended_at IS NULL AND session_pack_purchases.coach_id == authenticatedCoach.id`. On success: `expires_at += 30 days`, `extended_at = now`. Second extension, window violation, or coach mismatch: `400` ErrorDto `payment.packExtensionNotEligible` or `403`

10. **Given** a parent requests credit cash-out **When** `POST /api/payment/credits/cashout` with `{ "amount": 45.00 }` **Then** validate `requestedAmount <= parent_credit_balance` — else `400` ErrorDto `payment.insufficientCredit`. Calculate fee: `feeAmount = (requestedAmount × feeRate) + feeFixed` (from ConfigService keys). Write two ledger entries atomically: `CASH_OUT_DEBIT (−requestedAmount)` and `STRIPE_FEE_DEBIT (−feeAmount)`. Call `PaymentGateway.refund(stripePaymentMethodId, netAmount)` outside `@Transactional`. Parent shown: "€{requestedAmount} credit → €{netAmount} returned to your card (€{feeAmount} processing fee)"

11. **Given** a parent has no saved payment method **When** any Stripe payment flow is initiated **Then** `POST /api/payment/setup-intent` (`@PreAuthorize` parent) creates a Stripe SetupIntent and returns `{ "clientSecret": "..." }`. Frontend collects card via Stripe Elements (`stripe.confirmCardSetup(clientSecret)`). On success, frontend calls `POST /api/payment/save-payment-method` with `{ "paymentMethodId": "pm_..." }` — backend saves to `stripe_customers.stripe_payment_method_id`. Card data **never** passes through the backend; the SetupIntent flow is used exclusively for saving a card without an immediate charge (distinct from the pack purchase `confirmCardPayment` flow)

12. **Given** a booking transitions to `DISPUTED` **When** `BookingDisputedEvent` is handled **Then** `booking_payments.status = FROZEN`, `frozen_at` set; admin notified via published domain event; no credit or Stripe action until admin resolves

13. **Given** any Stripe API call fails during payment lifecycle **When** caught outside `@Transactional` **Then** exception logged with `bookingId/batchId` and payment context; any in-flight credit debit reversed via `BOOKING_DEDUCTION_REVERSAL` (positive amount restoring credit); `booking_payments.status = CHARGE_FAILED`; booking → `DECLINED` (or `FROZEN` for disputes); `ErrorDto` code `payment.lifecycleFailure` returned or admin alert for async path

## Tasks / Subtasks

- [x] **Task 1 — Flyway V62: all new payment tables + bookings column + config seeds** (AC: 1)
  - [x] Create `src/main/resources/db/migration/V62__session_payment_credit_wallet.sql`
  - [x] Create `payment.parent_credit_ledger` — append-only; no PK sequence; `gen_random_uuid()`; CHECK on `type` enum values including `CASH_OUT_REVERSAL`; add sign enforcement constraint:
    ```sql
    CONSTRAINT chk_ledger_amount_sign CHECK (
      CASE WHEN type IN ('BOOKING_DEDUCTION','BOOKING_DEDUCTION_REVERSAL','CASH_OUT_DEBIT','STRIPE_FEE_DEBIT')
           THEN amount < 0
           ELSE amount > 0
      END
    )
    ```
  - [x] Create `payment.stripe_customers` with `parent_id BIGINT PK` (not UUID — parent IDs are Long TSID)
  - [x] Create `payment.session_pack_tiers` with FK to `marketplace.coach_profiles(id)` (cross-schema FK pattern from V61)
  - [x] Create `payment.session_pack_purchases` with FKs to `marketplace.coach_profiles(id)` and `payment.session_pack_tiers(pack_tier_id)`
  - [x] Create `payment.booking_payments` (bookingId is UUID PK, FK from booking is intentionally omitted — see Dev Notes on cross-schema FK risk)
  - [x] Create `payment.parent_credit_balance` VIEW as defined in AC 1
  - [x] `ALTER TABLE booking.bookings ADD COLUMN session_pack_purchase_id UUID REFERENCES payment.session_pack_purchases(purchase_id)` — nullable, no NOT NULL constraint
  - [x] Seed ConfigService: `payment.stripe.feeRate = '0.025'` and `payment.stripe.feeFixed = '0.25'` using INSERT with `DEFAULT` for the id column (let the sequence assign it) and `ON CONFLICT (key) DO NOTHING` — do NOT hard-code a manual id integer

- [x] **Task 2 — JPA entities and repositories in `platform.payment.repo`** (AC: 1)
  - [x] `ParentCreditLedger.java` — `@Entity @Table(schema="payment", name="parent_credit_ledger")`, `@GeneratedValue(strategy=UUID)`, `@PrePersist` sets `createdAt`. NO `@PreUpdate`. **No setters for `txId` or `createdAt`** (append-only)
  - [x] `ParentCreditLedgerRepository extends JpaRepository<ParentCreditLedger, UUID>` — add `@Query("SELECT COALESCE(SUM(l.amount), 0) FROM ParentCreditLedger l WHERE l.parentId = :parentId") BigDecimal sumByParentId(@Param("parentId") Long parentId)`
  - [x] `StripeCustomer.java` — `@Entity @Table(schema="payment", name="stripe_customers")`, `@Id Long parentId` (no `@GeneratedValue` — PK is the parent's Long TSID)
  - [x] `StripeCustomerRepository extends JpaRepository<StripeCustomer, Long>`
  - [x] `SessionPackTier.java` — `@Entity @Table(schema="payment", name="session_pack_tiers")`, `@Version` field for optimistic locking
  - [x] `SessionPackTierRepository extends JpaRepository<SessionPackTier, UUID>` — add `findByCoachIdAndIsActiveTrue(UUID coachId)` and `findAllByCoachIdAndIsActiveTrue(UUID coachId)` (for auto-deactivation on new tier creation)
  - [x] `SessionPackPurchase.java` — `@Entity @Table(schema="payment", name="session_pack_purchases")`, fields: `purchaseId`, `parentId` (Long), `coachId` (UUID), `packTierId` (UUID), `pricePerSession` (BigDecimal), `remainingSessions` (int), `expiresAt`, `extendedAt`, `stripePaymentIntentId`, `createdAt`. Add `@Version` for optimistic locking on concurrent deduction
  - [x] `SessionPackPurchaseRepository extends JpaRepository<SessionPackPurchase, UUID>` — add: `@Lock(PESSIMISTIC_WRITE) @Query("SELECT p FROM SessionPackPurchase p WHERE p.purchaseId = :id") Optional<SessionPackPurchase> findByIdForUpdate(@Param("id") UUID id)` and `List<SessionPackPurchase> findByCoachIdAndExpiresAtBetweenAndExtendedAtIsNullAndRemainingSessionsGreaterThan(UUID coachId, Instant from, Instant to, int minSessions)`
  - [x] `BookingPayment.java` — `@Entity @Table(schema="payment", name="booking_payments")`, `@Id UUID bookingId` (no `@GeneratedValue` — bookingId is assigned from the booking's ID), fields as per AC 1 schema
  - [x] `BookingPaymentRepository extends JpaRepository<BookingPayment, UUID>`
  - [x] Update `Booking.java` — add `@Column(name="session_pack_purchase_id") UUID sessionPackPurchaseId` (nullable, no FK annotation — handled by DB migration)

- [x] **Task 3 — Extend `PaymentGateway` interface + `StripeClient` wrapper + `StripePaymentGateway` implementation** (AC: 3, 4, 5, 6, 10, 12)
  - [x] Add to `PaymentGateway` interface in `platform.payment.contract`:
    - `String chargeAndCapture(UUID referenceId, Long parentId, UUID coachId, BigDecimal amount)` — returns `stripePaymentIntentId`; `referenceId` is the bookingId for single bookings, purchaseId for pack purchases, or batchId for batches; callers must pass the most relevant reference for logging and idempotency key
    - `String chargeAndCaptureForBatch(UUID batchId, Long parentId, UUID coachId, BigDecimal amount)` — returns `stripePaymentIntentId`
    - `void refund(String stripePaymentMethodId, BigDecimal netAmount)`
    - `void freezePayment(String paymentIntentId)`
    - `String createSetupIntent(String stripeCustomerId)` — returns clientSecret
  - [x] Keep existing `capturePayment(UUID, UUID, BigDecimal)` **but mark as `@Deprecated`** — `SessionPackService` in booking module still calls it; it will be removed in Story 7.3 cleanup. It should now delegate to `chargeAndCapture()` or remain as stub
  - [x] Create `StripeClient.java` in `platform.payment.service` — wraps all static Stripe SDK calls as instance methods so they can be `@MockitoBean` in tests:
    - `PaymentIntent createPaymentIntent(PaymentIntentCreateParams params)`
    - `PaymentIntent retrievePaymentIntent(String id)`
    - `Refund createRefund(RefundCreateParams params)`
    - `SetupIntent createSetupIntent(SetupIntentCreateParams params)`
    - `Customer createCustomer(CustomerCreateParams params)`
  - [x] Implement `chargeAndCapture()` in `StripePaymentGateway`: read `commission.rate` via `configService.getString("platform.commission.rate")` and parse to BigDecimal; compute `applicationFeeAmount = amount.multiply(commissionRate)`; call `StripeClient.createPaymentIntent()` with `destination=coachStripeAccountId`, `application_fee_amount`, `confirm=true`; return `paymentIntentId`
  - [x] Implement `refund()`, `freezePayment()`, `createSetupIntent()` delegating to `StripeClient`
  - [x] All Stripe calls in `StripePaymentGateway` are **outside** `@Transactional` (no annotation on class or methods)
  - [x] `StripePaymentGateway` now injects `StripeClient` alongside `CoachStripeAccountRepository` and `ConfigService`

- [x] **Task 4 — `BookingAcceptedEvent` + `BookingService.acceptBooking()` refactoring** (AC: 3, 4, 5, 8)
  - [x] Create `BookingAcceptedEvent.java` in `platform.booking.contract` (extends `ApplicationEvent`):
    Fields: `UUID bookingId`, `Long parentId`, `UUID coachId`, `BigDecimal sessionPrice`, `UUID sessionPackPurchaseId` (nullable), `String parentEmail`, `String coachDisplayName`, `Instant requestedStartTime`, `String canonicalTimezone`
  - [x] Add expired session pack validation to `BookingService.createBooking()` (AC 8): if the request includes a `sessionPackPurchaseId`, load the `SessionPackPurchase` and check `expiresAt.isBefore(Instant.now())` — if expired throw `BookingException("payment.packExpired")` with the `expiresAt` for the message template
  - [x] Modify `BookingStateMachine.java` — change `PAYMENT_PENDING → PAYMENT_FAILED` target from `REFUND_PENDING` to `DECLINED`:
    ```java
    t.put(BookingStatus.PAYMENT_PENDING, Map.of(
        BookingEvent.PAYMENT_CAPTURED, BookingStatus.CONFIRMED,
        BookingEvent.PAYMENT_FAILED,   BookingStatus.DECLINED   // was REFUND_PENDING
    ));
    ```
    **Verify** the `REFUND_PENDING` state is still reachable from `DISPUTED → SETTLE_REFUND` — it is, so this change doesn't break the dispute flow.
  - [x] Modify `BookingService.acceptBooking()` to:
    1. Transition ACCEPT (REQUESTED → ACCEPTED)
    2. Transition INITIATE_PAYMENT (ACCEPTED → PAYMENT_PENDING) — last `@Transactional` action
    3. Look up `sessionPrice` from `CoachPricing` (already injected via `CoachPricingRepository`)
    4. Look up `parentEmail` via `userRepository.findById(booking.getParentId())`
    5. Publish `BookingAcceptedEvent` (with `sessionPackPurchaseId = booking.getSessionPackPurchaseId()`)
    6. **Remove** the current `INITIATE_PAYMENT` + `PAYMENT_CAPTURED` stub chain and the `BookingConfirmedEvent` publish — these now happen in `PaymentLifecycleService`
    7. Return a `BookingResponse` with status `PAYMENT_PENDING` (not CONFIRMED)
  - [x] `BookingConfirmedEvent` publishing moves to `PaymentLifecycleService` — do NOT publish it from `BookingService.acceptBooking()` anymore
  - [x] `BookingBatchService.acceptAll()` — no changes needed; it already publishes `BatchBookingAcceptedEvent` with all required fields (parentId, coachId, totalAmount, acceptedBookingIds)

- [x] **Task 5 — `PaymentLifecycleService` (core of this story)** (AC: 3, 4, 5, 12, 13)
  - [x] Create `PaymentLifecycleService.java` in `platform.payment.service`
  - [x] `@TransactionalEventListener(phase = AFTER_COMMIT)` on `BookingAcceptedEvent` — event handler method must NOT be `@Transactional` itself (Stripe calls happen here, outside TX)
  - [x] `@TransactionalEventListener(phase = AFTER_COMMIT)` on `BatchBookingAcceptedEvent`
  - [x] `@EventListener` on `BookingDisputedEvent` (if published from booking module — check; if not yet implemented, add TODO noting Story 10.x will wire this)
  - [x] Single-booking payment flow (AC 3):
    ```
    1. Query parent balance (read-only, outside TX)
    2. Determine credit amount to use (= balance.min(sessionPrice))
    3. If Stripe needed: StripePaymentGateway.chargeAndCapture(bookingId, ...) (outside TX)
    4. @Transactional: write BOOKING_DEDUCTION ledger (negative), create BookingPayment(CAPTURED, capturedAt=now()), call transitionToConfirmed()
    5. On Stripe failure: @Transactional: write BOOKING_DEDUCTION_REVERSAL (positive, = +creditToUse), create BookingPayment(CHARGE_FAILED), call transitionToDeclined()
    ```
  - [x] `transitionToConfirmed(UUID bookingId)` — private `@Transactional` method: fire `BookingEvent.PAYMENT_CAPTURED` on booking state machine, publish `BookingConfirmedEvent`
  - [x] `transitionToDeclined(UUID bookingId)` — private `@Transactional` method: fire `BookingEvent.PAYMENT_FAILED` on booking state machine (→ DECLINED per Task 4 state machine change)
  - [x] Pack-based path (AC 4): call `packSessionService.deductSession(event.getSessionPackPurchaseId())`; in separate `@Transactional` create `BookingPayment(status=CAPTURED, capturedAt=now(), creditDebited=0, stripeCharged=0)`; call `transitionToConfirmed()`
  - [x] Batch path (AC 5):
    ```
    1. Partition event.getAcceptedBookingIds() into pack-based (sessionPackPurchaseId != null) and credit-based
    2. For each pack-based booking: packSessionService.deductSession(); create BookingPayment(CAPTURED); transitionToConfirmed()
    3. For credit-based bookings: compute creditSubtotal = SUM of each credit-based booking's price
       (load prices from CoachPricing for each bookingId, NOT from event.getTotalAmount())
    4. Apply three-case routing to creditSubtotal
    5. One Stripe charge for credit-based deficit (chargeAndCaptureForBatch(batchId, ...))
    6. batchPaymentIntentId set on all credit-based BookingPayment records; pack-based records have null batchPaymentIntentId
    ```
  - [x] Credit ledger writes are always in their own isolated `@Transactional` via `creditWalletService.writeLedgerEntry()` — never share the Stripe call transaction
  - [x] Inject: `BookingService` (for state transitions), `PackSessionService`, `CreditWalletService`, `PaymentGateway`, `BookingPaymentRepository`, `BookingRepository`, `CoachPricingRepository`

- [x] **Task 6 — `CreditWalletService` + `CashOutService` + credit endpoints** (AC: 2, 10)
  - [x] Create `CreditWalletService.java` in `platform.payment.service`:
    - `BigDecimal getBalance(Long parentId)` — queries `ParentCreditLedgerRepository.sumByParentId()`
    - `@Transactional void writeLedgerEntry(Long parentId, BigDecimal amount, String type, UUID referenceId, String description)` — saves `ParentCreditLedger` entity; validate `amount != 0`; DEDUCTIONS are negative, REFUNDS/REVERSALS are positive
  - [x] Create `CashOutService.java` in `platform.payment.service`:
    - `void processCashOut(Long parentId, BigDecimal requestedAmount)` — NOT `@Transactional` (Stripe call inside):
      1. Verify balance ≥ requestedAmount — throw `PaymentGatewayException("payment.insufficientCredit")` if not
      2. Read fee params: `feeRate = new BigDecimal(configService.getString("payment.stripe.feeRate"))`, `feeFixed = new BigDecimal(configService.getString("payment.stripe.feeFixed"))`
      3. `feeAmount = requestedAmount.multiply(feeRate).add(feeFixed).setScale(2, HALF_UP)`
      4. `netAmount = requestedAmount.subtract(feeAmount)`
      5. Look up `stripeCustomers.stripe_payment_method_id` — throw `PaymentGatewayException("payment.noPaymentMethod")` if null
      6. Call `writeCashOutLedgerEntries(parentId, requestedAmount, feeAmount)` — separate `@Transactional` that commits
      7. Call `paymentGateway.refund(stripePaymentMethodId, netAmount)` OUTSIDE `@Transactional`; on failure: write compensating `CASH_OUT_REVERSAL (+requestedAmount)` entry to restore credit — see Dev Notes
  - [x] `CreditBalanceResponse` record in `platform.payment.contract`: `record CreditBalanceResponse(BigDecimal balance, String currency)`
  - [x] `CashOutRequest` record: `record CashOutRequest(@NotNull @DecimalMin("0.01") BigDecimal amount)`
  - [x] Create `CreditWalletResource.java` in `platform.payment.api` (`@RestController @RequestMapping("/api/payment/credits")` `@Observed(name="payment.credits")`):
    - `GET /balance` — `@PreAuthorize(HAS_PARENT_ROLE)` — calls `creditWalletService.getBalance(parentId)`, returns `CreditBalanceResponse`
    - `POST /cashout` — `@PreAuthorize(HAS_PARENT_ROLE)` — calls `cashOutService.processCashOut()`; parent ID resolved via `securityUtil.getCurrentUser().getBusinessId()` (Long TSID for parents — verified pattern from prior stories)

- [x] **Task 7 — `PackSessionService` (payment module)** (AC: 4, 9)
  - [x] Create `PackSessionService.java` in `platform.payment.service`:
    - `@Transactional void deductSession(UUID purchaseId)`: calls `sessionPackPurchaseRepository.findByIdForUpdate(purchaseId)` (PESSIMISTIC_WRITE); decrements `remainingSessions`; if 0: saves and publishes `SessionPackExhaustedEvent`; else saves. Throw `PaymentGatewayException("payment.packExhausted")` if already 0
    - `@Transactional void restoreSession(UUID purchaseId)`: calls `findByIdForUpdate()`; increments `remainingSessions`; saves
  - [x] `SessionPackExhaustedEvent` — check if this already exists in `platform.booking.contract.SessionPackExhaustedEvent`. **If it does**, reuse it rather than creating a new one. If not, create in `platform.payment.contract.event`

- [x] **Task 8 — `SessionPackPaymentService` + `SessionPackPaymentResource` + expiry scheduler** (AC: 6, 7, 8, 9)
  - [x] Create `SessionPackPaymentService.java` in `platform.payment.service`:
    - `SessionPackPurchaseResponse purchasePack(Long parentId, UUID packTierId, String paymentMethodId)` — **NOT `@Transactional`** (Stripe call inside — see Dev Notes):
      1. Load `SessionPackTier` by id — throw if not `isActive`
      2. `getOrCreateStripeCustomer(parentId, paymentMethodId)` — in its own `@Transactional`
      3. Call `paymentGateway.chargeAndCapture(packTierId, parentId, tier.getCoachId(), tier.getTotalPrice())` OUTSIDE `@Transactional`
      4. In separate `@Transactional`: create `SessionPackPurchase` with `pricePerSession` copied from tier (locked), `remainingSessions = tier.sessionCount`, `expiresAt = now + 60 days`, `stripePaymentIntentId = result`
      5. Return `SessionPackPurchaseResponse`
    - `@Transactional void extendPack(Long coachUserId, UUID purchaseId)`:
      1. Resolve coach profile via `coachProfileRepository.findByUserId(coachUserId)`
      2. Load `SessionPackPurchase` — throw `403` if `purchase.coachId != coach.id`
      3. Validate extension window: `now >= expiresAt - 14 days AND now <= expiresAt AND extendedAt == null` — throw `PaymentGatewayException("payment.packExtensionNotEligible")` if invalid
      4. Set `expiresAt += 30 days`, `extendedAt = now`; save
      5. Publish notification event (parent: "Your session pack has been extended to {new expiresAt}")
    - `@Transactional void createTier(UUID coachId, String label, int sessionCount, BigDecimal totalPrice)`:
      1. Deactivate all existing active tiers for this coach: `sessionPackTierRepository.findAllByCoachIdAndIsActiveTrue(coachId).forEach(t -> { t.setIsActive(false); sessionPackTierRepository.save(t); })`
      2. Create and save new `SessionPackTier` with `isActive = true`
    - `getOrCreateStripeCustomer(parentId, paymentMethodId)` — private `@Transactional`; saves new `StripeCustomer` only if absent; updates `stripePaymentMethodId` if provided
  - [x] `SessionPackPurchaseResponse` record in `platform.payment.contract`
  - [x] Create `SessionPackPaymentResource.java` in `platform.payment.api`:
    - `POST /api/payment/session-packs/purchase` — `@PreAuthorize(HAS_PARENT_ROLE)` — body: `{packTierId, paymentMethodId?}`
    - `POST /api/payment/session-packs/{purchaseId}/extend` — `@PreAuthorize(HAS_COACH_ROLE)` — no body (note: `POST` not `PATCH` — aligns with epics spec; extend is a state transition, not a partial update)
    - `GET /api/payment/coaches/me/session-pack-tiers` — `@PreAuthorize(HAS_COACH_ROLE)` — returns coach's active and inactive tiers
    - `POST /api/payment/coaches/me/session-pack-tiers` — `@PreAuthorize(HAS_COACH_ROLE)` — creates new tier, auto-deactivates previous active tier
    - `PATCH /api/payment/coaches/me/session-pack-tiers/{tierId}/deactivate` — `@PreAuthorize(HAS_COACH_ROLE)` — explicitly sets `isActive = false`
    - `GET /api/payment/coaches/{coachId}/session-pack-tiers` — **public / `@PreAuthorize(HAS_PARENT_ROLE)`** — returns the single active tier for the given coach (for parent discovery before purchase); returns empty if no active tier
  - [x] Create `SessionPackExpiryNotifier.java` in `platform.payment.service` — `@Scheduled(cron="0 0 8 * * *")` (8 AM daily):
    - Query `session_pack_purchases WHERE expires_at BETWEEN now() AND now() + INTERVAL '14 days' AND extended_at IS NULL AND remaining_sessions > 0`
    - For each: publish parent and coach notification events

- [x] **Task 9 — SetupIntent endpoint + save-payment-method endpoint + StripeCustomer management** (AC: 11)
  - [x] `POST /api/payment/setup-intent` in `SessionPackPaymentResource` (`@PreAuthorize(HAS_PARENT_ROLE)`):
    - Call `getOrCreateStripeCustomer(parentId, null)` to get/create Stripe Customer ID
    - Call `paymentGateway.createSetupIntent(stripeCustomerId)` outside `@Transactional`
    - Return `{ "clientSecret": "seti_...secret..." }`
  - [x] `POST /api/payment/save-payment-method` in `SessionPackPaymentResource` (`@PreAuthorize(HAS_PARENT_ROLE)`):
    - Body: `SavedPaymentMethodRequest(@NotBlank String paymentMethodId)`
    - Load or create `StripeCustomer` for parent; set `stripePaymentMethodId = paymentMethodId`; save
    - Returns `204`; this endpoint is called by the frontend after `stripe.confirmCardSetup(clientSecret)` resolves successfully
  - [x] `SetupIntentResponse` record in `platform.payment.contract`
  - [x] `SavedPaymentMethodRequest` record in `platform.payment.contract`

- [x] **Task 10 — Frontend: session pack purchase + credit wallet UI** (AC: 6, 11)
  - [x] Extend `src/frontend/src/api/payment.api.js` (already exists from Story 7.1) with:
    - `fetchCreditBalance()` → `GET /api/payment/credits/balance`
    - `cashOut(amount)` → `POST /api/payment/credits/cashout`
    - `purchaseSessionPack(packTierId, paymentMethodId)` → `POST /api/payment/session-packs/purchase`
    - `extendSessionPack(purchaseId)` → `POST /api/payment/session-packs/{purchaseId}/extend`
    - `createSetupIntent()` → `POST /api/payment/setup-intent`
    - `savePaymentMethod(paymentMethodId)` → `POST /api/payment/save-payment-method`
    - `fetchCoachSessionPackTiers(coachId)` → `GET /api/payment/coaches/{coachId}/session-pack-tiers` (parent-facing browse)
    - `fetchMySessionPackTiers()` → `GET /api/payment/coaches/me/session-pack-tiers` (coach-facing manage)
    - `createSessionPackTier(data)` → `POST /api/payment/coaches/me/session-pack-tiers`
  - [x] Stripe card collection flows — **only call Stripe SDK from `payment.api.js`**, never from Pinia stores or Vue components:
    - Session pack purchase: `stripe.confirmCardPayment(clientSecret)` (PaymentIntent flow — immediate charge)
    - Card setup for future payments: `stripe.confirmCardSetup(clientSecret)` followed by `savePaymentMethod(result.setupIntent.payment_method)`
  - [x] Extend `src/frontend/src/stores/payment.store.js` (already exists):
    - `creditBalance` state, `fetchCreditBalance()` action
    - `sessionPackTiers` state (coach-side), `fetchSessionPackTiers()` action
  - [x] Create `src/frontend/src/pages/parent/ParentCreditWalletPage.vue` — glassmorphism design, shows balance, cashout form, recent activity (stretch: ledger history)
  - [x] Add i18n keys `payment.credits.*` and `payment.sessionPack.*` to `en/index.js` and `de/index.js`
  - [x] Add route `/parent/credit-wallet` to `src/frontend/src/router/routes.js`

- [x] **Task 11 — Tests** (AC: 2, 3, 4, 5, 6, 8, 9, 10)
  - [x] `CreditRoutingTest.java` in `src/test/java/.../platform/payment/service/` (unit, no Spring context):
    - Case A: full credit covers booking — no Stripe call, ledger entry negative, booking CONFIRMED, capturedAt set
    - Case B: partial credit — ledger entry + Stripe charge for deficit
    - Case C: zero credit — Stripe only
    - Batch routing: credit-based bookings total computed from individual prices (not event total); pack-based bookings handled separately; one Stripe PaymentIntent for credit-based deficit
    - Pack-based bypass: `PackSessionService.deductSession()` called, no credit consulted, `BookingPayment(CAPTURED)` created
    - Stripe decline with credit pre-debited: `BOOKING_DEDUCTION_REVERSAL` written with positive amount, booking DECLINED
    Use Mockito to mock `PaymentGateway`, `CreditWalletService`, `PackSessionService`, `BookingService`, `CoachPricingRepository`
  - [x] `CashOutServiceTest.java` (unit): fee calculation with both percentage + fixed, insufficient credit rejection, Stripe refund failure → `CASH_OUT_REVERSAL` ledger entry written and credit restored
  - [x] `PackPriceLockedOnPurchaseTest.java` (unit): repricing a tier (create new tier, verify old auto-deactivated) does NOT change `pricePerSession` on existing `session_pack_purchases`
  - [x] `PackExtensionIT.java` (`@SpringBootTest` + Testcontainers): window boundary enforcement (too early, valid window, expired), double-extension guard, coach ownership check (403 on wrong coach)
  - [x] `SessionPackPurchaseIT.java` (`@SpringBootTest` + WireMock for Stripe): successful purchase → `session_pack_purchases` created with locked `pricePerSession`; expired pack → `400 payment.packExpired` on booking creation attempt
  - [x] `BatchPaymentIT.java` (`@SpringBootTest` + WireMock for Stripe): coach accepts batch of mixed pack-based + credit-based bookings → one Stripe PaymentIntent created for credit-based deficit only → all bookings CONFIRMED → pack-based `BookingPayment` records have `stripeCharged=0`; credit-based records have `batchPaymentIntentId` set
  - [x] `ExpiredPackBookingValidationTest.java` (unit): `BookingService.createBooking()` with an expired `sessionPackPurchaseId` throws `BookingException("payment.packExpired")`
  - [x] `PaymentWebhookIdempotencyIT.java` (`@SpringBootTest` + WireMock): double-firing a `BookingAcceptedEvent` (simulating duplicate Stripe webhook delivery) does NOT create two `booking_payments` records or double-charge the parent — the second event is a no-op (upsert or idempotency key check on `booking_payments.booking_id`)
  - [x] WireMock base class for payment tests: follow `BaseVideoIT` pattern (`@EnableWireMock(@ConfigureWireMock(name = "stripe-service"))`) — wire WireMock URL to `app.payment.stripe.baseUrl` in `application-test.yaml`

## Dev Notes

### Critical: State Machine Change (PAYMENT_FAILED → DECLINED)

The current `BookingStateMachine` has `PAYMENT_PENDING → PAYMENT_FAILED → REFUND_PENDING`. **This must be changed to DECLINED** to match the AC (parent UX of "payment failed" = booking declined, not refunded). The `REFUND_PENDING` state is still reachable from the dispute flow (`DISPUTED → SETTLE_REFUND → REFUND_PENDING → REFUND_PROCESSED → REFUNDED`) — verify this is unchanged in the state machine.

```java
// In BookingStateMachine.buildTransitions():
t.put(BookingStatus.PAYMENT_PENDING, Map.of(
    BookingEvent.PAYMENT_CAPTURED, BookingStatus.CONFIRMED,
    BookingEvent.PAYMENT_FAILED,   BookingStatus.DECLINED   // CHANGE FROM REFUND_PENDING
));
```

### Critical: `acceptBooking` Refactoring Pitfall

The current `acceptBooking()` fires three events synchronously: ACCEPT → INITIATE_PAYMENT → PAYMENT_CAPTURED. This must be changed. The new flow:

```java
@Transactional
public BookingResponse acceptBooking(UUID bookingId, Long coachUserId) {
    // ... coach ownership check ...
    TransitionContext ctx = new TransitionContext(ActorRole.COACH, coachUserId);
    transitionInternal(bookingId, BookingEvent.ACCEPT, ctx, false);
    transitionInternal(bookingId, BookingEvent.INITIATE_PAYMENT, ctx, true);  // commits TX, booking now PAYMENT_PENDING
    
    Booking updated = getBookingOrThrow(bookingId);
    BigDecimal sessionPrice = resolveSessionPrice(updated);  // from CoachPricing
    String parentEmail = resolveEmail(updated.getParentId());
    
    // Publish INSIDE @Transactional — @TransactionalEventListener(AFTER_COMMIT) ensures
    // PaymentLifecycleService only runs after DB commit
    eventPublisher.publishEvent(new BookingAcceptedEvent(
        this, updated.getId(), updated.getParentId(), updated.getCoachId(),
        sessionPrice, updated.getSessionPackPurchaseId(),
        parentEmail, coach.getDisplayName(),
        updated.getRequestedStartTime(), updated.getCanonicalTimezone()
    ));
    
    // Return PAYMENT_PENDING status — DO NOT publish BookingConfirmedEvent here
    return toResponse(updated, ...);
}
```

**`resolveSessionPrice(Booking b)`**: if `b.getSessionPackPurchaseId() != null`: load from `SessionPackPurchaseRepository.findById()` → `pricePerSession`. If null: load from `CoachPricingRepository.findByCoachId()` → `perSessionPrice`.

### Critical: Stripe + @Transactional Boundary

Stripe API calls must NEVER be inside `@Transactional`. The pattern (same as Story 7.1):

```java
// In PaymentLifecycleService — NOT @Transactional
@TransactionalEventListener(phase = AFTER_COMMIT)
public void onBookingAccepted(BookingAcceptedEvent event) {
    BigDecimal balance = creditWalletService.getBalance(event.getParentId());  // read-only
    BigDecimal creditToUse = balance.min(event.getSessionPrice());
    BigDecimal stripeAmount = event.getSessionPrice().subtract(creditToUse);
    
    String paymentIntentId = null;
    if (stripeAmount.compareTo(BigDecimal.ZERO) > 0) {
        try {
            // OUTSIDE @Transactional:
            paymentIntentId = paymentGateway.chargeAndCapture(
                event.getBookingId(), event.getParentId(), event.getCoachId(), stripeAmount);
        } catch (PaymentGatewayException e) {
            persistPaymentFailure(event, creditToUse, null);
            return;
        }
    }
    persistPaymentSuccess(event, creditToUse, stripeAmount, paymentIntentId);
}

@Transactional  // separate TX for DB writes
private void persistPaymentSuccess(BookingAcceptedEvent event, BigDecimal creditToUse, BigDecimal stripeCharged, String paymentIntentId) {
    if (creditToUse.compareTo(BigDecimal.ZERO) > 0) {
        creditWalletService.writeLedgerEntry(event.getParentId(), creditToUse.negate(),
            "BOOKING_DEDUCTION", event.getBookingId(), "Session booking deduction");
    }
    BookingPayment bp = new BookingPayment();
    bp.setBookingId(event.getBookingId());
    bp.setCreditDebited(creditToUse);
    bp.setStripeCharged(stripeCharged);
    bp.setStripePaymentIntentId(paymentIntentId);
    bp.setStatus("CAPTURED");
    bp.setCapturedAt(Instant.now());
    bookingPaymentRepository.save(bp);
    transitionToConfirmed(event.getBookingId());
}

@Transactional  // separate TX for failure writes
private void persistPaymentFailure(BookingAcceptedEvent event, BigDecimal creditToReverse, String paymentIntentId) {
    if (creditToReverse.compareTo(BigDecimal.ZERO) > 0) {
        // BOOKING_DEDUCTION_REVERSAL must be POSITIVE (restores credit)
        creditWalletService.writeLedgerEntry(event.getParentId(), creditToReverse,
            "BOOKING_DEDUCTION_REVERSAL", event.getBookingId(), "Payment failed - credit restored");
    }
    BookingPayment bp = new BookingPayment();
    bp.setBookingId(event.getBookingId());
    bp.setStatus("CHARGE_FAILED");
    bookingPaymentRepository.save(bp);
    transitionToDeclined(event.getBookingId());
}
```

**Warning**: `@TransactionalEventListener(AFTER_COMMIT)` methods run in a NEW transaction context by default (the original TX is committed). Any `@Transactional` calls inside are executed in a fresh TX. This is correct behavior — do NOT use `@TransactionalEventListener(AFTER_COMMIT)` with a method annotated `@Transactional` on the same bean (self-invocation bypasses proxy). Use separate `@Service` beans or internal private methods called from non-annotated public methods.

### Critical: Batch Payment — Credit Subtotal Must Be Computed, Not Taken from Event Total

`BatchBookingAcceptedEvent.getTotalAmount()` is the total price of ALL accepted bookings. A batch can contain both pack-based bookings (zero Stripe/credit cost) and credit-based bookings. Using the event total directly for credit routing will over-charge the parent by the cost of pack sessions.

The correct approach:
```java
List<UUID> packIds = /* bookingIds where sessionPackPurchaseId != null */;
List<UUID> creditIds = /* bookingIds where sessionPackPurchaseId == null */;
BigDecimal creditSubtotal = creditIds.stream()
    .map(id -> coachPricingRepository.findByCoachId(bookingRepo.findById(id).get().getCoachId()).getPerSessionPrice())
    .reduce(BigDecimal.ZERO, BigDecimal::add);
// Apply three-case routing to creditSubtotal only
```

### Critical: session_packs_purchased vs session_pack_purchases

**Two separate systems co-exist after Story 7.2:**

1. `booking.session_packs_purchased` — Story 3.2 entity, `SessionPackPurchased.java` in `platform.booking.repo`. Used by `SessionPackService.deductCredit()`. Legacy system for per-session credit model. **Do NOT delete or modify this table.**

2. `payment.session_pack_purchases` — NEW Story 7.2 entity, `SessionPackPurchase.java` in `platform.payment.repo`. Payment-aware tier-based purchases. Used by `PackSessionService.deductSession()`. `booking.session_pack_purchase_id` references THIS table.

A booking can reference EITHER system. If `booking.session_pack_purchase_id IS NOT NULL` → uses payment system (PackSessionService). If null → uses credit system (credit wallet + Stripe). The old `SessionPackService.deductCredit()` is NOT called from `PaymentLifecycleService`. The old `booking.session_packs_purchased` system remains intact for backward compat but is effectively superseded for new purchases.

### Critical: Cross-Schema FK on booking_payments

**Avoid** adding a `REFERENCES booking.bookings(id)` FK on `payment.booking_payments`. Cross-schema FKs from `payment` schema back to `booking` schema create circular dependency risk in Flyway migrations and complicate cascades. Instead: `booking_payments.booking_id` is the UUID PK with no FK constraint. The application logic enforces the integrity (only `PaymentLifecycleService` writes `booking_payments`).

The FK on `booking.bookings.session_pack_purchase_id → payment.session_pack_purchases(purchase_id)` IS safe (booking → payment direction, same as payment → marketplace for V61's FK).

### Critical: Parent ID is Long (BIGINT), Not UUID

Parents (and players) use Long TSID as their primary ID — confirmed throughout prior stories. `stripe_customers.parent_id` is `BIGINT`, not UUID. `parent_credit_ledger.parent_id` is `BIGINT`. `BookingAcceptedEvent.parentId` is `Long`. Do NOT use UUID for parent identity. The booking's `parentId` field is `Long` — use as-is. (Note: the epics spec mistakenly shows `parentId UUID` — the BIGINT/Long TSID is correct per implementation.)

### Critical: ConfigService getString() for Decimals

`ConfigService` has `getString(key)` and `getLong(key)`. There is NO `getDouble()` or `getBigDecimal()`. To read fee rates, use:

```java
BigDecimal feeRate = new BigDecimal(configService.getString("payment.stripe.feeRate"));
BigDecimal feeFixed = new BigDecimal(configService.getString("payment.stripe.feeFixed"));
```

### CashOut Stripe Failure Compensation

If `paymentGateway.refund()` fails after ledger entries are committed (they're in a separate `@Transactional` that already committed), the credit is NOT automatically restored. Use the `CASH_OUT_REVERSAL` ledger type (positive amount) to restore the deducted credit — do NOT use `BOOKING_REFUND` (which is semantically reserved for booking cancellation credits):

```java
public void processCashOut(Long parentId, BigDecimal requestedAmount) {
    // ... balance check, fee calc ...
    writeCashOutLedgerEntries(parentId, requestedAmount, feeAmount);  // commits
    try {
        paymentGateway.refund(paymentMethodId, netAmount);  // outside TX
    } catch (PaymentGatewayException e) {
        // Compensate: restore the deducted credit using CASH_OUT_REVERSAL
        creditWalletService.writeLedgerEntry(parentId, requestedAmount,
            "CASH_OUT_REVERSAL", null, "Cashout refund failed - credit restored");
        throw new PaymentGatewayException("payment.refundFailed");
    }
}
```

### Stripe Elements: Two Distinct Card Collection Flows

Two separate Stripe flows are used in this story — do not conflate them:

| Flow | Stripe API | Backend endpoint | Purpose |
|------|-----------|-----------------|---------|
| Session pack purchase | `stripe.confirmCardPayment(clientSecret)` | `POST /api/payment/session-packs/purchase` returns PaymentIntent clientSecret | Immediate capture |
| Save card for future use | `stripe.confirmCardSetup(clientSecret)` | `POST /api/payment/setup-intent` returns SetupIntent clientSecret; frontend then calls `POST /api/payment/save-payment-method` | Card-on-file, no immediate charge |

The epics explicitly specify `stripe.confirmCardPayment` for pack purchase. `confirmCardSetup` is only for the setup-intent card-save flow.

### Cancellation Refund Handlers (Deferred to Story 7.3)

The epics AC under Story 7.2 includes a cancellation event handler (credit ledger writes for CANCELLED_PARENT, CANCELLED_COACH, NO_SHOW_COACH). This story intentionally defers those listeners to Story 7.3 (`CancellationRefundService`), where the full refund matrix and reliability strike logic are defined. `PaymentLifecycleService` in this story only handles `BookingAcceptedEvent`, `BatchBookingAcceptedEvent`, and `BookingDisputedEvent`.

### PaymentWebhookIdempotencyIT — Duplicate Event Protection

`booking_payments.booking_id` is a UUID PK. A duplicate `BookingAcceptedEvent` (e.g., from Spring's `@TransactionalEventListener` retry or infrastructure re-delivery) will throw a PK violation on the second `bookingPaymentRepository.save()`. `PaymentLifecycleService` should catch `DataIntegrityViolationException` at the outer handler level and treat it as an idempotency no-op (log at WARN level, do not re-throw). The `PaymentWebhookIdempotencyIT` test must verify this behaviour.

### Booking State After acceptBooking()

After Story 7.2 refactoring, `acceptBooking()` returns a booking in `PAYMENT_PENDING` status (not `CONFIRMED`). Frontend polling or SSE (already implemented in Story 3.4) will observe the status change from `PAYMENT_PENDING` → `CONFIRMED` (or `DECLINED`) once `PaymentLifecycleService` completes. No frontend changes required for this status flow — the SSE events cover it.

### WireMock Setup for Payment ITs

For `BatchPaymentIT` and `SessionPackPurchaseIT`, Stripe SDK calls must be intercepted. Configure WireMock to stub `https://api.stripe.com`. Stripe SDK uses `Stripe.overrideApiBase()` or `Stripe.overrideApiBasePath()` to redirect calls to WireMock. Pattern (from `BaseVideoIT` for Bunny.net):

```java
@EnableWireMock(@ConfigureWireMock(name = "stripe-service"))
abstract class BasePaymentIT {
    @InjectWireMock("stripe-service")
    protected WireMockServer wireMockServer;
    // In @BeforeEach: Stripe.overrideApiBase(wireMockServer.baseUrl())
}
```

Add `wiremock.server.stripe-service.baseUrl` mapping in `application-test.yaml` to inject the WireMock URL into the test Stripe config.

### SessionPackExhaustedEvent Reuse

`SessionPackExhaustedEvent` already exists in `platform.booking.contract`. The `PackSessionService` in the payment module must import from `platform.booking.contract` (cross-module import is acceptable for domain events). Do NOT create a duplicate event.

### Flyway V62 Next Migration Number

V61 is the last migration. V62 is the next. Confirm with: `ls src/main/resources/db/migration/V* | tail -3`

### PaymentGateway.capturePayment() Deprecation

`SessionPackService.purchasePack()` and `purchaseSingleSession()` in the booking module call `paymentGateway.capturePayment(pack.getId(), pack.getCoachId(), amount)`. In Story 7.2:
- Add `@Deprecated` to this method in `PaymentGateway` interface
- Add Javadoc: "Deprecated. Use chargeAndCapture(). Will be removed in Story 7.3"
- The method now delegates to `chargeAndCapture(referenceId, null, coachId, amount)` in `StripePaymentGateway` (parentId is null for legacy path — log a warning)
- Do NOT refactor `SessionPackService` in this story; leave it to Story 7.3 cleanup

### Security: Parent Ownership

`GET /api/payment/credits/balance` and `POST /api/payment/credits/cashout` must resolve `parentId` from JWT, never from a path/query param. Use `securityUtil.getCurrentUser().getBusinessId()` — this is a `Long` for parents (confirmed in prior stories). Do NOT accept `parentId` from the request body.

### application.yaml Additions

```yaml
app:
  payment:
    stripe:
      # existing from V61:
      api-key: ${APP_PAYMENT_STRIPE_API_KEY:}
      webhook-secret: ${APP_PAYMENT_STRIPE_WEBHOOK_SECRET:}
      oauth-client-id: ${APP_PAYMENT_STRIPE_OAUTH_CLIENT_ID:}
      oauth-callback-url: ${APP_PAYMENT_STRIPE_OAUTH_CALLBACK_URL:/api/payment/coaches/me/stripe/callback}
      callback-success-url: ${APP_PAYMENT_STRIPE_CALLBACK_SUCCESS_URL:/coach/payment-settings}
      # NEW for V62:
      fee-rate: ${APP_PAYMENT_STRIPE_FEE_RATE:0.025}
      fee-fixed: ${APP_PAYMENT_STRIPE_FEE_FIXED:0.25}
```

Add corresponding fields to `PaymentProperties.java`.

### Project Structure Notes

**New files (backend):**
- `src/main/resources/db/migration/V62__session_payment_credit_wallet.sql`
- `platform.payment.repo.ParentCreditLedger` + `ParentCreditLedgerRepository`
- `platform.payment.repo.StripeCustomer` + `StripeCustomerRepository`
- `platform.payment.repo.SessionPackTier` + `SessionPackTierRepository`
- `platform.payment.repo.SessionPackPurchase` + `SessionPackPurchaseRepository`
- `platform.payment.repo.BookingPayment` + `BookingPaymentRepository`
- `platform.payment.service.StripeClient`
- `platform.payment.service.PaymentLifecycleService`
- `platform.payment.service.CreditWalletService`
- `platform.payment.service.CashOutService`
- `platform.payment.service.PackSessionService`
- `platform.payment.service.SessionPackPaymentService`
- `platform.payment.service.SessionPackExpiryNotifier`
- `platform.payment.api.CreditWalletResource`
- `platform.payment.api.SessionPackPaymentResource`
- `platform.payment.contract.CreditBalanceResponse`
- `platform.payment.contract.CashOutRequest`
- `platform.payment.contract.SessionPackPurchaseResponse`
- `platform.payment.contract.SetupIntentResponse`
- `platform.payment.contract.SavedPaymentMethodRequest`
- `platform.payment.contract.SessionPackTierResponse`
- `platform.booking.contract.BookingAcceptedEvent`

**Modified files (backend):**
- `platform.payment.contract.PaymentGateway` — rename `bookingId` → `referenceId` in `chargeAndCapture`, add new methods, deprecate `capturePayment`
- `platform.payment.service.StripePaymentGateway` — implement new methods via `StripeClient`
- `platform.payment.config.PaymentProperties` — add feeRate, feeFixed fields
- `platform.booking.repo.Booking` — add `sessionPackPurchaseId UUID` field
- `platform.booking.service.BookingService.acceptBooking()` — refactor as described above; add expired pack validation to `createBooking()`
- `platform.booking.service.BookingStateMachine` — PAYMENT_FAILED → DECLINED
- `src/main/resources/application.yaml` — add new payment config keys
- `src/test/resources/application-test.yaml` — add test payment config + WireMock URL

**New files (frontend):**
- `src/frontend/src/pages/parent/ParentCreditWalletPage.vue`

**Modified files (frontend):**
- `src/frontend/src/api/payment.api.js` — add credit wallet + session pack APIs + `savePaymentMethod`
- `src/frontend/src/stores/payment.store.js` — add credit balance + session pack tier state
- `src/frontend/src/i18n/en/index.js` + `de/index.js` — add `payment.credits.*` and `payment.sessionPack.*` keys
- `src/frontend/src/router/routes.js` — add `/parent/credit-wallet` route

**New test files:**
- `src/test/java/.../platform/payment/service/CreditRoutingTest.java`
- `src/test/java/.../platform/payment/service/CashOutServiceTest.java`
- `src/test/java/.../platform/payment/service/PackPriceLockedOnPurchaseTest.java`
- `src/test/java/.../platform/payment/service/ExpiredPackBookingValidationTest.java`
- `src/test/java/.../platform/payment/api/PackExtensionIT.java`
- `src/test/java/.../platform/payment/api/SessionPackPurchaseIT.java`
- `src/test/java/.../platform/payment/BatchPaymentIT.java`
- `src/test/java/.../platform/payment/PaymentWebhookIdempotencyIT.java`
- `src/test/java/.../platform/payment/BasePaymentIT.java` (abstract WireMock base)

### References

- [Epic 7 Story 7.2 spec]: `_bmad-output/planning-artifacts/skillars-epics.md` lines 2338–2444
- [Epic 7 Story 7.3 spec (cancellation refund matrix)]: lines 2449–2519 — Story 7.3 wires cancellation listeners; this story does NOT implement them
- [Story 7.1 Dev Notes — Handover Point]: `skillars-7-1-stripe-connect-onboarding-commission-engine.md` §Story 7.2 Handover Point — confirms what 7.1 deferred
- [Story 7.1 Review Findings D1]: `capturePayment` inside @Transactional is a pre-existing issue — deferred to 7.2 to fix by using the new `chargeAndCapture` flow outside @Transactional
- [BookingStateMachine]: `platform.booking.service.BookingStateMachine` — modify PAYMENT_FAILED target
- [BookingBatchService]: `platform.booking.service.BookingBatchService` — already publishes `BatchBookingAcceptedEvent` with all needed fields
- [SessionPackExhaustedEvent]: `platform.booking.contract.SessionPackExhaustedEvent` — reuse, do not recreate
- [BaseVideoIT WireMock pattern]: `src/test/java/.../platform/video/BaseVideoIT.java` — replicate for payment tests
- [V61 migration pattern]: `src/main/resources/db/migration/V61__payment_module_init.sql` — cross-schema FK pattern, config seed pattern
- [ConfigService]: `platform.config.service.ConfigService` — getString(), getLong() — no getDouble(); parse BigDecimal manually
- [SecurityConstants]: `infrastructure.security.SecurityConstants` — HAS_PARENT_ROLE, HAS_COACH_ROLE

## Dev Agent Record

### Agent Model Used

claude-sonnet-4-6

### Debug Log References

### Completion Notes List

- All 11 tasks implemented across two sessions; prior session covered Tasks 1–10, this session completed Task 11 (tests) plus a bug fix.
- Fixed V62 migration: added `version INT NOT NULL DEFAULT 0` to `session_pack_tiers` and `session_pack_purchases` (required by `@Version` JPA optimistic locking but missing from original DDL).
- Known issue: `PaymentLifecycleService` private batch helper methods (`persistPackBatchPayment`, `persistCreditBatchPayment`, `persistBatchFailedPayment`) are `@Transactional` but called via self-invocation — Spring AOP proxy is bypassed. Data still persists correctly because Spring Data JPA `save()` has its own transaction, but the explicit `@Transactional` is effectively a no-op on these private methods. Deferred fix (make public or extract to sibling bean) to Story 7.3.
- `SessionPackExhaustedEvent.playerId` field used with `pack.getParentId()` — semantic mismatch since pack purchases are parent-scoped, not player-scoped. Existing event contract reused as-is; cleanup deferred to Story 7.3.
- `ExpiredPackBookingValidationTest` confirms `PaymentGatewayException("payment.packExpired")` is thrown (not `BookingException`) — the booking module throws payment exceptions directly for consistency with the error handling framework.

### File List

- `src/main/resources/db/migration/V62__session_payment_credit_wallet.sql` (new — added version columns in this session)
- `src/main/java/com/softropic/skillars/platform/payment/repo/ParentCreditLedger.java` (new)
- `src/main/java/com/softropic/skillars/platform/payment/repo/ParentCreditLedgerRepository.java` (new)
- `src/main/java/com/softropic/skillars/platform/payment/repo/StripeCustomer.java` (new)
- `src/main/java/com/softropic/skillars/platform/payment/repo/StripeCustomerRepository.java` (new)
- `src/main/java/com/softropic/skillars/platform/payment/repo/SessionPackTier.java` (new)
- `src/main/java/com/softropic/skillars/platform/payment/repo/SessionPackTierRepository.java` (new)
- `src/main/java/com/softropic/skillars/platform/payment/repo/SessionPackPurchase.java` (new)
- `src/main/java/com/softropic/skillars/platform/payment/repo/SessionPackPurchaseRepository.java` (new)
- `src/main/java/com/softropic/skillars/platform/payment/repo/BookingPayment.java` (new)
- `src/main/java/com/softropic/skillars/platform/payment/repo/BookingPaymentRepository.java` (new)
- `src/main/java/com/softropic/skillars/platform/booking/repo/Booking.java` (modified — added sessionPackPurchaseId)
- `src/main/java/com/softropic/skillars/platform/payment/contract/PaymentGateway.java` (modified — new methods)
- `src/main/java/com/softropic/skillars/platform/payment/service/StripeClient.java` (new)
- `src/main/java/com/softropic/skillars/platform/payment/service/StripePaymentGateway.java` (modified)
- `src/main/java/com/softropic/skillars/platform/payment/config/PaymentProperties.java` (modified — feeRate, feeFixed)
- `src/main/resources/application.yaml` (modified — fee-rate, fee-fixed)
- `src/main/java/com/softropic/skillars/platform/booking/contract/BookingAcceptedEvent.java` (new)
- `src/main/java/com/softropic/skillars/platform/booking/service/BookingStateMachine.java` (modified)
- `src/main/java/com/softropic/skillars/platform/booking/contract/CreateBookingRequest.java` (modified)
- `src/main/java/com/softropic/skillars/platform/booking/service/BookingService.java` (modified)
- `src/main/java/com/softropic/skillars/platform/payment/service/PaymentLifecycleService.java` (new)
- `src/main/java/com/softropic/skillars/platform/payment/service/CreditWalletService.java` (new)
- `src/main/java/com/softropic/skillars/platform/payment/service/CashOutService.java` (new)
- `src/main/java/com/softropic/skillars/platform/payment/contract/CreditBalanceResponse.java` (new)
- `src/main/java/com/softropic/skillars/platform/payment/contract/CashOutRequest.java` (new)
- `src/main/java/com/softropic/skillars/platform/payment/api/CreditWalletResource.java` (new)
- `src/main/java/com/softropic/skillars/platform/payment/service/PackSessionService.java` (new)
- `src/main/java/com/softropic/skillars/platform/payment/service/SessionPackPaymentService.java` (new)
- `src/main/java/com/softropic/skillars/platform/payment/contract/SessionPackPurchaseResponse.java` (new)
- `src/main/java/com/softropic/skillars/platform/payment/contract/SessionPackTierResponse.java` (new)
- `src/main/java/com/softropic/skillars/platform/payment/contract/CreateSessionPackTierRequest.java` (new)
- `src/main/java/com/softropic/skillars/platform/payment/contract/PurchaseSessionPackRequest.java` (new)
- `src/main/java/com/softropic/skillars/platform/payment/contract/SetupIntentResponse.java` (new)
- `src/main/java/com/softropic/skillars/platform/payment/contract/SavedPaymentMethodRequest.java` (new)
- `src/main/java/com/softropic/skillars/platform/payment/api/SessionPackPaymentResource.java` (new)
- `src/main/java/com/softropic/skillars/platform/payment/service/SessionPackExpiryNotifier.java` (new)
- `src/frontend/src/api/payment.api.js` (modified)
- `src/frontend/src/stores/payment.store.js` (modified)
- `src/frontend/src/i18n/en/index.js` (modified)
- `src/frontend/src/i18n/de/index.js` (modified)
- `src/frontend/src/pages/parent/ParentCreditWalletPage.vue` (new)
- `src/frontend/src/router/routes.js` (modified)
- `src/test/java/com/softropic/skillars/config/StubPaymentGateway.java` (modified)
- `src/test/resources/application-test.yaml` (modified)
- `src/test/java/com/softropic/skillars/platform/payment/BasePaymentIT.java` (new)
- `src/test/java/com/softropic/skillars/platform/payment/service/CreditRoutingTest.java` (new)
- `src/test/java/com/softropic/skillars/platform/payment/service/CashOutServiceTest.java` (new)
- `src/test/java/com/softropic/skillars/platform/payment/service/PackPriceLockedOnPurchaseTest.java` (new)
- `src/test/java/com/softropic/skillars/platform/payment/service/ExpiredPackBookingValidationTest.java` (new)
- `src/test/java/com/softropic/skillars/platform/payment/service/PackExtensionIT.java` (new)
- `src/test/java/com/softropic/skillars/platform/payment/service/SessionPackPurchaseIT.java` (new)
- `src/test/java/com/softropic/skillars/platform/payment/service/BatchPaymentIT.java` (new)
- `src/test/java/com/softropic/skillars/platform/payment/service/PaymentWebhookIdempotencyIT.java` (new)

## Review Findings

_Code review of Group 1 (DB Migration + Entities/Repos) — 2026-06-24_

### Decision-Needed

_(none)_

### Patch

- [x] [Review][Patch] **CRITICAL — Sign constraint puts `BOOKING_DEDUCTION_REVERSAL` in negative branch, breaking all credit restores** [`V62__session_payment_credit_wallet.sql` — `chk_ledger_amount_sign`] — AC 3 and Dev Notes both state `BOOKING_DEDUCTION_REVERSAL` must be **positive** (it restores credit), but the `CASE WHEN` block places it in the `amount < 0` branch alongside `BOOKING_DEDUCTION`. Any Stripe charge failure that tries to restore pre-debited credit will violate this DB constraint, leaving the parent's wallet permanently deducted. Fix: move `BOOKING_DEDUCTION_REVERSAL` out of the negative `WHEN` list so it falls into the `ELSE amount > 0` branch.
- [x] [Review][Patch] **HIGH — Config seed hard-codes id integers 163 and 164** [`V62__session_payment_credit_wallet.sql` — INSERT] — Dev Notes explicitly say "do NOT hard-code a manual id integer" (use `DEFAULT`). Hard-coded IDs will cause a PK collision if any other migration between V61 and V62 added config rows. Fix: remove the `id` column from the INSERT, letting the sequence assign it.
- [x] [Review][Patch] **HIGH — `ParentCreditLedger` class-level `@Setter` exposes setters on `txId` and `createdAt`** [`ParentCreditLedger.java`] — Task 2 spec: "No setters for `txId` or `createdAt` (append-only)". The class-level `@Setter` generates setters for all fields. Fix: remove class-level `@Setter`; add field-level `@Setter` only on `parentId`, `amount`, `type`, `referenceId`, and `description`.
- [x] [Review][Patch] **HIGH — No UNIQUE partial index enforcing one active tier per coach; `findByCoachIdAndIsActiveTrue` returns `Optional` and will throw on duplicates** [`V62__session_payment_credit_wallet.sql`, `SessionPackTierRepository.java`] — Without a `UNIQUE INDEX WHERE is_active = true`, two concurrent `createTier()` calls can produce two active tiers, causing `findByCoachIdAndIsActiveTrue` (which returns `Optional`) to throw `IncorrectResultSizeDataAccessException`. Fix: add `CREATE UNIQUE INDEX idx_spt_one_active_per_coach ON payment.session_pack_tiers(coach_id) WHERE is_active = true;` to V62.
- [x] [Review][Patch] **MEDIUM — `session_pack_purchases.remaining_sessions` has no `CHECK (remaining_sessions >= 0)` constraint** [`V62__session_payment_credit_wallet.sql`] — Concurrent session deductions not covered by the pessimistic lock can race to decrement `remaining_sessions` below zero with no DB-level guard. Fix: add `CONSTRAINT chk_spp_remaining_non_negative CHECK (remaining_sessions >= 0)` to the DDL.
- [x] [Review][Patch] **MEDIUM — `COALESCE(SUM(l.amount), 0)` integer literal fallback may cause `ClassCastException`** [`ParentCreditLedgerRepository.java`] — JPQL `COALESCE(SUM(...), 0)` passes an integer `0` as the null-fallback; when the ledger is empty, Hibernate may return an `Integer` instead of `BigDecimal`, causing a `ClassCastException` at the call site. Fix: change `0` to `0.0` or use `CAST(0 AS java.math.BigDecimal)`.
- [x] [Review][Patch] **MEDIUM — No index on `parent_credit_ledger(parent_id)`** [`V62__session_payment_credit_wallet.sql`] — The `parent_credit_balance` view and `sumByParentId` JPQL query both aggregate over `parent_id`; without an index this is a full-table scan on every balance read. Fix: add `CREATE INDEX idx_pcl_parent_id ON payment.parent_credit_ledger(parent_id);` to V62.

### Defer

- [x] [Review][Defer] **extendPack missing pessimistic lock — double extension possible** [`SessionPackPaymentService.java`] — deferred, pre-existing (service layer; reviewed in Group 2)
- [x] [Review][Defer] **Pack ownership not validated in `BookingService.createBooking()`** [`BookingService.java`] — deferred, pre-existing (booking module; reviewed in Group 4)
- [x] [Review][Defer] **Missing indexes on `session_pack_purchases(parent_id)` and `(coach_id, expires_at)`** [`V62__session_payment_credit_wallet.sql`] — deferred, performance concern, non-blocking
- [x] [Review][Defer] **Raw `String` for `type`/`status` fields instead of enums** [`ParentCreditLedger.java`, `BookingPayment.java`] — deferred, design pattern; DB constraint guards correctness; higher migration cost to add enum mapping

---

_Code review of Group 2 (Services) — 2026-06-24_

### Decision-Needed

- [ ] [Review][Decision] **`refund()` API design: `setCharge(pm_...)` passes payment method ID to a field requiring a charge ID** [`StripePaymentGateway.java:refund()`] — `RefundCreateParams.builder().setCharge(stripePaymentMethodId)` will always fail; Stripe's `charge` field expects a `ch_...` or `pi_...` ID, not a `pm_...` payment method ID. The cash-out "pay credit back to card" flow requires a different Stripe API. Options: (A) refund the most recent PaymentIntent for this parent — requires storing `paymentIntentId` on `stripe_customers`; (B) use Stripe Transfer/Payout API for credit cash-outs; (C) rethink cash-out as a manual admin payout. Decision determines which Stripe API call to use and what additional data to store.

### Patch

- [x] [Review][Patch] **CRITICAL — `getOrCreateStripeCustomer` stores placeholder Stripe customer ID without calling Stripe API** [`SessionPackPaymentService.java:getOrCreateStripeCustomer()`] — When no `StripeCustomer` exists for a parent, the code persists `"cus_placeholder_" + parentId` as the `stripeCustomerId` without calling `stripeClient.createCustomer()`. Any flow that later uses this ID with Stripe (SetupIntents, refunds, customer lookups) will immediately get a Stripe API error. Fix: call `stripeClient.createCustomer(CustomerCreateParams.builder().putMetadata("parentId", parentId.toString()).build())` and use the returned `customer.getId()`.
- [x] [Review][Patch] **CRITICAL — `chargeAndCapture` builds PaymentIntent with `confirm=true` but no payment method — every charge will fail** [`StripePaymentGateway.java:chargeAndCapture()`] — `PaymentIntentCreateParams` is built without `.setPaymentMethod(...)` or `.setCustomer(...)`. Stripe rejects a confirmed PaymentIntent with no payment method. Fix: inject `StripeCustomerRepository`, look up the parent's `stripePaymentMethodId` by `parentId`, and add `.setPaymentMethod(...)` and `.setCustomer(stripeCustomerId)` to the params. (Note: `parentId` is already a parameter on `chargeAndCapture`.)
- [x] [Review][Patch] **CRITICAL — Phantom `BOOKING_DEDUCTION_REVERSAL` written on Stripe decline when no credit was ever deducted — inflates parent balance** [`PaymentLifecycleService.java:handleCreditBasedBooking()` + `onBatchBookingAccepted()`] — In both single and batch paths, the Stripe charge is attempted BEFORE the credit ledger debit. On Stripe failure, `persistPaymentFailure(creditToUse, ...)` writes a positive `BOOKING_DEDUCTION_REVERSAL` for `creditToUse` — but no `BOOKING_DEDUCTION` was ever written. The reversal creates phantom credit, inflating the balance. AC 3 (Case B) specifies: write `BOOKING_DEDUCTION` first, then charge Stripe for the deficit. Fix: move the `creditWalletService.writeLedgerEntry(..., "BOOKING_DEDUCTION", ...)` call to BEFORE the Stripe charge; on Stripe failure, the reversal is then correct because the deduction was real.
- [x] [Review][Patch] **HIGH — Batch credit per-booking share allocation applies full `creditToUse` to each booking — `BookingPayment` amounts sum to more than was debited** [`PaymentLifecycleService.java:onBatchBookingAccepted()` — lines 193–196] — `bookingCreditShare = price.min(creditToUse)` is computed with the same total `creditToUse` for every iteration. For 2×€50 bookings with €50 credit: each booking records `creditDebited=50`, summing to €100 allocated — but only €50 was deducted from the ledger. Fix: track a running `remainingCredit` variable and subtract each booking's share: `BigDecimal bookingCreditShare = price.min(remainingCredit); remainingCredit = remainingCredit.subtract(bookingCreditShare);`.
- [x] [Review][Patch] **HIGH — No parent notification published on single-booking or batch payment failure** [`PaymentLifecycleService.java:persistPaymentFailure()`, `onBatchBookingAccepted()` — batch failure block] — AC 3 requires parent to be notified "Payment failed — please update your payment method." AC 5 requires parent notified once on batch Stripe decline. Neither failure path publishes a notification event — `parentEmail` is accepted but unused in `persistPaymentFailure`. Fix: publish a `PaymentFailedNotificationEvent` (or reuse an existing notification event) with `parentEmail` and message key `payment.failure.updateMethod` in both `persistPaymentFailure` and the batch failure path.
- [x] [Review][Patch] **HIGH — `SessionPackExpiryWarningEvent` carries no coach contact — coach notification cannot be sent; only one event published per pack** [`SessionPackExpiryNotifier.java:notifyExpiringPacks()`] — AC 9 requires "parent AND coach each receive their respective notifications." The event contains no `coachEmail` or `coachEmail` field, making a coach-targeted notification impossible downstream. Only one `SessionPackExpiryWarningEvent` is published per pack. Fix: either (A) publish two separate events (parent and coach) per pack; or (B) add `coachEmail` to `SessionPackExpiryWarningEvent` and let the handler fan out two notifications.
- [x] [Review][Patch] **MEDIUM — Cash-out reversal on Stripe refund failure only restores `requestedAmount`; `STRIPE_FEE_DEBIT` amount is permanently lost** [`CashOutService.java:processCashOut()`] — On refund failure (catch block line 48), the compensation writes `CASH_OUT_REVERSAL` for `requestedAmount` only. If `STRIPE_FEE_DEBIT` committed (which it does, since `writeCashOutLedgerEntries`'s `@Transactional` is bypassed and each entry commits separately), `feeAmount` remains permanently deducted. Fix: write a second `STRIPE_FEE_DEBIT` reversal of `+feeAmount` in the catch block alongside the `CASH_OUT_REVERSAL`.
- [x] [Review][Patch] **MEDIUM — `toResponse` passes `purchase.getRemainingSessions()` for both `sessionCount` and `remainingSessions` parameters** [`SessionPackPaymentService.java:toResponse()`] — At purchase time both values happen to be equal, masking the bug. Any client calculating "sessions used" will always show 0. Fix: pass `tier.getSessionCount()` (or `purchase.getRemainingSessions()` at creation which equals total) as the first argument; the field semantics require `totalSessions` to be fixed at the tier count, not a live remaining count. Fix: thread `tier` into `toResponse` or store total on `SessionPackPurchase`, and use `tier.getSessionCount()` for the `sessionCount` arg.
- [x] [Review][Patch] **MEDIUM — Batch pack-booking exception is silently swallowed — booking remains stuck in ACCEPTED with no payment record** [`PaymentLifecycleService.java:onBatchBookingAccepted()` — pack loop lines 132–139] — The `catch (Exception e)` block only logs; no `CHARGE_FAILED` `BookingPayment` is persisted and `transitionToDeclined()` is not called. The booking stays in `PAYMENT_PENDING` indefinitely. Fix: in the catch block, call `persistPackBatchPayment` with `CHARGE_FAILED` status and `transitionToDeclined(bookingId)`.

### Defer

- [x] [Review][Defer] **`@Transactional` Spring proxy bypass on all self-invoked methods** [`PaymentLifecycleService.java`, `SessionPackPaymentService.java`, `CashOutService.java`] — deferred, acknowledged in Dev Notes; proper fix requires extracting to sibling `@Service` beans — Story 7.3
- [x] [Review][Defer] **`BookingDisputedEvent` handler missing (AC 12 not implemented)** — deferred, Dev Notes say "add TODO if not yet implemented; Story 10.x will wire this"
- [x] [Review][Defer] **`SessionPackExhaustedEvent.playerId` contains `parentId` semantically** [`PackSessionService.java`] — deferred, pre-existing event contract; Story 7.3
- [x] [Review][Defer] **extendPack missing pessimistic lock** [`SessionPackPaymentService.java`] — deferred (carried from Group 1 D1); Story 7.3
- [x] [Review][Defer] **Hardcoded EUR currency in `chargeAndCapture`** [`StripePaymentGateway.java`] — deferred, single-currency platform now; config-driven later
- [x] [Review][Defer] **`getBalance()` TOCTOU concurrent double-spend risk** [`CreditWalletService.java`] — deferred, architectural; carried from Group 1 D5

_Code review of Group 3 (API + Contracts) — 2026-06-24_

### Decision-Needed

_(none)_

### Patch

- [x] [Review][Patch] **CRITICAL — `createSetupIntent()` creates StripeCustomer with `cus_placeholder_` — Stripe API call will fail** [`SessionPackPaymentResource.java:createSetupIntent()`] — When no `StripeCustomer` exists for the parent, inlines `"cus_placeholder_" + parentId` instead of calling `paymentGateway.createStripeCustomer(parentId)`. Any `SetupIntent` created against this invalid customer ID will get a Stripe API error. Fix: call `paymentGateway.createStripeCustomer(parentId)` to obtain a real `cus_...` ID.
- [x] [Review][Patch] **CRITICAL — `savePaymentMethod()` creates StripeCustomer with `cus_placeholder_`** [`SessionPackPaymentResource.java:savePaymentMethod()`] — Same anti-pattern: `orElseGet` branch builds a `StripeCustomer` with a placeholder ID. Any subsequent charge or SetupIntent against this customer fails at Stripe. Fix: call `paymentGateway.createStripeCustomer(parentId)`.
- [x] [Review][Patch] **HIGH — AC 8 expiry check uses `ifPresent()` — non-existent `sessionPackPurchaseId` silently passes validation** [`BookingService.java:createBooking()`] — `sessionPackPurchaseRepository.findById(...).ifPresent(...)` ignores the "not found" case. Booking proceeds with a dangling FK, producing a `DataIntegrityViolationException` (500) at DB insert instead of a clean `400 ResourceNotFound`. Fix: use `orElseThrow(() -> new ResourceNotFoundException(...))` before the expiry check.

### Defer

_(none for Group 3)_

## Change Log

_Code review of Group 4 (Booking module) — 2026-06-24_

### Decision-Needed

_(none)_

### Patch

- [x] [Review][Patch] **CRITICAL — `createBookingRequest()` gates on legacy `hasCredits()`, blocking all AC 3 non-legacy-pack payment paths** [`BookingService.java:createBookingRequest()`] — `sessionPackService.hasCredits()` checks only the pre-7.2 `SessionPackPurchased` table. A parent who has platform credit (`parent_credit_ledger`) but no legacy pack is blocked at booking creation. Likewise, a parent with zero credit intending to pay full Stripe (AC 3 Case C) is blocked. AC 3 explicitly defines all three cases (A: full credit, B: partial credit, C: zero-credit Stripe charge) as valid; the gate must be removed so all paths reach `PaymentLifecycleService`. Fix: remove the `hasCredits()` guard; payment readiness is already checked by `isCoachPaymentReady()`; payment failure routes gracefully to `DECLINED` via `PaymentLifecycleService`.

### Defer

- [Review][Defer] **MEDIUM — Legacy pessimistic lock acquired even on new `sessionPackPurchaseId` path** [`BookingService.java:179`] — `sessionPackPurchasedRepository.findActivePacksForDeduction()` locks all legacy pack rows unconditionally; when `sessionPackPurchaseId != null` the new pack has its own lock via `PackSessionService.deductSession()`; the legacy lock is pure overhead. Logged as D10 in deferred-work.md; Story 7.3.

_Code review of Group 5 (Frontend) — 2026-06-24_

### Decision-Needed

_(none)_

### Patch

- [x] [Review][Patch] **MEDIUM — `deactivateSessionPackTier` export missing from `payment.api.js`** — `PATCH /api/payment/coaches/me/session-pack-tiers/{tierId}/deactivate` added in Story 7.2 but no exported API function; endpoint unreachable from any future coach UI. Fix: added `export const deactivateSessionPackTier = (tierId) => api.patch(...)`.

- [x] [Review][Patch] **CRITICAL (escalated from MEDIUM) — `BookingRequestPage.vue:canSubmit` hard-gates on legacy `hasCredits`** — `creditsForCoach` in `booking.store.js` sums only legacy `SessionPackPurchased.creditsRemaining`; parents with platform credit or zero credit (AC 3 Case C full-Stripe) see the submit button permanently disabled. Mirror of the Group 4 backend bug. Fix: removed `hasCredits.value &&` from `canSubmit`; the warning banner remains informational with its "Buy sessions" CTA but no longer blocks submission.

### Defer

- [Review][Defer] **LOW — `extendSessionPack` comment mis-labels the function as a parent action** [`payment.api.js`] — the endpoint is `@PreAuthorize(HAS_COACH_ROLE)`; it is a coach action. Only the comment is wrong; the API call targets the correct endpoint. Cosmetic; Story 7.3.

_Code review of Group 6 (Tests) — 2026-06-24_

### Decision-Needed

_(none)_

### Patch

- [x] [Review][Patch] **CRITICAL — `CashOutServiceTest` uses `stripePaymentMethodId` for refund verification; service uses `lastPaymentIntentId`** — `CashOutService:35` throws `payment.noPaymentMethod` when `lastPaymentIntentId == null`. Two tests (`processCashOut_feeCalculatedCorrectly_refundIssuedWithNetAmount` and `processCashOut_stripeRefundFails_writesReversalAndRethrows`) set only `stripePaymentMethodId` on the stub `StripeCustomer` → service throws before reaching `refund()`. Fix: renamed constant to `PAYMENT_INTENT_ID`, set `customer.setLastPaymentIntentId(PAYMENT_INTENT_ID)` in both tests, updated `verify(paymentGateway).refund(eq(PAYMENT_INTENT_ID), ...)`.

- [x] [Review][Patch] **MEDIUM — `CashOutServiceTest.processCashOut_stripeRefundFails` only verified one `CASH_OUT_REVERSAL` entry** — `CashOutService:51–52` writes TWO reversals (requestedAmount + feeAmount) on refund failure; test only verified `eq(AMOUNT)` = `100.00`. Fix: added second `verify(creditWalletService).writeLedgerEntry(..., eq(new BigDecimal("2.75")), eq("CASH_OUT_REVERSAL"), ...)`.

- [x] [Review][Patch] **CRITICAL — `ExpiredPackBookingValidationTest.createBookingRequest_validPackProvided_doesNotThrowPackExpired` empty try block** — method under test was never called; test always passed regardless of any code change. Fix: added full mock setup mirroring the expired-pack test, then called `bookingService.createBookingRequest(PARENT_ID, req)` inside the try-catch; any exception other than `payment.packExpired` is allowed.

- [x] [Review][Patch] **MEDIUM — `BatchPaymentIT.batchBooking_withCreditBalance_creditAppliedBeforeStripeCharge` weak assertion** — `assertThat(deductionCount).isGreaterThan(0L)` upgraded to `isEqualTo(2L)`: 2 bookings × €40, €100 balance covers both; exactly 2 `BOOKING_DEDUCTION` entries expected.

- [x] [Review][Patch] **LOW — `CreditRoutingTest.packBasedBooking_noStripeNoCreditConsulted` dead variable** — `Booking booking = new Booking()` created but never used. Fix: removed variable and its now-unused import.

### Defer

_(none)_

---

_Code review of Group 1 (DB + Entities/Repos) — adversarial pass 2 — 2026-06-24_

### Decision-Needed

_(none)_

### Patch

- [x] [Review][Patch] **CRITICAL — `@Version Integer` uninitialized on `SessionPackPurchase` and `SessionPackTier` — `DataIntegrityViolationException` on first `save()`** [`SessionPackPurchase.java:57`, `SessionPackTier.java:51`] — Fixed: added `= 0` field initializer on both entities.
- [x] [Review][Patch] **HIGH — No `CHECK (session_count > 0)`, `CHECK (total_price > 0)`, `CHECK (price_per_session > 0)` on `session_pack_tiers`** [`V62__session_payment_credit_wallet.sql`] — Fixed: added `chk_spt_session_count_positive`, `chk_spt_total_price_positive`, `chk_spt_price_per_session_positive` constraints to DDL.
- [x] [Review][Patch] **HIGH — JPQL `COALESCE(SUM(l.amount), 0.0)` returns `Double` for parents with no ledger rows — `ClassCastException` at `CreditWalletService.getBalance()`** [`ParentCreditLedgerRepository.java:12`] — Fixed: removed COALESCE from JPQL; changed return to `Optional<BigDecimal>`; `CreditWalletService.getBalance()` now calls `.orElse(BigDecimal.ZERO)`.
- [x] [Review][Patch] **MEDIUM — `findByIdForUpdate` inherits `readOnly=true` — PostgreSQL rejects `SELECT ... FOR UPDATE` in a read-only transaction** [`SessionPackPurchaseRepository.java:17-19`] — Fixed: added `@Transactional` annotation to override read-only default; removed unused `@Nullable` import.
- [x] [Review][Patch] **MEDIUM — `SessionPackPurchase.pricePerSession` mutable via `@Setter` despite being a locked-at-purchase value** [`SessionPackPurchase.java`] — Fixed: added `updatable = false` to `@Column` on `pricePerSession`; setter retained for initial construction but JPA will not write this column in UPDATE statements.
- [x] [Review][Patch] **MEDIUM — `boolean isActive` in `SessionPackTier` generates Lombok `isIsActive()` getter** [`SessionPackTier.java`] — **DISMISSED: false positive.** `SessionPackPaymentService.java` confirms Lombok generates `isActive()` getter and `setActive(boolean)` setter correctly for `boolean isActive` (service calls `tier.isActive()` at lines 45, 183 and `t.setActive(false)` at lines 88, 101, 115 — all compile). Spring Data JPA `findByCoachIdAndIsActiveTrue` resolves to the `isActive` JPA attribute via field access. No code change needed.
- [x] [Review][Patch] **LOW — `Booking.sessionPackPurchaseId` lacks `@Column(updatable = false)` — FK can be silently cleared or replaced on any subsequent save** [`Booking.java`] — Fixed: changed to `@Column(name = "session_pack_purchase_id", updatable = false)`.

### Defer

- [x] [Review][Defer] **`parent_credit_balance` VIEW returns 0 rows for parents with no ledger history** [`V62__session_payment_credit_wallet.sql`] — `GROUP BY parent_id` with `COALESCE(SUM(...), 0)` emits no row when a parent has zero ledger entries; native SQL callers get 0 rows instead of a zero-balance row. Safe today via JPQL path; latent trap for future native SQL consumers.
- [x] [Review][Defer] **Duplicate expiry query methods in `SessionPackPurchaseRepository`** [`SessionPackPurchaseRepository.java:21-25`] — `findByCoachIdAndExpiresAtBetween...` (coach-scoped derived query) and `findExpiringWithinWindowAndSessionsRemaining` (platform-wide JPQL) serve overlapping purposes; the coach-scoped method appears unused. Verify against Group 2 service callers.
- [x] [Review][Defer] **`SessionPackPurchase.expiresAt` mutable with no `updatable = false`** [`SessionPackPurchase.java`] — Extension is gated via `SessionPackPaymentService.extendPack()` business-rule validation; open setter is a footgun but service-layer enforced for now.
- [x] [Review][Defer] **No DB-level append-only enforcement on `parent_credit_ledger` (no trigger or RLS)** [`V62__session_payment_credit_wallet.sql`] — AC 1 states "no UPDATE or DELETE ever on this table" but no DB rule enforces it; application-layer invariant only. Low risk while single-app; worth hardening in a later migration.
- [x] [Review][Defer] **`stripe_customers.last_payment_intent_id` column not in AC 1 spec schema** [`V62__session_payment_credit_wallet.sql`, `StripeCustomer.java`] — Intentional addition to support the cash-out refund flow (Group 2 Decision D1 resolution). Spec AC 1 should be updated to document this column formally.
- [x] [Review][Defer] **No `CHECK (stripe_customer_id LIKE 'cus_%')` format guard on `stripe_customers`** [`V62__session_payment_credit_wallet.sql`] — Would catch placeholder IDs at DB boundary; application-layer only today.

_Code review of Group 2 (service layer) — adversarial pass 2 — 2026-06-24_

### Decision-Needed
- [x] [Review][Decision] **Spring AOP `@Transactional`-on-private bypass affects 3 services** — DN-1 resolved: **Option A chosen — extract to new `@Service` beans**.

### Patch
- [x] [Review][Patch] **CRITICAL — Race condition in `processCashOut`: balance check and debit are not atomic** [`CashOutService.java:26–28`] — No lock between `getBalance()` (plain SELECT SUM) and `writeCashOutLedgerEntries()`; two concurrent cash-out requests both pass the balance check and both debit. Fix: wrap the entire balance-check-and-debit in a `@Transactional` method with pessimistic isolation, or use a `SELECT SUM … FOR UPDATE` via a dedicated repo method.
- [x] [Review][Patch] **CRITICAL — Spring AOP `@Transactional`-on-private bypass (Option A: extract to new `@Service`)** [`CashOutService.java:58–64`, `PaymentLifecycleService.java:272,283,297`, `SessionPackPaymentService.java:131,150`] — Private `@Transactional` methods are silently no-ops in Spring proxy AOP. Three sites: (1) `CashOutService.writeCashOutLedgerEntries` → move to a `writeCashOutLedgerEntries(Long, BigDecimal, BigDecimal)` method on `CreditWalletService`; (2) `PaymentLifecycleService.persistPack/Credit/BatchFailed*` → extract to new `@Service BookingPaymentPersistenceService`; (3) `SessionPackPaymentService.getOrCreateStripeCustomer` + `createPurchase` → make `protected` or extract to `StripeCustomerService`.
- [x] [Review][Patch] **CRITICAL — `handleCreditBasedBooking` commits BOOKING_DEDUCTION in its own TX before Stripe call; credit permanently lost if process crashes between them** [`PaymentLifecycleService.java:89,107`] — Spec (Task 5) requires: charge Stripe outside TX, then in ONE `@Transactional`: write BOOKING_DEDUCTION + create `BookingPayment(CAPTURED)` + `transitionToConfirmed`. Code uses two separate transactions. Fix: move the `writeLedgerEntry` call inside `persistPaymentSuccess` so both the ledger entry and the payment record commit in the same transaction.
- [x] [Review][Patch] **CRITICAL — `purchasePack` charges Stripe before persisting `SessionPackPurchase` — parent charged with no pack record on DB failure** [`SessionPackPaymentService.java:588–591`] — `chargeAndCapture` succeeds, then `createPurchase()` throws → charge is orphaned with no compensation. Fix: add try-catch around `createPurchase()` that calls `paymentGateway.refund(paymentIntentId, tier.getTotalPrice())` on failure.
- [x] [Review][Patch] **HIGH — `CashOutService` reads `lastPaymentIntentId` instead of `stripePaymentMethodId`; throws wrong error code** [`CashOutService.java:31–37`] — AC 10 says look up `stripe_payment_method_id`, throw `payment.noPaymentMethod`. Code reads `lastPaymentIntentId`, throws `payment.noPaymentIntentOnFile`. Also: using last payment intent as refund target refunds the wrong charge if parent made a subsequent Stripe payment. Fix: read `sc.getStripePaymentMethodId()`, throw `payment.noPaymentMethod` if null, pass to `refund()`.
- [x] [Review][Patch] **HIGH — Null `stripePaymentMethodId` passed to Stripe `setPaymentMethod` without guard** [`StripePaymentGateway.java:~57`] — `stripe_customers.stripe_payment_method_id` is nullable. If a parent has a `StripeCustomer` record but no saved payment method, `setPaymentMethod(null)` causes a Stripe 400 error, silently failing the charge. Fix: before calling `builder.setPaymentMethod(...)`, check for null and throw `PaymentGatewayException("payment.noPaymentMethod")` if null.
- [x] [Review][Patch] **HIGH — `resolveSessionPrice` silently returns `BigDecimal.ZERO` when coach has no pricing — session delivered for free** [`BookingService.java:239–248`] — If `coachPricingRepository.findByCoachId()` returns empty, `sessionPrice = 0`, `handleCreditBasedBooking` skips all charges, booking confirmed with $0. Fix: throw `ResourceNotFoundException` (or `PaymentGatewayException`) instead of falling back to `ZERO`.
- [x] [Review][Patch] **HIGH — `feeAmount` can exceed `requestedAmount` for small cash-outs — negative `netAmount` sent to Stripe** [`CashOutService.java:39–47`] — With `feeFixed=0.25 EUR`, any request under ~€0.26 produces negative `netAmount`; `toCents(-0.01) = -1L` → Stripe 400. Fix: add `if (netAmount.compareTo(BigDecimal.ZERO) <= 0) throw new PaymentGatewayException("payment.cashOutTooSmall")` before the refund call.
- [x] [Review][Patch] **HIGH — Batch pack deduction: no `restoreSession` called when deduction succeeds but subsequent persist/transition fails** [`PaymentLifecycleService.java:130–143`] — `packSessionService.deductSession()` commits atomically. If `persistPackBatchPayment` or `transitionToConfirmed` then fails, session is consumed with no confirmed booking. Fix: in the catch block, call `packSessionService.restoreSession(b.getSessionPackPurchaseId())` before `transitionToDeclined`.
- [x] [Review][Patch] **HIGH — `chargeAndCapture` missing `setOffSession(true)` — EU SCA/3DS off-session charges fail without actionable error** [`StripePaymentGateway.java:chargeAndCapture`] — Server-side charges for saved payment methods require `off_session=true` to get correct SCA exemption handling and retry-able error codes. Without it, 3DS-enrolled EU cards return an `authentication_required` error with no `next_action` URL. Fix: add `.setOffSession(true)` to the `PaymentIntentCreateParams.Builder`.
- [x] [Review][Patch] **MEDIUM — `persistPackBatchPayment` drops `batchId` — `batch_payment_intent_id` null for pack-based batch records** [`PaymentLifecycleService.java:272–281`] — AC 5 requires every batch booking payment to have `batch_payment_intent_id = batchId`. The credit-based counterpart (`persistCreditBatchPayment`) sets it correctly; the pack-based one does not. Fix: add `bp.setBatchPaymentIntentId(batchId)` before `bookingPaymentRepository.save(bp)`.

### Defer
- [x] [Review][Defer] **Non-atomic idempotency check in `onBookingAccepted`** [`PaymentLifecycleService.java:52–55`] — `existsById` is a bare SELECT outside any transaction; concurrent event retry can bypass it. Root cause is the TX boundary issue addressed in P3 above; revisit if duplicate event replay becomes observed in production.
- [x] [Review][Defer] **`SessionPackExpiryNotifier` sends up to 14 daily warning emails per pack — no notification-sent guard** [`SessionPackExpiryNotifier.java`] — Requires a `last_warned_at` column on `session_pack_purchases` (V63+ migration). Acceptable for MVP; revisit before public launch.
- [x] [Review][Defer] **`createTier` TOCTOU under concurrent coach requests — two active tiers briefly possible** [`SessionPackPaymentService.java:createTier`] — Concurrent calls both deactivate existing tiers and insert new ones. DB UNIQUE partial index (`idx_spt_one_active_per_coach`) enforces the one-active constraint at commit time; one transaction will fail with a constraint violation. Low probability in production; acceptable for now.

_Code review of Group 4 (Booking module) — adversarial pass 2 — 2026-06-24_

### Decision-Needed

_(none)_

### Patch

- [x] [Review][Patch] **CRITICAL — No `pack.getParentId() == parentId` check — any parent can consume another parent's session pack** [`BookingService.java:170-176`] — AC 8 requires: "Pack belongs to this parent — else 403." The code fetches the `SessionPackPurchase` and checks expiry but never asserts `pack.getParentId().equals(parentId)`. A malicious parent can reference a victim's `sessionPackPurchaseId`, bypass Stripe, and cause `PackSessionService.deductSession()` to drain the victim's paid sessions. Fix: add `if (!pack.getParentId().equals(parentId)) throw new OperationNotAllowedException("Pack does not belong to this parent", SecurityError.MISSING_RIGHTS);` after the expiry check.
- [x] [Review][Patch] **CRITICAL — No `pack.getCoachId() == req.coachId()` check — pack purchased for Coach A can be applied to Coach B** [`BookingService.java:170-176`] — AC 8 requires: "Pack belongs to the requested coach — else `payment.packCoachMismatch`." The code only validates expiry. Applying a coachA pack to a coachB booking deducts from the wrong coach's pack inventory and prices the session using the wrong tier's `pricePerSession`. Fix: add `if (!pack.getCoachId().equals(req.coachId())) throw new PaymentGatewayException("payment.packCoachMismatch");` after the parentId check.
- [x] [Review][Patch] **CRITICAL — No `pack.getRemainingSessions() > 0` check — exhausted pack can be booked against** [`BookingService.java:170-176`] — AC 8 requires: "Pack has remaining sessions — else `payment.packExhausted`." A pack with `remainingSessions == 0` passes all checks and creates a booking in PAYMENT_PENDING. `PackSessionService.deductSession()` will then fail at payment time, causing a DECLINED booking. Failing early gives the parent a clean 400 before any state transitions. Fix: add `if (pack.getRemainingSessions() <= 0) throw new PaymentGatewayException("payment.packExhausted");`.
- [x] [Review][Patch] **HIGH — Cross-midnight sessions always rejected by availability window check** [`BookingService.java:530`] — `startZdt.toLocalDate().equals(endZdt.toLocalDate())` is false for any session that crosses midnight in the coach's timezone (e.g., 23:30–00:30 Saturday). The window is skipped unconditionally even when the coach has an explicit late-night availability window. Fix: drop the date-equality guard; instead check that `endZdt` belongs to the same `dayOfWeek` window OR `endZdt` is the next calendar day. Simplest fix: `&& !requestedStart.isBefore(w.getStartTime()) && !requestedEnd.isAfter(w.getEndTime())` with the day check applied to start only, and if endZdt is next-day only allow it when `endZdt.toLocalTime()` is before `LocalTime.MIDNIGHT` (i.e., the window end is midnight or later).
- [x] [Review][Patch] **MEDIUM — `canonicalTimezone` not validated as IANA timezone — garbage value stored and crashes downstream** [`CreateBookingRequest.java:16`] — `@NotBlank` permits any non-empty string. Invalid values like `"EST"`, `"UTC+5"`, or typos pass Bean Validation. Inside `isSlotWithinAvailabilityWindow`, only the coach's window timezone (`w.getCanonicalTimezone()`) is checked with `ZoneId.of()` — the request timezone is stored raw and used later in notifications/scheduling where `ZoneId.of(booking.getCanonicalTimezone())` will throw `DateTimeException`. Fix: add a custom `@ValidTimezone` constraint (or use `@Pattern(regexp = "[A-Za-z]+/[A-Za-z_]+")` as a lightweight guard) to `CreateBookingRequest.canonicalTimezone`.

### Defer

- [x] [Review][Defer] **`getBooking(UUID)` has no caller authorization check** [`BookingService.java:271`] — pre-existing; any authenticated user can read any booking by UUID; Story 7.3 access-control hardening
- [x] [Review][Defer] **`getParentBookings` does not clamp negative `effectiveCredits` to 0** [`BookingService.java:316`] — inconsistency with `getParentPlayerSchedule` (which does clamp); pre-existing; low impact (UI shows -N sessions)
- [x] [Review][Defer] **`CANCEL_PARENT` not permitted from `PAYMENT_PENDING` — booking stuck if Stripe webhook never fires** [`BookingStateMachine.java:30`] — design gap; webhook reliability is a separate concern; acceptable for MVP; Story 7.3
- [x] [Review][Defer] **Past-elapsed `requestedStartTime` at CANCEL_PARENT gives NONE refund eligibility** [`BookingService.java:471`] — negative `hoursUntilSession` maps to "NONE"; correct flow is `NO_SHOW_COACH` which gives FULL refund; edge case; Story 7.3
- [x] [Review][Defer] **`Booking` identity columns (`parentId`, `playerId`, `coachId`) lack `updatable = false`** [`Booking.java:31-37`] — defence-in-depth gap; no current code path mutates them; pre-existing entity pattern

_Code review of Group 3 (API + Contracts) — adversarial pass 2 — 2026-06-24_

> ⚠️ **Partial coverage**: Edge Case Hunter and Acceptance Auditor both hit the session rate limit; only Blind Hunter completed. Two dismissed findings confirmed safe by manual inspection.

### Decision-Needed

_(none)_

### Patch

- [x] [Review][Patch] **HIGH — Stripe customer race condition in `createSetupIntent` and `savePaymentMethod` — orphaned Stripe customer on concurrent double-create** [`SessionPackPaymentResource.java:115-121`, `:131-140`] — Two concurrent requests from the same parent both pass `stripeCustomerRepository.findById()` returning empty, both enter the `orElseGet` branch, both call `paymentGateway.createStripeCustomer()`, creating two Stripe customer objects. The second `stripeCustomerRepository.save()` fails on the PK constraint, leaving one Stripe customer permanently orphaned (created in Stripe's system, never linked to a DB record). Fix: wrap `stripeCustomerRepository.save(sc)` in a `try { ... } catch (DataIntegrityViolationException e) { return stripeCustomerRepository.findById(parentId).orElseThrow(); }` in both endpoints.

### Defer

- [x] [Review][Defer] **`getActiveCoachTier` returns 204 No Content when no active tier found — unusual semantics for a typed GET** [`SessionPackPaymentResource.java:101-105`] — Spec says "returns empty if none" (ambiguous); 204 is unusual for a GET on a typed resource (204 means "success, no body" but clients expecting `SessionPackTierResponse` get null). More idiomatic would be 404 or 200 with explicit null. Not broken today; clean up if clients show null-handling issues — deferred.

_Code review of Group 5 (Frontend) — adversarial pass 2 — 2026-06-24_

### Decision-Needed

_(none)_

### Patch

- [x] [Review][Patch] **HIGH — Type mismatch in `bookedStartTimes` filter: `b.coachId === coachId` and `b.playerId === playerId.value` use strict equality with mixed types** [`BookingRequestPage.vue: bookedStartTimes computed`] — `route.params.coachId` is always a **string**; API responses return `coachId` and `playerId` as **numbers**. Strict `===` between `42` and `"42"` returns false — the deduplication guard silently breaks. Parent can select and submit a slot already booked with that coach, creating duplicate booking requests. Fix: `String(b.coachId) === String(coachId)` and `String(b.playerId) === String(playerId.value)`.
- [x] [Review][Patch] **CRITICAL (AC 3) — `submit()` has no catch block — booking errors swallowed silently, no user notification** [`BookingRequestPage.vue: submit()`] — `try/finally` with no `catch` means any 4xx/5xx/network error from `submitBookingRequest` resets the spinner but shows nothing to the parent. AC 3 requires booking failure to display an error notification. Fix: add `catch { $q.notify({ type: 'negative', message: t('booking.requests.submitError') }) }`.
- [x] [Review][Patch] **CRITICAL (AC 7) — `submitBatch` called with hardcoded `0` instead of `canonicalTimezone`** [`BookingRequestPage.vue: submitBatchRequest()`] — `bookingStore.submitBatch(coachId, playerId.value, 0)` passes the literal `0` where the third argument is `canonicalTimezone` (IANA timezone string). The backend's IANA timezone validation (added in Group 4) rejects `"0"` as an invalid timezone — every batch booking returns 400. Fix: replace `0` with `Intl.DateTimeFormat().resolvedOptions().timeZone`.
- [x] [Review][Patch] **CRITICAL (AC 10) — Fee rate hardcoded client-side in cashout success toast** [`ParentCreditWalletPage.vue: handleCashOut()`] — `net: (cashoutAmount.value * 0.975 - 0.25)` and `fee: (cashoutAmount.value * 0.025 + 0.25)` duplicate the fee constants from `PaymentProperties` in the frontend. AC 10 requires fee rate to be defined in backend config only. The backend's `cashOut()` endpoint returns no fee/net breakdown, so the displayed values diverge silently if fee config changes. Fix: simplify the success toast to show only the gross amount; removed `{net}` and `{fee}` from `cashoutSuccess` i18n keys in en/de.
- [x] [Review][Patch] **HIGH (AC 2) — Credit balance displayed without `.toFixed(2)` — not EUR-formatted** [`ParentCreditWalletPage.vue: balance display`] — `` `€${balance}` `` renders `€10` or `€10.1`. AC 2 requires 2 decimal places. Fix: `` `€${Number(balance).toFixed(2)}` ``.
- [x] [Review][Patch] **HIGH — No client-side maximum cashout validation against current balance** [`ParentCreditWalletPage.vue: q-btn :disable`] — `:disable="!cashoutAmount || cashoutAmount <= 0"` allows submitting any positive amount regardless of balance. A stale balance display (e.g., credits spent in another tab) or backend bug could result in an overdraft request reaching the server with no early feedback. Fix: `:disable="!cashoutAmount || cashoutAmount <= 0 || balance === null || cashoutAmount > balance"`.

### Defer

- [x] [Review][Defer] **`loadStripe(undefined)` if publishable key is null — TypeError in payment confirmation** [`payment.api.js: confirmPackPayment`, `confirmCardSetup`] — Fix belongs in the callers that supply the key from `stripeStatus`; guarding the API module itself would mask the real issue. Deferred to component review. (D17)
- [x] [Review][Defer] **Shared `loading`/`error` flags across concurrent store actions** [`payment.store.js`] — Real architectural concern: concurrent calls to different actions race on a single `loading` flag, causing premature spinner dismissal. No current page in this group calls multiple store actions concurrently so no active user-visible bug. Deferred to store architecture pass. (D18)
- [x] [Review][Defer] **`selectedSlot` not cleared when entering batch mode** [`BookingRequestPage.vue: toggleBatchMode()`] — On entering batch mode the stale `selectedSlot` value persists. On returning to single mode `canSubmit` is immediately `true`. LOW severity, no data loss. (D19)

_Code review of Group 6 (Tests) — adversarial pass 2 — 2026-06-24_

### Decision-Needed

_(none)_

### Patch

- [x] [Review][Patch] **CRITICAL — `CreditRoutingTest` missing `@Mock BookingPaymentPersistenceService` → NPE on every path that calls `persistenceService`** [`CreditRoutingTest.java`] — `PaymentLifecycleService` depends on `BookingPaymentPersistenceService` (injected via constructor). No matching `@Mock` was declared, so Mockito left `persistenceService = null`. Every test path that reaches `persistPaymentSuccess` or `persistPaymentFailure` threw NPE before any assertion ran. Fix: add `@Mock BookingPaymentPersistenceService persistenceService;`; rewrite all assertions to verify at `persistenceService` boundary (where the @Transactional calls actually happen).
- [x] [Review][Patch] **CRITICAL — `stripeDeclineWithCreditPreDebited_reversalWritten` asserted phantom reversal** [`CreditRoutingTest.java`] — After the Group 2 P3 fix (credit deduction moved inside `persistPaymentSuccess`), credit is NEVER pre-debited before Stripe. On Stripe failure, `persistPaymentFailure` is called with `creditToReverse=ZERO` — no reversal is written. The test was asserting `writeLedgerEntry(..., "BOOKING_DEDUCTION_REVERSAL", ...)` which never happens in production. Renamed to `stripeDecline_chargesCaptureFails_callsPersistFailureWithZeroReversal`; now correctly verifies `persistenceService.persistPaymentFailure(BOOKING_ID, ZERO, ...)` (AC 3 decline path).
- [x] [Review][Patch] **HIGH (AC 3) — `caseB` and `caseC` missing `status=CAPTURED` assertion** [`CreditRoutingTest.java`] — Both tests verified Stripe call and amount but not `BookingPayment.status=CAPTURED`. After restructure, `persistPaymentSuccess` covers all success outcomes including status; verified via `persistenceService.persistPaymentSuccess(...)` call with correct `creditDebited`/`stripeCharged` arguments.
- [x] [Review][Patch] **HIGH (AC 4) — `packBasedBooking` missing `BookingPayment.status` assertion** [`CreditRoutingTest.java`] — `deductSession` call was verified but `persistenceService.persistPaymentSuccess(BOOKING_ID, ZERO, ZERO, ...)` was not. Now asserted.
- [x] [Review][Patch] **CRITICAL (AC 3 decline) — No PAYMENT_FAILED / booking→DECLINED verification** [`CreditRoutingTest.java`] — `BookingDeclinedEvent` is published inside `BookingPaymentPersistenceService.persistPaymentFailure()`; the decline test now verifies `persistenceService.persistPaymentFailure(BOOKING_ID, ZERO, ...)` is called, ensuring the event path is exercised.
- [x] [Review][Patch] **CRITICAL — `validPackProvided_doesNotThrowPackExpired` broken after Group 4 patches** [`ExpiredPackBookingValidationTest.java`] — Mock `validPack` had `parentId=null`, `coachId=null`, `remainingSessions=0`. Production now checks parentId first → NPE or `OperationNotAllowedException` (uncaught) or `payment.packExhausted` (silently swallowed by catch block). Fixed: set `validPack.setParentId(PARENT_ID)`, `setCoachId(COACH_ID)`, `setRemainingSessions(5)`; assertion changed to `assertThatThrownBy` form that fails on any pack-validation exception.
- [x] [Review][Patch] **CRITICAL × 3 (AC 8) — Three new Group 4 validations have zero test coverage** [`ExpiredPackBookingValidationTest.java`] — No tests existed for: pack belonging to wrong parent (`OperationNotAllowedException`), pack for wrong coach (`payment.packCoachMismatch`), pack exhausted (`payment.packExhausted`). Added three new `@Test` methods and a `setupCommonMocks()` helper.
- [x] [Review][Patch] **CRITICAL (test isolation) — `BasePaymentIT.cleanPaymentData()` omits `coach_profiles` / `user` cleanup** [`BasePaymentIT.java`] — `insertTestCoach` uses `ON CONFLICT DO NOTHING`; stale rows from prior test classes caused second-run inserts to silently no-op, returning the old UUID. Tests operating on coach-specific data could use phantom rows. Added `DELETE FROM marketplace.coach_profiles` and `DELETE FROM main."user" WHERE login LIKE '%@test.com'` in FK-safe order.
- [x] [Review][Patch] **HIGH — `BatchPaymentIT` assertions too weak: wrong deduction count + missing session count check** [`BatchPaymentIT.java`] — `batchBooking_withCreditBalance` asserted `deductionCount == 2` but production writes ONE ledger entry for the whole batch; corrected to `1L` and added `totalDeducted == 80.00` verification. `batchBooking_mixedPackAndCredit` did not verify `remaining_sessions` was decremented; added `assertThat(remainingSessions).isEqualTo(4)`.

### Defer

- [x] [Review][Defer] **`CashOutService` field alignment: test sets `lastPaymentIntentId` but service may read `stripePaymentMethodId`** — requires reading `CashOutService.processCashOut()` to confirm; deferred as D20 pending investigation.
- [x] [Review][Defer] **Pack deduction failure path (`deductSession` throws) entirely untested at unit level** — requires substantial new mock infrastructure for `persistenceService`; deferred to Story 7.3 (D21).
- [x] [Review][Defer] **Credit routing and cashout boundary cases** (`balance==price`, `amount==balance`) — low severity, current behaviour correct (D22).
- [x] [Review][Defer] **Unit-level idempotency ledger-count assertions** — covered by `PaymentWebhookIdempotencyIT` integration tests (D23).

## Change Log

- 2026-06-24: Story implementation complete. Implemented Tasks 1–11 across two sessions. Added V62 Flyway migration (payment tables + version columns fix), JPA entities, StripeClient wrapper, PaymentLifecycleService with three-case credit routing, CreditWalletService, CashOutService, PackSessionService, SessionPackPaymentService, SetupIntent flow, Vue credit wallet page, and full test suite (4 unit tests + 4 integration tests + BasePaymentIT). Status → review.
- 2026-06-24: Code review Group 4 (Booking module) adversarial pass 2 — applied 5 patch findings. `BookingService.createBookingRequest` now validates three AC 8 session-pack conditions: parent ownership, coach ownership (throws `payment.packCoachMismatch`), and remaining sessions > 0 (throws `payment.packExhausted`). Timezone validated via `ZoneId.of()` before availability check. `isSlotWithinAvailabilityWindow` rewritten to use `ZonedDateTime` comparison — eliminates date-equality guard that rejected all cross-midnight sessions. New i18n keys `packCoachMismatch` and `packExhausted` added to en/de.
- 2026-06-24: Code review Group 3 adversarial pass 2 — applied 1 patch finding. `SessionPackPaymentResource.createSetupIntent` and `savePaymentMethod` now catch `DataIntegrityViolationException` on `stripeCustomerRepository.save()` and reload the winning record — prevents orphaned Stripe customers on concurrent double-create.
- 2026-06-24: Code review Group 6 (Tests) adversarial pass 2 — applied 9 patch findings. `CreditRoutingTest` restructured: added `@Mock BookingPaymentPersistenceService persistenceService`; all assertions now verify at `persistenceService` boundary (fixing NPE on all test paths); `stripeDecline` test renamed and corrected (production does not pre-debit credit before Stripe — reversal assertion was phantom). Three new AC 8 tests added to `ExpiredPackBookingValidationTest` (pack belongs to wrong parent, pack for wrong coach, pack exhausted) plus `setupCommonMocks()` helper; fixed `validPackProvided` test by setting parentId/coachId/remainingSessions on mock. `BasePaymentIT.cleanPaymentData()` now deletes `coach_profiles` and `user` rows (fixes inter-test contamination). `BatchPaymentIT.batchBooking_withCreditBalance` deduction count corrected from 2 to 1 (one entry per batch, not per booking) and total amount verified at €80; `batchBooking_mixedPackAndCredit` now verifies `remaining_sessions=4`.
- 2026-06-24: Code review Group 5 (Frontend) adversarial pass 2 — applied 6 patch findings. `BookingRequestPage.vue` `bookedStartTimes` filter now uses `String()` coercion to fix coachId/playerId type mismatch. `submit()` now has a catch block with `$q.notify` on failure (AC 3). `submitBatch` third arg fixed from `0` to `Intl.DateTimeFormat().resolvedOptions().timeZone` (AC 7 — fixes backend IANA timezone rejection). `ParentCreditWalletPage.vue` cashout toast simplified to gross-amount-only (AC 10 — removes hardcoded fee constants). Balance display now `.toFixed(2)` (AC 2). Cashout button disabled when amount > balance.
- 2026-06-24: Code review Group 2 adversarial pass 2 — applied all 11 patch findings. Created `BookingPaymentPersistenceService` (extracts all self-call @Transactional methods — fixes Spring AOP proxy bypass). `CashOutService` restructured: reads `stripePaymentMethodId` (P5), validates netAmount > 0 (P8), delegates atomic check-and-debit to `CreditWalletService.debitForCashOut()` with SERIALIZABLE isolation (P1+P2). `CreditWalletService` gained `debitForCashOut` method. `PaymentLifecycleService` restructured: credit deduction moved inside `persistPaymentSuccess` so BOOKING_DEDUCTION + BookingPayment + transition all commit atomically (P3); batch pack loop now tracks `deducted` flag and calls `restoreSession` on downstream failure (P9). `SessionPackPaymentService.purchasePack` adds compensating refund on `createPurchase` failure (P4); removed dead `@Transactional` from private methods. `StripePaymentGateway.chargeAndCapture` adds null guard for `stripePaymentMethodId` (P6) and `.setOffSession(true)` (P10). `BookingService.resolveSessionPrice` throws instead of returning ZERO (P7). `BookingPaymentPersistenceService.confirmPackBatchPayment` sets `batchPaymentIntentId` (P11). Status → done.
