-- V64: Coach & Player Subscription Tiers
-- Assumes main.subscription_lifecycle_outbox.subscriber_id is UUID (created in V58).
-- That column is safe to ALTER here because the table is expected to be empty at V64 time
-- (no subscription lifecycle events have fired before this story ships).
-- If deploying to an environment with existing rows, TRUNCATE first or handle manually.

-- payment schema already exists from V61
CREATE SCHEMA IF NOT EXISTS payment;

-- Coach billing subscription state (Stripe + tier sync)
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

-- Player billing subscription state
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

-- Pending coach tier changes (scheduled downgrades)
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

-- Pending player tier changes (scheduled downgrades)
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

-- Alter subscription_lifecycle_outbox.subscriber_id from UUID to BIGINT
-- (V58 created it as UUID before player IDs were confirmed as Long TSID)
ALTER TABLE main.subscription_lifecycle_outbox
    ALTER COLUMN subscriber_id TYPE BIGINT USING subscriber_id::text::bigint;

-- Config seeds — next IDs after 504 (last used in V63)
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
