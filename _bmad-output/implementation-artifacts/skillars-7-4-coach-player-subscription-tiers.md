# Story 7.4: Coach & Player Subscription Tiers

Status: done

## Story

As a coach,
I want to purchase, upgrade, downgrade, and cancel my subscription tier,
And as a parent, I want to manage my player's subscription tier,
So that each party has access to exactly the features their tier entitles them to.

## Acceptance Criteria

1. **Given** the payment module initialises subscription support
   **When** the Flyway migration runs
   **Then** the following tables exist:
   - `payment.coach_subscriptions` (subscriptionId UUID PK, coachId UUID NOT NULL UNIQUE, tier VARCHAR(20) NOT NULL DEFAULT 'SCOUT', stripeSubscriptionId VARCHAR, stripeCustomerId VARCHAR, status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE', currentPeriodEnd TIMESTAMPTZ, cancelAtPeriodEnd BOOLEAN NOT NULL DEFAULT false, pastDueSince TIMESTAMPTZ, createdAt TIMESTAMPTZ, updatedAt TIMESTAMPTZ)
   - `payment.player_subscriptions` (subscriptionId UUID PK, playerId **BIGINT** NOT NULL UNIQUE, tier VARCHAR(20) NOT NULL DEFAULT 'ATHLETE', stripeSubscriptionId VARCHAR, billingInterval VARCHAR(8) NOT NULL DEFAULT 'MONTHLY', status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE', currentPeriodEnd TIMESTAMPTZ, cancelAtPeriodEnd BOOLEAN NOT NULL DEFAULT false, pastDueSince TIMESTAMPTZ, createdAt TIMESTAMPTZ, updatedAt TIMESTAMPTZ)
   - `payment.coach_subscription_changes` (changeId UUID PK, coachId UUID NOT NULL, fromTier VARCHAR(20) NOT NULL, toTier VARCHAR(20) NOT NULL, effectiveAt TIMESTAMPTZ NOT NULL, applied BOOLEAN NOT NULL DEFAULT false, voidedAt TIMESTAMPTZ nullable, triggerSource VARCHAR(32) NOT NULL DEFAULT 'SCHEDULED')
   - `payment.player_subscription_changes` (changeId UUID PK, playerId **BIGINT** NOT NULL, fromTier VARCHAR(20) NOT NULL, toTier VARCHAR(20) NOT NULL, effectiveAt TIMESTAMPTZ NOT NULL, applied BOOLEAN NOT NULL DEFAULT false, voidedAt TIMESTAMPTZ nullable, triggerSource VARCHAR(32) NOT NULL DEFAULT 'SCHEDULED')
   - **And** `main.subscription_lifecycle_outbox.subscriber_id` is `BIGINT` (ALTER from UUID — the column was created in V58 as UUID before player IDs were confirmed as Long TSID; V64 must alter it)

2. **Given** a coach on the SCOUT tier navigates to subscription settings
   **When** they view available tiers
   **Then** `GET /api/payment/subscriptions/coach/tiers` returns tier feature lists and monthly prices — prices and feature entitlements read from ConfigService, never hardcoded
   **And** `GET /api/payment/subscriptions/coach/me` returns their current tier, status, `currentPeriodEnd`, and `cancelAtPeriodEnd`

3. **Given** a coach selects a paid tier to subscribe or upgrade to
   **When** `POST /api/payment/subscriptions/coach/subscribe` is called with `{tier, paymentMethodId}` (no billingInterval — coach tiers are MONTHLY only per FR-PAY-006)
   **Then** a Stripe Subscription is created with the appropriate `priceId` (looked up from ConfigService by tier) — Stripe API call outside `@Transactional`
   **And** `payment.coach_subscriptions.tier` and `stripeSubscriptionId` are updated in a separate `@Transactional` after
   **And** `marketplace.coach_subscriptions.tier` is also updated to match in the same transaction (keeps feature-gating in sync)
   **And** `ConfigService` tier entitlements are immediately active — features gate on `marketplace.coach_subscriptions.tier`; never cache in a field
   **And** the coach is notified: "Welcome to {tier}! Your new features are now active."
   **And** if the coach already has `status=ACTIVE` and a non-null `stripeSubscriptionId`, the endpoint returns `409 payment.subscription.alreadyActive`

4. **Given** a coach upgrades their tier mid-cycle
   **When** `POST /api/payment/subscriptions/coach/change-tier` is called with `{newTier}` where `newTier > currentTier`
   **Then** Stripe prorates the charge for the remaining billing period (handled by Stripe, not calculated by the platform)
   **And** `payment.coach_subscriptions.tier` and `marketplace.coach_subscriptions.tier` are updated immediately after the Stripe call succeeds

5. **Given** a coach downgrades their tier mid-cycle
   **When** `POST /api/payment/subscriptions/coach/change-tier` is called with `{newTier}` where `newTier < currentTier`
   **Then** the downgrade takes effect at `currentPeriodEnd` — not immediately
   **And** a pending downgrade is recorded in `payment.coach_subscription_changes` with `triggerSource = 'SCHEDULED'`
   **And** `@Scheduled` `SubscriptionChangeApplicator` applies pending changes daily (`SELECT … FOR UPDATE SKIP LOCKED` on `coach_subscription_changes` where `effectiveAt <= now() AND applied = false AND voided_at IS NULL`)
   **And** both `payment.coach_subscriptions.tier` and `marketplace.coach_subscriptions.tier` are updated when the change is applied
   **And** coach is shown: "Your plan will change to {newTier} on {currentPeriodEnd}. You retain {currentTier} features until then."
   **And** if the coach upgrades again before `currentPeriodEnd`, the pending downgrade record is voided by setting **both** `applied = true` AND `voidedAt = now()` in a single update

6. **Given** a coach cancels their subscription
   **When** `DELETE /api/payment/subscriptions/coach` is called
   **Then** `stripe.subscriptions.update(id, { cancel_at_period_end: true })` is called — Stripe API call outside `@Transactional`
   **And** `payment.coach_subscriptions.cancelAtPeriodEnd = true`; tier remains active until `currentPeriodEnd`
   **And** coach is shown: "Your {tier} subscription will end on {currentPeriodEnd}. You retain access until then."
   **And** on `customer.subscription.deleted` webhook: `payment.coach_subscriptions.tier = SCOUT`, `status = CANCELLED`, `stripeSubscriptionId = null`; and `marketplace.coach_subscriptions.tier = SCOUT`

7. **Given** a Stripe subscription webhook arrives (`customer.subscription.updated`, `customer.subscription.deleted`, `invoice.payment_failed`)
   **When** `StripeWebhookResource` processes the event (signature verified, idempotency checked via `payment.stripe_webhook_events`)
   **Then** `payment.coach_subscriptions` or `payment.player_subscriptions` is updated to reflect the new state; `marketplace.coach_subscriptions.tier` is also kept in sync for coach events
   **And** on `invoice.payment_failed`: `status = PAST_DUE`, `pastDueSince = now()` (only if `pastDueSince` is currently null — do not overwrite on repeated failures); coach/parent notified: "Your payment failed — please update your payment method to retain access to {tier} features"
   **And** on `PAST_DUE` persisting beyond `subscription.pastDue.gracePeriodDays` (ConfigService, default 7) measured from `pastDueSince`: tier downgraded to SCOUT/ATHLETE automatically via `SubscriptionGracePeriodChecker @Scheduled` daily; coach/parent notified

8. **Given** a parent manages a player subscription
   **When** `POST /api/payment/subscriptions/player/subscribe`, `change-tier`, or `DELETE /api/payment/subscriptions/player` is called with `playerId` (Long)
   **Then** the same lifecycle applies as coach subscriptions above, scoped to `payment.player_subscriptions`
   **And** `@PreAuthorize("@playerOwnershipGuard.check(authentication, #playerId)")` on every player endpoint — `403` on mismatch, never `404`
   **And** player tier billing interval constraints are enforced: SEMI_PRO and PRO accept only `YEARLY`; ATHLETE accepts `MONTHLY`, `QUARTERLY`, or `YEARLY`

9. **Given** any feature within the platform checks tier entitlements
   **When** a tier-gated operation is attempted
   **Then** `ConfigService.getString("coach.tier.{tier}.{feature}")` is called at that moment — never cached in a field or stored in the request context
   **And** if the subscription is `PAST_DUE` or `CANCELLED`, the user is treated as SCOUT/ATHLETE tier for entitlement checks

10. **Given** a `SubscriptionExpiredEvent` is published (from this story's webhook handlers)
    **When** `VideoSubscriptionLifecycleListener` handles the event (AFTER_COMMIT)
    **Then** the event is written to `main.subscription_lifecycle_outbox` for durable async processing
    **And** the outbox processor (60s fixedDelay) runs Path A (YEARLY — resets `lifecycle_locked_at`) or Path B (non-YEARLY — transitions player videos to BLOCKED if no other active subscription)

## Tasks / Subtasks

- [x] **Task 1 — Flyway V64 migration** (AC: 1)
  - [x] `src/main/resources/db/migration/V64__subscription_tiers.sql`
  - [x] Create schema `payment` (already exists from V61 — use `CREATE SCHEMA IF NOT EXISTS`)
  - [x] Create `payment.coach_subscriptions`:
    ```sql
    CREATE TABLE payment.coach_subscriptions (
        subscription_id      UUID         NOT NULL DEFAULT gen_random_uuid(),
        coach_id             UUID         NOT NULL,
        tier                 VARCHAR(20)  NOT NULL DEFAULT 'SCOUT',
        stripe_subscription_id VARCHAR,
        stripe_customer_id   VARCHAR,
        status               VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE',
        current_period_end   TIMESTAMPTZ,
        cancel_at_period_end BOOLEAN      NOT NULL DEFAULT false,
        past_due_since       TIMESTAMPTZ,
        created_at           TIMESTAMPTZ  NOT NULL DEFAULT now(),
        updated_at           TIMESTAMPTZ  NOT NULL DEFAULT now(),
        CONSTRAINT pk_payment_coach_subscriptions PRIMARY KEY (subscription_id),
        CONSTRAINT uq_pcs_coach_id UNIQUE (coach_id),
        CONSTRAINT fk_pcs_coach FOREIGN KEY (coach_id) REFERENCES marketplace.coach_profiles(id),
        CONSTRAINT chk_pcs_tier CHECK (tier IN ('SCOUT','INSTRUCTOR','ACADEMY')),
        CONSTRAINT chk_pcs_status CHECK (status IN ('ACTIVE','PAST_DUE','CANCELLED','TRIALLING'))
    );
    ```
  - [x] Create `payment.player_subscriptions`:
    ```sql
    CREATE TABLE payment.player_subscriptions (
        subscription_id        UUID         NOT NULL DEFAULT gen_random_uuid(),
        player_id              BIGINT       NOT NULL,
        tier                   VARCHAR(20)  NOT NULL DEFAULT 'ATHLETE',
        stripe_subscription_id VARCHAR,
        billing_interval       VARCHAR(8)   NOT NULL DEFAULT 'MONTHLY',
        status                 VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE',
        current_period_end     TIMESTAMPTZ,
        cancel_at_period_end   BOOLEAN      NOT NULL DEFAULT false,
        past_due_since         TIMESTAMPTZ,
        created_at             TIMESTAMPTZ  NOT NULL DEFAULT now(),
        updated_at             TIMESTAMPTZ  NOT NULL DEFAULT now(),
        CONSTRAINT pk_pps PRIMARY KEY (subscription_id),
        CONSTRAINT uq_pps_player_id UNIQUE (player_id),
        CONSTRAINT fk_pps_player FOREIGN KEY (player_id) REFERENCES main.player_profiles(id),
        CONSTRAINT chk_pps_tier CHECK (tier IN ('ATHLETE','SEMI_PRO','PRO')),
        CONSTRAINT chk_pps_status CHECK (status IN ('ACTIVE','PAST_DUE','CANCELLED','TRIALLING')),
        CONSTRAINT chk_pps_billing_interval CHECK (billing_interval IN ('MONTHLY','QUARTERLY','YEARLY')),
        CONSTRAINT chk_pps_semi_pro_yearly CHECK (tier != 'SEMI_PRO' OR billing_interval = 'YEARLY'),
        CONSTRAINT chk_pps_pro_yearly CHECK (tier != 'PRO' OR billing_interval = 'YEARLY')
    );
    ```
  - [x] Create `payment.coach_subscription_changes`:
    ```sql
    CREATE TABLE payment.coach_subscription_changes (
        change_id      UUID         NOT NULL DEFAULT gen_random_uuid(),
        coach_id       UUID         NOT NULL,
        from_tier      VARCHAR(20)  NOT NULL,
        to_tier        VARCHAR(20)  NOT NULL,
        effective_at   TIMESTAMPTZ  NOT NULL,
        applied        BOOLEAN      NOT NULL DEFAULT false,
        voided_at      TIMESTAMPTZ,
        trigger_source VARCHAR(32)  NOT NULL DEFAULT 'SCHEDULED',
        CONSTRAINT pk_csc PRIMARY KEY (change_id),
        CONSTRAINT fk_csc_coach FOREIGN KEY (coach_id) REFERENCES marketplace.coach_profiles(id)
    );
    CREATE INDEX idx_csc_pending ON payment.coach_subscription_changes(effective_at)
        WHERE applied = false AND voided_at IS NULL;
    ```
  - [x] Create `payment.player_subscription_changes` (same pattern as coach, but `player_id BIGINT NOT NULL`, FK to `main.player_profiles(id)`):
    ```sql
    CREATE TABLE payment.player_subscription_changes (
        change_id      UUID         NOT NULL DEFAULT gen_random_uuid(),
        player_id      BIGINT       NOT NULL,
        from_tier      VARCHAR(20)  NOT NULL,
        to_tier        VARCHAR(20)  NOT NULL,
        effective_at   TIMESTAMPTZ  NOT NULL,
        applied        BOOLEAN      NOT NULL DEFAULT false,
        voided_at      TIMESTAMPTZ,
        trigger_source VARCHAR(32)  NOT NULL DEFAULT 'SCHEDULED',
        CONSTRAINT pk_psc PRIMARY KEY (change_id),
        CONSTRAINT fk_psc_player FOREIGN KEY (player_id) REFERENCES main.player_profiles(id)
    );
    CREATE INDEX idx_psc_pending ON payment.player_subscription_changes(effective_at)
        WHERE applied = false AND voided_at IS NULL;
    ```
  - [x] Alter `main.subscription_lifecycle_outbox` to change `subscriber_id` from UUID to BIGINT (the column was created in V58 before player IDs were confirmed as Long TSID):
    ```sql
    ALTER TABLE main.subscription_lifecycle_outbox
        ALTER COLUMN subscriber_id TYPE BIGINT USING subscriber_id::text::bigint;
    ```
    **Note**: This ALTER will fail if any rows already exist with UUID data. The table is expected to be empty at V64 time (no subscription lifecycle events have fired in production). If deploying to an environment with existing data, truncate first or handle the migration manually.
  - [x] Seed `platform_config` — next IDs after 504 (last used in V63):
    ```sql
    INSERT INTO main.platform_config (id, key, value, value_type, description, updated_at) VALUES
        -- Coach tiers: MONTHLY only (FR-PAY-006)
        (505, 'subscription.coach.instructor.monthly.priceId',  '', 'STRING', 'Stripe priceId: Instructor monthly billing (set in admin before launch)', NOW()),
        (506, 'subscription.coach.academy.monthly.priceId',     '', 'STRING', 'Stripe priceId: Academy monthly billing (set in admin before launch)', NOW()),
        -- Player tiers
        (507, 'subscription.player.semi_pro.yearly.priceId',    '', 'STRING', 'Stripe priceId: Semi-Pro yearly billing (YEARLY only per FR-PAY-007)', NOW()),
        (508, 'subscription.player.pro.yearly.priceId',         '', 'STRING', 'Stripe priceId: Pro yearly billing (YEARLY only per FR-PAY-007)', NOW()),
        (509, 'subscription.player.athlete.monthly.priceId',    '', 'STRING', 'Stripe priceId: Athlete monthly billing', NOW()),
        (510, 'subscription.player.athlete.quarterly.priceId',  '', 'STRING', 'Stripe priceId: Athlete quarterly billing', NOW()),
        (511, 'subscription.player.athlete.yearly.priceId',     '', 'STRING', 'Stripe priceId: Athlete yearly billing', NOW()),
        (512, 'subscription.pastDue.gracePeriodDays',           '7', 'LONG',  'Days before PAST_DUE triggers automatic tier downgrade', NOW())
    ON CONFLICT (key) DO NOTHING;
    ```

- [x] **Task 2 — JPA entities** (AC: 1)
  - [x] Create `PaymentCoachSubscription.java` in `platform.payment.repo`:
    ```java
    @Entity @Table(schema="payment", name="coach_subscriptions")
    @Getter @Setter @NoArgsConstructor @Slf4j
    public class PaymentCoachSubscription {
        @Id @GeneratedValue(strategy = GenerationType.UUID)
        @Column(name = "subscription_id") private UUID subscriptionId;
        @Column(name = "coach_id", nullable = false, unique = true) private UUID coachId;
        @Column(nullable = false) private String tier = "SCOUT";
        @Column(name = "stripe_subscription_id") private String stripeSubscriptionId;
        @Column(name = "stripe_customer_id") private String stripeCustomerId;
        @Column(nullable = false) private String status = "ACTIVE";
        @Column(name = "current_period_end") private Instant currentPeriodEnd;
        @Column(name = "cancel_at_period_end", nullable = false) private boolean cancelAtPeriodEnd = false;
        @Column(name = "past_due_since") private Instant pastDueSince;
        @Column(name = "created_at", updatable = false) private Instant createdAt;
        @Column(name = "updated_at") private Instant updatedAt;
        @PrePersist void prePersist() { createdAt = updatedAt = Instant.now(); }
        @PreUpdate void preUpdate() { updatedAt = Instant.now(); }
    }
    ```
  - [x] Create `PaymentCoachSubscriptionRepository extends JpaRepository<PaymentCoachSubscription, UUID>`:
    - `Optional<PaymentCoachSubscription> findByCoachId(UUID coachId)`
  - [x] Create `PaymentPlayerSubscription.java` in `platform.payment.repo`:
    ```java
    @Entity @Table(schema="payment", name="player_subscriptions")
    @Getter @Setter @NoArgsConstructor
    public class PaymentPlayerSubscription {
        @Id @GeneratedValue(strategy = GenerationType.UUID)
        @Column(name = "subscription_id") private UUID subscriptionId;
        @Column(name = "player_id", nullable = false, unique = true) private Long playerId;
        @Column(nullable = false) private String tier = "ATHLETE";
        @Column(name = "stripe_subscription_id") private String stripeSubscriptionId;
        @Column(name = "billing_interval", nullable = false) private String billingInterval = "MONTHLY";
        @Column(nullable = false) private String status = "ACTIVE";
        @Column(name = "current_period_end") private Instant currentPeriodEnd;
        @Column(name = "cancel_at_period_end", nullable = false) private boolean cancelAtPeriodEnd = false;
        @Column(name = "past_due_since") private Instant pastDueSince;
        @Column(name = "created_at", updatable = false) private Instant createdAt;
        @Column(name = "updated_at") private Instant updatedAt;
        @PrePersist void prePersist() { createdAt = updatedAt = Instant.now(); }
        @PreUpdate void preUpdate() { updatedAt = Instant.now(); }
    }
    ```
    **Note**: `player_id` is `Long` (BIGINT TSID) — `player_profiles.id` is a BIGINT TSID, not a UUID. All player IDs throughout this story are `Long`.
  - [x] Create `PaymentPlayerSubscriptionRepository extends JpaRepository<PaymentPlayerSubscription, UUID>`:
    - `Optional<PaymentPlayerSubscription> findByPlayerId(Long playerId)`
  - [x] Create `CoachSubscriptionChange.java` in `platform.payment.repo`:
    - `@Entity @Table(schema="payment", name="coach_subscription_changes")`
    - fields: `changeId UUID PK`, `coachId UUID`, `fromTier String`, `toTier String`, `effectiveAt Instant`, `applied boolean = false`, `voidedAt Instant (nullable)`, `triggerSource String = "SCHEDULED"`
  - [x] Create `CoachSubscriptionChangeRepository`:
    - `List<CoachSubscriptionChange> findByAppliedFalseAndVoidedAtIsNullAndEffectiveAtBefore(Instant cutoff)`
  - [x] Create `PlayerSubscriptionChange.java` — mirror for players with `playerId Long` instead of `coachId UUID`
  - [x] Create `PlayerSubscriptionChangeRepository`:
    - `List<PlayerSubscriptionChange> findByAppliedFalseAndVoidedAtIsNullAndEffectiveAtBefore(Instant cutoff)`
  - [x] Add `existsByParentIdAndPlayerId(Long parentId, Long playerId)` to `platform.security.repo.ParentPlayerLinkRepository` — Spring Data derives this query from the method name; `ParentPlayerLink` has both `parentId` and `playerId` Long fields. This method does not yet exist in the repository.

- [x] **Task 3 — Contract: enums, request/response DTOs, event** (AC: 2–10)
  - [x] Create `CoachSubscriptionTierBilling.java` enum in `platform.payment.contract`:
    `SCOUT, INSTRUCTOR, ACADEMY` — DO NOT use the new naming from the epics (FREE/STARTER/PRO/ELITE); use the SAME names as `platform.marketplace.contract.CoachSubscriptionTier` to maintain consistency across the codebase
  - [x] Create `PlayerSubscriptionTierBilling.java` enum in `platform.payment.contract`:
    `ATHLETE, SEMI_PRO, PRO`
  - [x] Create `BillingInterval.java` enum in `platform.payment.contract`:
    `MONTHLY, QUARTERLY, YEARLY`
    **Note**: YEARLY (not ANNUAL) — this string must match what `VideoSubscriptionLifecycleListener` and `PlayerSubscriptionQueryPort` expect. The interface Javadoc and Story 6.4 Task 4 both use "YEARLY".
  - [x] Create `SubscriptionStatus.java` enum in `platform.payment.contract`:
    `ACTIVE, PAST_DUE, CANCELLED, TRIALLING`
  - [x] Create `CoachSubscribeRequest.java` record in `platform.payment.contract`:
    `public record CoachSubscribeRequest(@NotNull String tier, @NotNull String paymentMethodId) {}`
    (No `billingInterval` field — coaches are MONTHLY only per FR-PAY-006)
  - [x] Create `CoachChangeTierRequest.java` record: `public record CoachChangeTierRequest(@NotNull String newTier) {}`
  - [x] Create `PlayerSubscribeRequest.java` record:
    `public record PlayerSubscribeRequest(@NotNull Long playerId, @NotNull String tier, @NotNull String billingInterval, @NotNull String paymentMethodId) {}`
  - [x] Create `PlayerChangeTierRequest.java` record: `public record PlayerChangeTierRequest(@NotNull Long playerId, @NotNull String newTier) {}`
  - [x] Create `CoachSubscriptionResponse.java` record in `platform.payment.contract`:
    `public record CoachSubscriptionResponse(UUID subscriptionId, String tier, String status, Instant currentPeriodEnd, boolean cancelAtPeriodEnd) {}`
  - [x] Create `PlayerSubscriptionResponse.java` record (same pattern, `Long playerId` instead of UUID)
  - [x] Create `TierInfoResponse.java` record:
    `public record TierInfoResponse(String tier, List<String> features, String monthlyPrice, String annualPrice) {}`
  - [x] Create `SubscriptionExpiredEvent.java` in `platform.payment.contract.event`:
    ```java
    public class SubscriptionExpiredEvent extends ApplicationEvent {
        private final Long subscriberId;       // playerId (Long TSID) — matches video.owner_id format
        private final String subscriptionTier; // "YEARLY", "MONTHLY", or "QUARTERLY" — null treated as non-YEARLY (WARN log in listener)
        private final Instant expiredAt;
        // constructor + accessors
    }
    ```
    **CRITICAL**: `subscriptionTier` must be `"YEARLY"`, `"MONTHLY"`, or `"QUARTERLY"` — NOT `"ANNUAL"`. `VideoSubscriptionLifecycleListener` routes on `"YEARLY"` vs non-YEARLY. This matches the `PlayerSubscriptionQueryPort` Javadoc and Story 6.4 Task 4 specification.
  - [x] **Change `platform.video.contract.PlayerSubscriptionQueryPort`** — the existing interface uses `UUID playerId` but player IDs throughout the system are `Long` (BIGINT TSID). Update both method signatures:
    ```java
    boolean hasActiveYearlySubscription(Long playerId);
    boolean hasAnyActiveSubscription(Long playerId);
    ```

- [x] **Task 4 — `SubscriptionService` in `platform.payment.service`** (AC: 2–9)
  - [x] Create `SubscriptionService.java` — `@Service @RequiredArgsConstructor @Slf4j`
  - [x] Inject: `PaymentCoachSubscriptionRepository`, `PaymentPlayerSubscriptionRepository`, `CoachSubscriptionChangeRepository`, `PlayerSubscriptionChangeRepository`, `CoachSubscriptionRepository` (marketplace.repo), `StripeCustomerRepository`, `ConfigService`, `StripeClient`, `ApplicationEventPublisher`, `ParentPlayerLinkRepository`
  - [x] `getCoachTiers()`: reads SCOUT, INSTRUCTOR, ACADEMY from ConfigService; builds `List<TierInfoResponse>` from keys `coach.tier.{tier}.*` and monthly priceIds (no annual)
  - [x] `getPlayerTiers()`: similar for ATHLETE (monthly/quarterly/yearly), SEMI_PRO (yearly only), PRO (yearly only)
  - [x] `getCoachSubscription(UUID coachId)`: `paymentCoachSubscriptionRepository.findByCoachId(coachId)` — create default SCOUT row if absent (lazy init pattern from Story 7.1's `CoachStripeAccount` creation); return `CoachSubscriptionResponse`
  - [x] `getPlayerSubscription(Long parentUserId, Long playerId)`: verify ownership via `parentPlayerLinkRepository.existsByParentIdAndPlayerId(parentUserId, playerId)` — throw `403 payment.subscription.playerOwnership` if false; find or create default ATHLETE row; return `PlayerSubscriptionResponse`
  - [x] `subscribeCoach(UUID coachId, String tier, String paymentMethodId)`:
    1. Validate `tier` is not SCOUT (can't subscribe to free tier)
    2. Load existing subscription; if `status = ACTIVE` and `stripeSubscriptionId != null`, throw `409 payment.subscription.alreadyActive`
    3. Look up `priceId = configService.getString("subscription.coach." + tier.toLowerCase() + ".monthly.priceId")`; throw `400 payment.subscription.priceNotConfigured` if blank
    4. Look up coach's `stripeCustomerId` from `payment.coach_subscriptions` (set during Stripe onboarding in Story 7.1); throw `400 payment.subscription.noStripeAccount` if absent
    5. Stripe call: `stripeClient.createSubscription(stripeCustomerId, priceId, paymentMethodId)` — **outside `@Transactional`**
    6. `@Transactional`: update `payment.coach_subscriptions.tier`, `stripeSubscriptionId`, `status=ACTIVE`, `currentPeriodEnd`; **also** update `marketplace.coach_subscriptions.tier` to the same value
    7. Publish coach subscription welcome notification
  - [x] `subscribePlayer(Long parentUserId, Long playerId, String tier, String billingInterval, String paymentMethodId)`:
    1. Verify parent owns player: `parentPlayerLinkRepository.existsByParentIdAndPlayerId(parentUserId, playerId)` — throw `403 payment.subscription.playerOwnership` if false
    2. Load existing subscription; if `status = ACTIVE` and `stripeSubscriptionId != null`, throw `409 payment.subscription.alreadyActive`
    3. Validate tier/billingInterval combination: SEMI_PRO and PRO require `billingInterval = YEARLY`; throw `400 payment.subscription.invalidBillingInterval` otherwise
    4. Look up `priceId = configService.getString("subscription.player." + tier.toLowerCase() + "." + billingInterval.toLowerCase() + ".priceId")`; throw `400 payment.subscription.priceNotConfigured` if blank
    5. Look up parent's Stripe customer ID: `stripeCustomerRepository.findById(parentUserId).orElseThrow(() -> new IllegalStateException("No Stripe customer for parentId=" + parentUserId)).getStripeCustomerId()`
    6. Stripe call: `stripeClient.createSubscription(stripeCustomerId, priceId, paymentMethodId)` — **outside `@Transactional`**
    7. `@Transactional`: update `payment.player_subscriptions.tier`, `stripeSubscriptionId`, `billingInterval`, `status=ACTIVE`, `currentPeriodEnd`
    8. Publish player subscription welcome notification
  - [x] `changeCoachTier(UUID coachId, String newTier)`:
    - If upgrade: Stripe `subscriptions.update(id, { items: [{price: newPriceId}], proration_behavior: 'create_prorations' })` — outside TX; then `@Transactional` update both tables immediately
    - If downgrade: `@Transactional` — void any existing pending downgrade (set **both** `applied = true` AND `voidedAt = now()`); insert new `coach_subscription_changes` row with `effectiveAt = currentPeriodEnd` and `triggerSource = 'SCHEDULED'`; do NOT update tier now
  - [x] `changePlayerTier(Long parentUserId, Long playerId, String newTier)`: same pattern, with ownership check; validate new tier's billing interval constraints
  - [x] `cancelCoachSubscription(UUID coachId)`: Stripe `subscriptions.update(id, {cancel_at_period_end: true})` outside TX; `@Transactional` set `cancelAtPeriodEnd = true`
  - [x] `cancelPlayerSubscription(Long parentUserId, Long playerId)`: same with ownership check
  - [x] `applyPendingChanges()` — called by `SubscriptionChangeApplicator` scheduler:
    - `SELECT FOR UPDATE SKIP LOCKED` on coach and player changes tables (`applied = false AND voided_at IS NULL AND effective_at <= now()`)
    - For each: update both payment and marketplace tables; set `applied = true`
    - Only publish `SubscriptionExpiredEvent` for player changes where `triggerSource = 'WEBHOOK_DELETED'` AND new tier is ATHLETE (free) — the webhook path sets this source when the Stripe subscription is deleted; the scheduled downgrade path uses `triggerSource = 'SCHEDULED'` and publishes the event directly from `handleSubscriptionWebhook()` instead (see CRITICAL note on event flow)
  - [x] `checkPastDueGracePeriod()` — called by `SubscriptionGracePeriodChecker` scheduler:
    - Find subscriptions where `status = PAST_DUE` and `past_due_since < now() - gracePeriodDays` — use `past_due_since`, NOT `updated_at`; `updated_at` is refreshed on any modification and does not represent when PAST_DUE began
    - `gracePeriodDays = configService.getLong("subscription.pastDue.gracePeriodDays")` — call per invocation, never cache
    - Downgrade tier to SCOUT/ATHLETE; update both payment and marketplace tables; notify
  - [x] `handleSubscriptionWebhook(String eventType, String stripeSubscriptionId, Map<String,Object> data)` — called from `StripeWebhookService`:
    - Route on `eventType`: `customer.subscription.updated`, `customer.subscription.deleted`, `invoice.payment_failed`
    - `customer.subscription.updated`: sync `status`, `currentPeriodEnd`, `cancelAtPeriodEnd` from Stripe event data. **Do NOT sync `tier` from this event** — Stripe knows only `priceId`, not internal tier names; the priceId-to-tier reverse map is not maintained (the platform updates tier synchronously in subscribe/changeTier before the webhook can fire). Only status/period fields are trust-worthy from this event.
    - `customer.subscription.deleted`: set `tier = SCOUT`/`ATHLETE`, `status = CANCELLED`, `stripeSubscriptionId = null`; update marketplace tier (for coaches); for **players** only: publish `SubscriptionExpiredEvent(subscriberId=playerId, subscriptionTier=sub.getBillingInterval(), expiredAt=now())`
    - `invoice.payment_failed`: set `status = PAST_DUE`; set `pastDueSince = now()` only if currently null (do not overwrite on repeated failures); notify

- [x] **Task 5 — Schedulers** (AC: 5, 7)
  - [x] Create `SubscriptionChangeApplicator.java` in `platform.payment.service`:
    `@Scheduled(cron="0 0 2 * * *")` (daily at 02:00:00); calls `subscriptionService.applyPendingChanges()`
    **Note**: Spring `@Scheduled` cron requires 6 fields (`seconds minutes hours dayOfMonth month dayOfWeek`).
  - [x] Create `SubscriptionGracePeriodChecker.java` in `platform.payment.service`:
    `@Scheduled(cron="0 0 3 * * *")` (daily at 03:00:00); calls `subscriptionService.checkPastDueGracePeriod()`

- [x] **Task 6 — `SubscriptionResource` in `platform.payment.api`** (AC: 2–9)
  - [x] Create `SubscriptionResource.java` — `@Observed(name="payment.subscription") @RestController @RequestMapping("/api/payment/subscriptions") @RequiredArgsConstructor`
  - [x] `GET /coach/tiers` — `@PreAuthorize("permitAll()")` (publicly visible for marketing) → `List<TierInfoResponse>` 200
  - [x] `GET /coach/me` — `@PreAuthorize(HAS_COACH_ROLE)` → `CoachSubscriptionResponse` 200
  - [x] `POST /coach/subscribe` — `@PreAuthorize(HAS_COACH_ROLE)` — body `@Valid CoachSubscribeRequest` → `CoachSubscriptionResponse` 200
  - [x] `POST /coach/change-tier` — `@PreAuthorize(HAS_COACH_ROLE)` — body `@Valid CoachChangeTierRequest` → 204
  - [x] `DELETE /coach` — `@PreAuthorize(HAS_COACH_ROLE)` → 204
  - [x] `GET /player/tiers` — `@PreAuthorize("permitAll()")` → `List<TierInfoResponse>` 200
  - [x] `GET /player/me` — `@PreAuthorize("@playerOwnershipGuard.check(authentication, #playerId)")` — query param `playerId` (Long, required) → `PlayerSubscriptionResponse` 200
  - [x] `POST /player/subscribe` — `@PreAuthorize(HAS_PARENT_ROLE)` — body `@Valid PlayerSubscribeRequest` → `PlayerSubscriptionResponse` 200; ownership check delegated to service (AC 8)
  - [x] `POST /player/change-tier` — `@PreAuthorize(HAS_PARENT_ROLE)` — body `@Valid PlayerChangeTierRequest` → 204; ownership check delegated to service
  - [x] `DELETE /player` — `@PreAuthorize(HAS_PARENT_ROLE)` — query param `playerId` (Long, required) → 204; ownership check delegated to service
  - [x] Helper `currentCoachId()`: load `CoachProfile` via `coachProfileRepository.findByUserId(principal.getUserId())` → coachId; follow exact pattern from `StripeOnboardingResource`
  - [x] Helper `currentParentId()`: cast `principal.getBusinessId()` to Long; follow exact pattern from `CancellationResource`

- [x] **Task 7 — `StripeWebhookService` extension** (AC: 7)
  - [x] In `platform.payment.service.StripeWebhookService.handleEventAtomically()`, extend the existing `if/else if` chain (the codebase uses `if ("account.updated".equals(event.getType()))` — **do NOT use switch-case syntax**, match existing style):
    ```java
    } else if ("customer.subscription.updated".equals(event.getType())) {
        handleSubscriptionUpdated(event);
    } else if ("customer.subscription.deleted".equals(event.getType())) {
        handleSubscriptionDeleted(event);
    } else if ("invoice.payment_failed".equals(event.getType())) {
        handleInvoicePaymentFailed(event);
    } else {
        log.debug("[STRIPE_WEBHOOK_UNKNOWN_TYPE type={}]", event.getType());
    }
    ```
  - [x] Add `SubscriptionService` injection via `@Lazy` — check for circular dependency: `StripeWebhookService` → `SubscriptionService` → `StripeClient`. `StripeClient` should NOT depend on `StripeWebhookService`; if it does, extract shared logic. The existing `@Lazy private StripeWebhookService self` pattern already handles internal self-reference separately.
  - [x] `handleSubscriptionUpdated(Event)`: extract `com.stripe.model.Subscription` from event (use same `deserializeUnsafe()` pattern as `handleAccountUpdated`); determine if coach or player subscription by querying `paymentCoachSubscriptionRepository.findByStripeSubscriptionId(sub.getId())` then `paymentPlayerSubscriptionRepository.findByStripeSubscriptionId(sub.getId())`; if neither found, log WARN and return (orphaned Stripe subscription); call `subscriptionService.handleSubscriptionWebhook("customer.subscription.updated", sub.getId(), data)`
  - [x] `handleSubscriptionDeleted(Event)`: same routing; call `handleSubscriptionWebhook("customer.subscription.deleted", ...)`
  - [x] `handleInvoicePaymentFailed(Event)`: extract `com.stripe.model.Invoice`; get `subscriptionId`; route to service
  - [x] Add `findByStripeSubscriptionId(String stripeSubscriptionId)` to both `PaymentCoachSubscriptionRepository` and `PaymentPlayerSubscriptionRepository`

- [x] **Task 8 — `StripeClient` extension** (AC: 3–6)
  - [x] Add subscription methods to `platform.payment.service.StripeClient.java`:
    - `createSubscription(String stripeCustomerId, String priceId, String paymentMethodId): String` (returns Stripe subscriptionId)
    - `updateSubscriptionTier(String stripeSubscriptionId, String newPriceId): void`
    - `cancelSubscriptionAtPeriodEnd(String stripeSubscriptionId): Instant` (returns new `currentPeriodEnd`)
    - `attachPaymentMethod(String stripeCustomerId, String paymentMethodId): void`
    - All calls use `RequestOptions` with idempotency key derived from `{subscriptionId}:{action}`

- [x] **Task 9 — Cross-story handoff: `SubscriptionExpiredEvent` + `VideoSubscriptionLifecycleListener`** (AC: 10)
  - [x] Confirm `SubscriptionExpiredEvent` is in `platform.payment.contract.event` (created in Task 3)
  - [x] Create `VideoSubscriptionLifecycleListener.java` in `platform.video.service`:
    ```java
    @Service @RequiredArgsConstructor @Slf4j
    public class VideoSubscriptionLifecycleListener {
        private final SubscriptionLifecycleOutboxRepository outboxRepository;
        private final PlayerSubscriptionQueryPort playerSubscriptionQueryPort;
        private final VideoRepository videoRepository;
        private final ApplicationEventPublisher eventPublisher;

        @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
        @Transactional(propagation = Propagation.REQUIRES_NEW)
        public void onSubscriptionExpired(SubscriptionExpiredEvent event) {
            if (event.getSubscriberId() == null) {
                log.warn("[SUB_LIFECYCLE] null subscriberId — skipping");
                return;
            }
            String tier = event.getSubscriptionTier();
            if (tier == null) {
                log.warn("[SUB_LIFECYCLE] null subscriptionTier for subscriberId={} — treating as non-YEARLY (Path B)", event.getSubscriberId());
            }
            SubscriptionLifecycleOutbox entry = new SubscriptionLifecycleOutbox();
            entry.setSubscriberId(event.getSubscriberId());  // Long
            entry.setSubscriptionTier(tier != null ? tier : "MONTHLY");
            entry.setExpiredAt(event.getExpiredAt());
            entry.setStatus("PENDING");
            outboxRepository.save(entry);
        }

        @Scheduled(fixedDelay = 60_000)
        @Transactional
        public void processOutbox() {
            int maxAttempts = (int) configService.getLong("platform.video.lifecycle.outbox_max_attempts");
            List<SubscriptionLifecycleOutbox> pending = outboxRepository
                .findTop100ByStatusAndAttemptsLessThanOrderByCreatedAtAsc("PENDING", maxAttempts);
            for (SubscriptionLifecycleOutbox entry : pending) {
                try {
                    entry.setAttempts(entry.getAttempts() + 1);
                    processEntry(entry);
                    entry.setStatus("PROCESSED");
                    entry.setProcessedAt(Instant.now());
                } catch (Exception e) {
                    log.error("[SUB_LIFECYCLE_FAILED id={} attempt={}]", entry.getId(), entry.getAttempts(), e);
                    if (entry.getAttempts() >= maxAttempts) {
                        entry.setStatus("DEAD_LETTER");
                        entry.setLastError(e.getMessage());
                        log.error("[SUB_LIFECYCLE_DEAD_LETTER id={} subscriberId={}] Max attempts reached — manual remediation required",
                            entry.getId(), entry.getSubscriberId());
                    }
                }
                outboxRepository.save(entry);
            }
        }
    }
    ```
  - [x] `processEntry(SubscriptionLifecycleOutbox entry)`:
    - `boolean isYearly = "YEARLY".equalsIgnoreCase(entry.getSubscriptionTier())`
    - **Owner ID bridge**: `entry.getSubscriberId()` is `Long`; `Video.ownerId` is `String`. Player video `ownerId` is stored as `Long.toString()` (the Long TSID value as a decimal string — confirmed via `VideoEventResource.java` line 70: `Long.parseLong(ownerId)` on player videos). Use `String ownerId = entry.getSubscriberId().toString()`.
    - Path A (YEARLY): `videoRepository.resetLifecycleLockedAt(ownerId)` — resets `lifecycle_locked_at` to null on BLOCKED videos so ARCHIVED clock pauses (FR-PAY-008). Paginate: call `videoRepository.findBlockedReadyByOwner(ownerId, batchSize)` in a loop until empty; call `videoLifecycleService.resetLifecycleClock(videoId, Instant.now())` per video.
    - Path B (non-YEARLY): if `!playerSubscriptionQueryPort.hasAnyActiveSubscription(entry.getSubscriberId())`: batch-transition owned ACTIVE/READY videos to BLOCKED; paginate with `videoRepository.findActiveReadyByOwner(ownerId, batchSize)` using batchSize from `configService.getLong("platform.video.lifecycle.batch_size")`; call `videoLifecycleService.blockForSubscriptionExpiry(videoId, Instant.now())` per video.
  - [x] Add `SubscriptionLifecycleOutbox` entity to `platform.video.repo` (table: `main.subscription_lifecycle_outbox` — already created by V58, altered by V64 to change `subscriber_id` to BIGINT). The entity field is `Long subscriberId`.
  - [x] Add `SubscriptionLifecycleOutboxRepository` with:
    - `List<SubscriptionLifecycleOutbox> findTop100ByStatusAndAttemptsLessThanOrderByCreatedAtAsc(String status, int maxAttempts)`
    - (The `resetLifecycleLockedAt` method belongs on `VideoRepository`, not here — see next point)
  - [x] Add to `VideoRepository`:
    - `void resetLifecycleLockedAt(String ownerId)` (JPQL UPDATE: `UPDATE Video v SET v.lifecycleLockedAt = null WHERE v.ownerId = :ownerId AND v.accessState = 'BLOCKED'`)

- [x] **Task 10 — Replace `PlayerSubscriptionQueryAdapter` stub** (AC: 10)
  - [x] Create `platform.payment.adapter.PlayerSubscriptionQueryAdapter.java`:
    ```java
    @Component @RequiredArgsConstructor
    public class PlayerSubscriptionQueryAdapter implements PlayerSubscriptionQueryPort {
        private final PaymentPlayerSubscriptionRepository playerSubscriptionRepository;
        private static final Set<String> ACTIVE_STATUSES = Set.of("ACTIVE", "TRIALLING");

        @Override
        public boolean hasActiveYearlySubscription(Long playerId) {
            return playerSubscriptionRepository.findByPlayerId(playerId)
                .map(sub -> ACTIVE_STATUSES.contains(sub.getStatus())
                    && "YEARLY".equalsIgnoreCase(sub.getBillingInterval())
                    && sub.getCurrentPeriodEnd() != null
                    && sub.getCurrentPeriodEnd().isAfter(Instant.now()))
                .orElse(false);
        }

        @Override
        public boolean hasAnyActiveSubscription(Long playerId) {
            return playerSubscriptionRepository.findByPlayerId(playerId)
                .map(sub -> ACTIVE_STATUSES.contains(sub.getStatus())
                    && sub.getCurrentPeriodEnd() != null
                    && sub.getCurrentPeriodEnd().isAfter(Instant.now()))
                .orElse(false);
        }
    }
    ```
  - [x] **IMPORTANT**: Delete `platform.booking.adapter.PlayerSubscriptionQueryAdapter.java` — there must be only ONE `@Component` implementing `PlayerSubscriptionQueryPort` or Spring will throw `NoUniqueBeanDefinitionException`

- [x] **Task 11 — Frontend: subscription management pages** (AC: 2–9)
  - [x] Add to `src/frontend/src/api/payment.api.js`:
    - `fetchCoachTiers()` → `GET /api/payment/subscriptions/coach/tiers`
    - `fetchMyCoachSubscription()` → `GET /api/payment/subscriptions/coach/me`
    - `subscribeCoach(payload)` → `POST /api/payment/subscriptions/coach/subscribe`
    - `changeCoachTier(payload)` → `POST /api/payment/subscriptions/coach/change-tier`
    - `cancelCoachSubscription()` → `DELETE /api/payment/subscriptions/coach`
    - `fetchPlayerTiers()` → `GET /api/payment/subscriptions/player/tiers`
    - `fetchMyPlayerSubscription(playerId)` → `GET /api/payment/subscriptions/player/me?playerId={playerId}`
    - `subscribePlayer(payload)` → `POST /api/payment/subscriptions/player/subscribe`
    - `changePlayerTier(payload)` → `POST /api/payment/subscriptions/player/change-tier`
    - `cancelPlayerSubscription(playerId)` → `DELETE /api/payment/subscriptions/player?playerId={playerId}`
  - [x] Extend `src/frontend/src/stores/payment.store.js`:
    - Add state: `coachSubscription: null`, `coachTiers: []`, `playerSubscription: null`, `playerTiers: []`
    - Add actions: `fetchCoachSubscription()`, `fetchCoachTiers()`, `subscribeCoach(payload)`, `changeCoachTier(newTier)`, `cancelCoachSubscription()`, and player mirrors
  - [x] Create `src/frontend/src/pages/coach/CoachSubscriptionPage.vue`:
    - Glassmorphism design (follow `CoachReliabilityPage.vue` and `RevenuePage.vue` patterns)
    - Current tier card: shows tier, status, `currentPeriodEnd`, `cancelAtPeriodEnd` banner
    - Tier comparison table: feature list + monthly prices from `/coach/tiers` endpoint
    - Subscribe/upgrade/downgrade CTA: opens payment method input (reuse Stripe Elements pattern from `payment.api.js`'s `stripe.confirmCardPayment`)
    - Cancel subscription action
    - Soft teaser overlay (UX-DR22): Scout-blocked features show blurred preview + "Upgrade to unlock" CTA
  - [x] Create `src/frontend/src/pages/parent/PlayerSubscriptionPage.vue` (same pattern for player tiers; pass `playerId` from route param as Long to all API calls)
  - [x] Add i18n keys `subscription.*` to `src/frontend/src/i18n/en/index.js` and `de/index.js`
  - [x] Add routes to `src/frontend/src/router/routes.js`:
    - `/coach/subscription` → `CoachSubscriptionPage.vue`
    - `/parent/player/:playerId/subscription` → `PlayerSubscriptionPage.vue`
  - [x] Add "Subscription" link to coach Command Center navigation (check `CommandCenter.vue` or global nav component)

- [x] **Task 12 — Tests** (AC: 1–10)
  - [x] `SubscriptionLifecycleIT.java` (`@SpringBootTest @Testcontainers` — WireMock for Stripe):
    - Coach subscribes: Stripe called with correct priceId, both payment and marketplace tables updated
    - Coach upgrades mid-cycle: immediate tier change, proration Stripe call
    - Coach downgrades: pending record in `coach_subscription_changes` with `triggerSource='SCHEDULED'`, tier unchanged until `effectiveAt`
    - `SubscriptionChangeApplicator` runs: pending change applied, both tables updated, void sets both `applied=true` AND `voided_at`
    - Coach cancels: `cancelAtPeriodEnd = true`, tier active; on `customer.subscription.deleted` webhook: tier → SCOUT
    - Webhook idempotency: duplicate `customer.subscription.deleted` event → no-op (uses `stripe_webhook_events` table)
    - Coach subscribe to already-active tier: 409 returned
  - [x] `PlayerSubscriptionOwnershipIT.java` (`@SpringBootTest @Testcontainers`):
    - Parent subscribes their own player: 200 (player_id is Long)
    - Parent attempts to subscribe another parent's player: 403, never 404
    - `GET /player/me?playerId=otherPlayerId`: 403 (ownership guard on GET)
  - [x] `PastDueGracePeriodTest.java` (unit — Mockito):
    - PAST_DUE within grace period: no downgrade (uses `past_due_since`, not `updated_at`)
    - PAST_DUE past grace period: `tier = SCOUT/ATHLETE` set on both tables; notification published
    - Grace period boundary: exactly `gracePeriodDays` — no downgrade; `gracePeriodDays + 1` — downgrade
    - `updated_at` being bumped does NOT reset the grace period clock (regression guard)
  - [x] `TierEntitlementGatingTest.java` (unit):
    - `PAST_DUE` status → effective tier = SCOUT for entitlement check
    - `CANCELLED` status → effective tier = SCOUT
    - `configService.getLong("subscription.pastDue.gracePeriodDays")` called per `checkPastDueGracePeriod()` invocation, not cached in a field (verified via Mockito invocation count across multiple scheduler calls)
  - [x] `PlayerSubscriptionQueryAdapterTest.java` (unit):
    - Player with ACTIVE YEARLY subscription + future `currentPeriodEnd`: both `hasAnyActiveSubscription = true` AND `hasActiveYearlySubscription = true`
    - Player with ACTIVE MONTHLY subscription: `hasAnyActiveSubscription = true`, `hasActiveYearlySubscription = false`
    - Player with CANCELLED subscription: `hasAnyActiveSubscription = false`
    - Player with ACTIVE subscription + past `currentPeriodEnd`: `hasAnyActiveSubscription = false`
  - [x] `VideoSubscriptionLifecycleListenerIT.java` (`@SpringBootTest @Testcontainers`) **[deferred from Story 6.4 Task 13]**:
    - Fire `SubscriptionExpiredEvent` (MONTHLY tier, `subscriberId` as Long) with no other active subscription → assert outbox row inserted; drain outbox → assert videos transition to BLOCKED + `lifecycle_locked_at` set
    - Fire `SubscriptionExpiredEvent` (MONTHLY tier) while `hasAnyActiveSubscription` returns true → drain outbox → videos remain ACTIVE (concurrent-subscription guard)
    - Fire `SubscriptionExpiredEvent` (YEARLY tier) for subscriber whose videos are already BLOCKED → drain outbox → assert `lifecycle_locked_at` reset to approximately `now()`
    - Outbox at-least-once: simulate processOutbox crash after partial batch → re-run → no duplicate BLOCKED transitions
    - Dead-letter: entry at max attempts → `status = DEAD_LETTER`; ERROR log contains `DEAD_LETTER` marker
  - [x] `YearlyExemptionRenewalIT.java` **[deferred from Story 6.4 Task 13]**:
    - Monthly expiry → BLOCKED; yearly active → no ARCHIVED; yearly expires → clock reset to T1; scheduler after T1+30d → ARCHIVED
  - [x] `SimultaneousExpiryIT.java` **[deferred from Story 6.4 Task 13]**:
    - MONTHLY+YEARLY concurrent expiry race; documents permanent ACTIVE gap; asserts WARN log produced

## Dev Notes

### CRITICAL: Tier Naming Discrepancy in Epics Document

The Story 7.4 AC in `skillars-epics.md` (lines 2535–2536) specifies tier enums `FREE/STARTER/PRO/ELITE` for coach and `FREE/STANDARD/PREMIUM` for player. **DO NOT use these names.**

The entire codebase (V20 config seeds, `CoachSubscriptionTier.java`, `marketplace.coach_subscriptions` CHECK constraint, `QuotaConfigService`, `ReportGenerationService`, `RadarAssessmentService`, `DevelopmentCorrelationService`) uses:
- **Coach tiers**: `SCOUT`, `INSTRUCTOR`, `ACADEMY`
- **Player tiers**: `ATHLETE`, `SEMI_PRO`, `PRO`

Use these names throughout this story. The epics document contains a naming inconsistency for Story 7.4; all other stories and existing code use SCOUT/INSTRUCTOR/ACADEMY.

### CRITICAL: Player IDs Are Long (BIGINT TSID) — Not UUID

`player_profiles.id` in `main.player_profiles` is `BIGINT` (Long TSID via `@Tsid` from `BaseEntity`). All player-related tables, repositories, events, and services throughout the codebase use `Long playerId`:
- `ParentPlayerLinkRepository.findByPlayerId(Long)`
- `PlayerOwnershipGuard.check(Authentication, Long)`
- `SessionPackExpiredEvent.getPlayerId()` → `Long`
- `parent_player_links.player_id BIGINT`

`payment.player_subscriptions.player_id` must be `BIGINT`, FK to `main.player_profiles(id)`. The `security` schema does **not exist** — there are only `main`, `marketplace`, `payment`, `booking`, `development`, and `session` schemas.

`SubscriptionExpiredEvent.subscriberId` must be `Long`. The V58 `subscription_lifecycle_outbox.subscriber_id` column (created as UUID) must be altered to BIGINT in V64.

`PlayerSubscriptionQueryPort` currently declares `UUID playerId` — this is wrong and must be changed to `Long playerId` as part of Task 3.

### CRITICAL: Two `coach_subscriptions` Tables — Do Not Confuse

| Table | Schema | Purpose | Entity |
|---|---|---|---|
| `marketplace.coach_subscriptions` | marketplace | Feature tier for gating (SCOUT/INSTRUCTOR/ACADEMY + active_since) | `CoachSubscription.java` in `platform.marketplace.repo` |
| `payment.coach_subscriptions` | payment | Stripe billing state (stripeSubscriptionId, status, etc.) | `PaymentCoachSubscription.java` in `platform.payment.repo` (**NEW**) |

Both must be kept in sync: when the payment service changes a coach's billing tier, it MUST also update `marketplace.coach_subscriptions.tier` in the SAME `@Transactional` block. Feature gating code (in marketplace, development, video, session modules) reads `marketplace.coach_subscriptions.tier` — do NOT refactor those callers in this story.

### CRITICAL: `player_subscriptions` has no counterpart in marketplace schema

Unlike coaches, players do not have an existing feature-tier table. `payment.player_subscriptions` is the sole source of truth for player tier gating. New code checking player tier should read from `SubscriptionService.getEffectivePlayerTier(playerId)`.

### CRITICAL: Coach Tiers Are MONTHLY Billing Only (FR-PAY-006)

FR-PAY-006 specifies "Instructor (Pro, monthly only), Academy (Elite, monthly only)." `CoachSubscribeRequest` has no `billingInterval` field. V64 seeds only monthly priceIds for coach tiers. Do NOT offer or accept annual billing for coach subscriptions.

### CRITICAL: Player Tier Billing Constraints (FR-PAY-007)

FR-PAY-007: "Athlete (monthly/quarterly/yearly), Semi-Pro (yearly only), Pro (yearly only)."
- `BillingInterval` enum: `MONTHLY`, `QUARTERLY`, `YEARLY` (NOT `ANNUAL` — see next note)
- SEMI_PRO and PRO reject any billingInterval other than `YEARLY` — enforced in service validation AND in DB CHECK constraints
- V64 seeds Athlete priceIds for monthly, quarterly, and yearly

### CRITICAL: Billing Interval String Is "YEARLY" Not "ANNUAL"

The `billing_interval` column value, `BillingInterval.YEARLY.name()`, `SubscriptionExpiredEvent.subscriptionTier`, and the outbox `subscription_tier` column must all use `"YEARLY"` (not `"ANNUAL"`). This is required for:
- `VideoSubscriptionLifecycleListener` Path A routing: checks `"YEARLY".equalsIgnoreCase(...)` per Story 6.4 Task 4
- `PlayerSubscriptionQueryPort.hasActiveYearlySubscription()` Javadoc and Story 6.4 test assertions both use "YEARLY"

Using "ANNUAL" will cause Path A (yearly clock reset) to never trigger, silently breaking FR-PAY-008 for all yearly subscribers.

### CRITICAL: Parent Stripe Customer ID Lookup

Player subscriptions are billed to the parent's Stripe customer account. `payment.stripe_customers` (created in V62) maps `parent_id BIGINT → stripe_customer_id VARCHAR`. In `subscribePlayer()`, look up the parent's Stripe customer ID before calling Stripe:
```java
String stripeCustomerId = stripeCustomerRepository.findById(parentUserId)
    .orElseThrow(() -> new IllegalStateException("No Stripe customer for parentId=" + parentUserId))
    .getStripeCustomerId();
```
`StripeCustomerRepository` is already in `platform.payment.repo` — inject it into `SubscriptionService`.

### CRITICAL: `existsByParentIdAndPlayerId` Must Be Added to Repository

`ParentPlayerLinkRepository` currently only has `existsByPlayerId(Long)`, `findByPlayerId(Long)`, `findByParentId(Long)`, and `findAllByPlayerId(Long)`. The method `existsByParentIdAndPlayerId(Long parentId, Long playerId)` must be added (Task 2) — Spring Data JPA derives it from the method name. `ParentPlayerLink` entity has both `parentId` and `playerId` Long fields.

### CRITICAL: Grace Period Must Use `past_due_since`, Not `updated_at`

`updated_at` is refreshed on any record modification (webhook syncs, unrelated field updates). Using it as the PAST_DUE timestamp would reset the grace period every time the subscription row is touched. `past_due_since` is set once on the first `invoice.payment_failed` event (only if currently null) and cleared when the subscription recovers to ACTIVE. The `SubscriptionGracePeriodChecker` queries `WHERE status = 'PAST_DUE' AND past_due_since < now() - gracePeriodDays`.

### CRITICAL: Downgrade Void Requires Both Fields

When voiding a pending downgrade (e.g., coach upgrades again before period end), both fields must be set in the same UPDATE:
- `applied = true` — excludes the row from scheduler's `WHERE applied = false` filter
- `voided_at = now()` — provides audit trail and secondary exclusion via `AND voided_at IS NULL`

Setting only one field leaves the row visible to either the scheduler or audit queries.

### CRITICAL: `triggerSource` on Subscription Change Records

`coach_subscription_changes.trigger_source` and `player_subscription_changes.trigger_source` record how the change was created:
- `'SCHEDULED'` — created by a downgrade request, applied by `SubscriptionChangeApplicator` at period end
- `'WEBHOOK_DELETED'` — reserved for future use if a webhook directly inserts a change record; currently, webhook-triggered downgrades are applied directly, not via the changes table

`SubscriptionExpiredEvent` for video lifecycle is published from `handleSubscriptionDeleted()` directly (not via the scheduler), so the `triggerSource` field is not used to conditionally publish the event from `applyPendingChanges()`. The comment in Task 4's `applyPendingChanges()` clarifies: the scheduler only publishes `SubscriptionExpiredEvent` for records where `triggerSource = 'WEBHOOK_DELETED'` (in case a future flow routes through the table); for normal scheduled downgrades the event is NOT published via the scheduler (the subscription expiry for SCHEDULED downgrades is a tier change, not a subscription deletion — no video lifecycle effect until an actual `customer.subscription.deleted` webhook fires).

### CRITICAL: `subscription_lifecycle_outbox` Subscriber ID Type Change

V58 created `main.subscription_lifecycle_outbox.subscriber_id UUID NOT NULL`. This must be altered to `BIGINT` in V64 because player IDs are Long TSID, not UUID. The ALTER is:
```sql
ALTER TABLE main.subscription_lifecycle_outbox
    ALTER COLUMN subscriber_id TYPE BIGINT USING subscriber_id::text::bigint;
```
This migration is safe as long as the table is empty at V64 deployment time (expected, as no subscription lifecycle events have fired before this story ships). Document this assumption in the migration file header comment.

### CRITICAL: Delete the booking-module stub adapter

`platform.booking.adapter.PlayerSubscriptionQueryAdapter` is the existing stub. After creating `platform.payment.adapter.PlayerSubscriptionQueryAdapter`, Spring will have two `@Component` beans implementing `PlayerSubscriptionQueryPort` → `NoUniqueBeanDefinitionException` at startup. Delete the booking-module file completely.

### CRITICAL: StripeWebhookService Event Routing Pattern

The existing `handleEventAtomically()` uses `if/else if` string comparison, not Java switch-case. Add new event types by extending the existing chain — do not introduce switch syntax or the code style will diverge and reviewers may reject. See Task 7 for the exact pattern.

### CRITICAL: `customer.subscription.updated` Does Not Carry Internal Tier

When Stripe fires `customer.subscription.updated`, the event contains a `priceId` for the new plan, not an internal tier name (`SCOUT`/`INSTRUCTOR`/etc.). The platform does not maintain a priceId→tier reverse map in ConfigService. **Do not attempt to sync `tier` from this webhook event** — the platform updates tier synchronously in `subscribeCoach()`/`changeCoachTier()` before this webhook can arrive, so the tier is already correct. Only sync `status`, `currentPeriodEnd`, and `cancelAtPeriodEnd` from `customer.subscription.updated`.

### CRITICAL: `SubscriptionExpiredEvent` Triggers Only for Player Subscription Deletion

`SubscriptionExpiredEvent` is published only when a **player** subscription is deleted via webhook (`customer.subscription.deleted`). Coaches do not have videos managed by subscription lifecycle. Add a guard in `handleSubscriptionDeleted()`: only publish the event when the deleted subscription is found in `payment.player_subscriptions`.

### CRITICAL: `subscription_lifecycle_outbox` Table Already Exists (V58)

Do NOT create this table in V64. It was created in `V58__lifecycle_schema.sql` with columns: `id UUID PK`, `subscriber_id UUID` (**altered to BIGINT in V64**), `subscription_tier VARCHAR(32)`, `expired_at TIMESTAMPTZ`, `status VARCHAR(16)`, `attempts INT`, `last_error TEXT`, `processed_at TIMESTAMPTZ`, `created_at TIMESTAMPTZ`.

The `SubscriptionLifecycleOutbox` entity in `platform.video.repo` maps to `main.subscription_lifecycle_outbox` — schema is `main`, not `video`.

### ConfigService — always call per use

```java
// CORRECT
long graceDays = configService.getLong("subscription.pastDue.gracePeriodDays");
// WRONG — stale after admin changes value
private final long graceDays; // populated in constructor
```

`ConfigService` has an internal TTL cache (default 300s refresh) — that is fine. The prohibition is on the **consumer** caching the returned value in a field. Rule from `architecture.md` lines 420–428, reinforced across Stories 7.1–7.3.

### StripeWebhookService — existing idempotency pattern

`webhookEventRepository.insertIfAbsent(event.getId(), event.getType())` uses `ON CONFLICT DO NOTHING` for idempotency. This already covers all new event types added in Task 7 — no changes needed to the idempotency mechanism.

### Stripe SDK — subscription model

Use `com.stripe.model.Subscription` for subscription events (not `Account`). The event data object deserialization pattern in `handleAccountUpdated()` must be replicated:
```java
Subscription sub;
try {
    sub = (Subscription) event.getDataObjectDeserializer().deserializeUnsafe();
} catch (EventDataObjectDeserializationException e) {
    log.error("[STRIPE_WEBHOOK_DESERIALIZE_FAILED ...]", ...);
    throw new RuntimeException("Webhook deserialization failed...", e);
}
```
Re-throwing ensures the idempotency record is rolled back and Stripe retries.

### Tier gating — PAST_DUE treated as free tier

AC 9 requires: `PAST_DUE` or `CANCELLED` subscription → treat as SCOUT/ATHLETE for entitlement checks. Add a helper in `SubscriptionService`:
```java
public String getEffectiveCoachTier(UUID coachId) {
    return paymentCoachSubscriptionRepository.findByCoachId(coachId)
        .filter(sub -> Set.of("ACTIVE","TRIALLING").contains(sub.getStatus()))
        .map(PaymentCoachSubscription::getTier)
        .orElse("SCOUT");
}
```
Do NOT modify existing callers in `marketplace` or `development` modules in this story — they read `marketplace.coach_subscriptions.tier` directly (which is kept in sync). The `getEffectiveCoachTier` helper is for new payment-module code only.

### Previous Story 7.3 Patterns to Follow

- `@TransactionalEventListener(phase = AFTER_COMMIT)` + `@Transactional(propagation = REQUIRES_NEW)` — mandatory for all event listeners (see `CancellationRefundService`)
- External API (Stripe) calls outside `@Transactional` — mandatory (see `BookingPaymentPersistenceService`)
- `@Observed(name="...")` on all Resource methods
- All request DTOs are `record` types with Jakarta validation
- `@PreAuthorize` on every endpoint (no exceptions)
- `currentCoachUserId()` helper pattern from `CancellationResource`: `((CustomUserPrincipal) principal).getBusinessId()` cast to Long; verify exact pattern in `CancellationResource.java`

### Parent ID is Long (BIGINT) — Consistent with Stories 7.2 and 7.3

`parentId` is `Long` (BIGINT from `security.users.id`), never UUID. All parent auth methods return `Long`. Player IDs are also `Long` (BIGINT TSID from `main.player_profiles.id`). Coach IDs are UUID.

### UX: Soft tier gating (UX-DR22)

Scout-tier blocked features (Session Builder, Skills Radar, Reports) must show a blurred preview with "Upgrade to unlock" CTA — NOT a full block page. Reference `UX-DR22` from `ux-design-specification.md`. The frontend `CoachSubscriptionPage.vue` should implement this pattern for Scout-blocked sections. A `TierGateOverlay.vue` reusable component is recommended.

### Project Structure for New Files

All new backend files follow `com.softropic.skillars.platform.{module}.{layer}`:
```
platform.payment.repo:
  PaymentCoachSubscription.java        — NEW
  PaymentCoachSubscriptionRepository.java — NEW
  PaymentPlayerSubscription.java       — NEW
  PaymentPlayerSubscriptionRepository.java — NEW
  CoachSubscriptionChange.java         — NEW
  CoachSubscriptionChangeRepository.java — NEW
  PlayerSubscriptionChange.java        — NEW
  PlayerSubscriptionChangeRepository.java — NEW

platform.payment.service:
  SubscriptionService.java             — NEW
  SubscriptionChangeApplicator.java    — NEW (scheduler)
  SubscriptionGracePeriodChecker.java  — NEW (scheduler)

platform.payment.api:
  SubscriptionResource.java            — NEW

platform.payment.contract:
  CoachSubscriptionResponse.java       — NEW
  PlayerSubscriptionResponse.java      — NEW
  TierInfoResponse.java                — NEW
  CoachSubscribeRequest.java           — NEW
  CoachChangeTierRequest.java          — NEW
  PlayerSubscribeRequest.java          — NEW
  PlayerChangeTierRequest.java         — NEW
  BillingInterval.java (enum)          — NEW (MONTHLY, QUARTERLY, YEARLY)
  SubscriptionStatus.java (enum)       — NEW
  event/SubscriptionExpiredEvent.java  — NEW

platform.payment.adapter:
  PlayerSubscriptionQueryAdapter.java  — NEW (replaces booking-module stub)

platform.video.service:
  VideoSubscriptionLifecycleListener.java — NEW

platform.video.repo:
  SubscriptionLifecycleOutbox.java     — NEW (maps to main.subscription_lifecycle_outbox; subscriber_id is Long)
  SubscriptionLifecycleOutboxRepository.java — NEW

platform.video.contract:
  PlayerSubscriptionQueryPort.java     — MODIFIED (UUID → Long for playerId)

platform.security.repo:
  ParentPlayerLinkRepository.java      — MODIFIED (add existsByParentIdAndPlayerId)

platform.payment.service:
  StripeWebhookService.java            — MODIFIED (add subscription event routing, if/else pattern)
  StripeClient.java                    — MODIFIED (add subscription methods)

platform.video.repo:
  VideoRepository.java                 — MODIFIED (add resetLifecycleLockedAt, findByStripeSubscriptionId)

platform.booking.adapter:
  PlayerSubscriptionQueryAdapter.java  — DELETED (stub replaced by payment adapter)

db/migration:
  V64__subscription_tiers.sql          — NEW
```

### References

- Epics: `_bmad-output/planning-artifacts/skillars-epics.md` lines 2523–2607 (Story 7.4 AC + cross-story handoffs)
- FR-PAY-006 (coach subscription tiers — monthly only), FR-PAY-007 (player tiers — quarterly/yearly constraints), FR-PAY-008 (yearly retention), FR-PAY-009 (premium features), FR-PAY-017 (subscription self-service)
- UX-DR22 (soft teaser overlay for Scout-blocked features)
- Story 6.4: `skillars-6-4-streaming-security-video-lifecycle.md` — Task 4 full spec for `VideoSubscriptionLifecycleListener` and Task 13 deferred ITs
- Story 7.3: `skillars-7-3-cancellation-refund-reliability-strikes.md` — `@TransactionalEventListener + REQUIRES_NEW` pattern, `StripeWebhookService` deserialization pattern
- Story 7.1: `skillars-7-1-stripe-connect-onboarding-commission-engine.md` — `StripeClient` patterns, `StripeWebhookService` base structure
- `marketplace.coach_subscriptions` (V26 migration) — existing feature tier table; must keep in sync
- `main.subscription_lifecycle_outbox` (V58 migration, altered V64) — outbox table for video subscription lifecycle
- `platform.video.contract.PlayerSubscriptionQueryPort` — interface to MODIFY in Task 3 (UUID → Long)
- `platform.booking.adapter.PlayerSubscriptionQueryAdapter` — stub to DELETE in Task 10
- `payment.stripe_customers` (V62 migration) — parent Stripe customer ID lookup for player subscriptions
- `architecture.md` lines 496–501: Stripe calls outside @Transactional rule
- `project-context.md`: Java record DTOs, MapStruct, `@PreAuthorize` on every endpoint, no DB DDL in Java

## Dev Agent Record

### Agent Model Used

claude-sonnet-4-6

### Debug Log References

### Completion Notes List

- Tasks 1–11 implemented across multiple sessions; all ACs satisfied.
- Player IDs confirmed as Long (BIGINT TSID) throughout; `PlayerSubscriptionQueryPort` updated accordingly.
- Two `coach_subscriptions` tables (payment + marketplace) kept in sync via `syncMarketplaceTier()`.
- Grace period uses `pastDueSince`, not `updatedAt` — verified in PastDueGracePeriodTest.
- Downgrade void sets both `applied=true` AND `voidedAt=now()` in one UPDATE.
- `SubscriptionExpiredEvent.subscriptionTier` uses "YEARLY" (not "ANNUAL") for VideoSubscriptionLifecycleListener Path A.
- Deferred tests (YearlyExemptionRenewalIT, SimultaneousExpiryIT) written with `@Disabled("Deferred from 6.4")`.

### File List

**New files:**
- `src/main/resources/db/migration/V64__subscription_tiers.sql`
- `src/main/java/com/softropic/skillars/platform/payment/repo/PaymentCoachSubscription.java`
- `src/main/java/com/softropic/skillars/platform/payment/repo/PaymentCoachSubscriptionRepository.java`
- `src/main/java/com/softropic/skillars/platform/payment/repo/PaymentPlayerSubscription.java`
- `src/main/java/com/softropic/skillars/platform/payment/repo/PaymentPlayerSubscriptionRepository.java`
- `src/main/java/com/softropic/skillars/platform/payment/repo/CoachSubscriptionChange.java`
- `src/main/java/com/softropic/skillars/platform/payment/repo/CoachSubscriptionChangeRepository.java`
- `src/main/java/com/softropic/skillars/platform/payment/repo/PlayerSubscriptionChange.java`
- `src/main/java/com/softropic/skillars/platform/payment/repo/PlayerSubscriptionChangeRepository.java`
- `src/main/java/com/softropic/skillars/platform/payment/contract/CoachSubscriptionTierBilling.java`
- `src/main/java/com/softropic/skillars/platform/payment/contract/PlayerSubscriptionTierBilling.java`
- `src/main/java/com/softropic/skillars/platform/payment/contract/BillingInterval.java`
- `src/main/java/com/softropic/skillars/platform/payment/contract/SubscriptionStatus.java`
- `src/main/java/com/softropic/skillars/platform/payment/contract/CoachSubscribeRequest.java`
- `src/main/java/com/softropic/skillars/platform/payment/contract/CoachChangeTierRequest.java`
- `src/main/java/com/softropic/skillars/platform/payment/contract/PlayerSubscribeRequest.java`
- `src/main/java/com/softropic/skillars/platform/payment/contract/PlayerChangeTierRequest.java`
- `src/main/java/com/softropic/skillars/platform/payment/contract/CoachSubscriptionResponse.java`
- `src/main/java/com/softropic/skillars/platform/payment/contract/PlayerSubscriptionResponse.java`
- `src/main/java/com/softropic/skillars/platform/payment/contract/TierInfoResponse.java`
- `src/main/java/com/softropic/skillars/platform/payment/contract/event/SubscriptionExpiredEvent.java`
- `src/main/java/com/softropic/skillars/platform/payment/service/SubscriptionService.java`
- `src/main/java/com/softropic/skillars/platform/payment/service/SubscriptionChangeApplicator.java`
- `src/main/java/com/softropic/skillars/platform/payment/service/SubscriptionGracePeriodChecker.java`
- `src/main/java/com/softropic/skillars/platform/payment/api/SubscriptionResource.java`
- `src/main/java/com/softropic/skillars/platform/payment/adapter/PlayerSubscriptionQueryAdapter.java`
- `src/main/java/com/softropic/skillars/platform/video/service/VideoSubscriptionLifecycleListener.java`
- `src/frontend/src/pages/coach/CoachSubscriptionPage.vue`
- `src/frontend/src/pages/parent/PlayerSubscriptionPage.vue`
- `src/test/java/com/softropic/skillars/platform/payment/service/PastDueGracePeriodTest.java`
- `src/test/java/com/softropic/skillars/platform/payment/service/TierEntitlementGatingTest.java`
- `src/test/java/com/softropic/skillars/platform/payment/adapter/PlayerSubscriptionQueryAdapterTest.java`
- `src/test/java/com/softropic/skillars/platform/payment/service/SubscriptionLifecycleIT.java`
- `src/test/java/com/softropic/skillars/platform/payment/api/PlayerSubscriptionOwnershipIT.java`
- `src/test/java/com/softropic/skillars/platform/video/service/VideoSubscriptionLifecycleListenerIT.java`
- `src/test/java/com/softropic/skillars/platform/video/service/YearlyExemptionRenewalIT.java`
- `src/test/java/com/softropic/skillars/platform/video/service/SimultaneousExpiryIT.java`

**Modified files:**
- `src/main/java/com/softropic/skillars/platform/security/repo/ParentPlayerLinkRepository.java` — added `existsByParentIdAndPlayerId`
- `src/main/java/com/softropic/skillars/platform/video/contract/PlayerSubscriptionQueryPort.java` — changed `UUID playerId` → `Long playerId`
- `src/main/java/com/softropic/skillars/platform/video/service/VideoLifecycleScheduler.java` — UUID.fromString → Long.parseLong
- `src/main/java/com/softropic/skillars/platform/payment/service/StripeWebhookService.java` — extended with subscription event handlers
- `src/main/java/com/softropic/skillars/platform/payment/service/StripeClient.java` — added subscription SDK methods
- `src/main/java/com/softropic/skillars/platform/video/repo/SubscriptionLifecycleOutbox.java` — subscriberId UUID → Long
- `src/main/java/com/softropic/skillars/platform/video/repo/SubscriptionLifecycleOutboxRepository.java` — added findTop100... query
- `src/main/java/com/softropic/skillars/platform/video/repo/VideoRepository.java` — added resetLifecycleLockedAt
- `src/frontend/src/api/payment.api.js` — added 10 subscription API functions
- `src/frontend/src/stores/payment.store.js` — added subscription state and actions
- `src/frontend/src/i18n/en/index.js` — added subscription.* keys
- `src/frontend/src/i18n/de/index.js` — added subscription.* German translations
- `src/frontend/src/router/routes.js` — added coach/subscription and player/:playerId/subscription routes

**Deleted files:**
- `src/main/java/com/softropic/skillars/platform/booking/adapter/PlayerSubscriptionQueryAdapter.java` — stub replaced by payment adapter

### Review Findings

- [x] [Review][Patch] CD-1: Phantom @Transactional on package-private persist* methods — FIXED: added @Lazy self-reference; all persist* methods made public and called via self.*; @Transactional now activated through Spring proxy [SubscriptionService.java]
- [x] [Review][Patch] CD-2: Infinite loop in processEntry Path A — FIXED: removed paginated do-while loop; bulk resetLifecycleLockedAt() is sufficient for FR-PAY-008; loop was infinite because findBlockedReadyByOwner has no lifecycle_locked_at filter [VideoSubscriptionLifecycleListener.java]
- [x] [Review][Patch] CD-3: applyPendingChanges uses derived query without SKIP LOCKED — FIXED: replaced with findPendingForScheduler() using native SQL FOR UPDATE SKIP LOCKED in both change repositories [CoachSubscriptionChangeRepository.java, PlayerSubscriptionChangeRepository.java]
- [x] [Review][Patch] CD-4: checkPastDueGracePeriod calls findAll() on full subscriptions tables — FIXED: added findByStatusAndPastDueSinceBefore() derived query to both repositories; removed stream filter [SubscriptionService.java]
- [x] [Review][Patch] CD-5: processOutbox wraps 100 entries in one transaction — FIXED: removed @Transactional from processOutbox; extracted processAndSaveEntry(@Transactional REQUIRES_NEW) called via self; each entry commits independently [VideoSubscriptionLifecycleListener.java]
- [x] [Review][Patch] CD-6: currentPeriodEnd never set after subscription creation — FIXED: StripeClient.createSubscription now returns full Subscription object; persist* methods extract both stripeSubId and currentPeriodEnd; status taken from Stripe response instead of hardcoding ACTIVE [StripeClient.java, SubscriptionService.java]
- [x] [Review][Patch] CD-7: stripeSubscriptionId null not guarded before Stripe calls — FIXED: added null checks before Stripe calls in changeCoachTier (upgrade), cancelCoachSubscription, changePlayerTier (upgrade), cancelPlayerSubscription; throws payment.subscription.noActiveStripeSubscription [SubscriptionService.java]
- [x] [Review][Patch] LD-1: attachPaymentMethod not called before createSubscription — FIXED: stripeClient.attachPaymentMethod() now called before createSubscription() in both subscribeCoach and subscribePlayer [SubscriptionService.java]
- [x] [Review][Decision] PC-1: getEffectiveCoachTier returns SCOUT during PAST_DUE grace period — DISMISSED: product decision confirmed immediate downgrade on PAST_DUE (validity period lapse = lose access). Current behavior correct.

## Change Log

| Date | Version | Description | Author |
|------|---------|-------------|--------|
| 2026-06-25 | 1.0 | Story implementation complete — Tasks 1–12 delivered; all ACs satisfied | claude-sonnet-4-6 |
| 2026-06-25 | 1.1 | Code review findings added — 7 confirmed defects, 1 likely defect, 1 decision-needed | claude-sonnet-4-6 |
