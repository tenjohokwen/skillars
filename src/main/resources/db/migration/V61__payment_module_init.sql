-- Payment module: Stripe Connect onboarding + commission engine
-- Story 7.1: coach_stripe_accounts, stripe_webhook_events, platform.commission.rate seed

CREATE SCHEMA IF NOT EXISTS payment;

CREATE TABLE payment.coach_stripe_accounts (
    coach_id           UUID         NOT NULL,
    version            INTEGER      NOT NULL DEFAULT 0,
    stripe_account_id  VARCHAR      NOT NULL,
    onboarding_status  VARCHAR(16)  NOT NULL DEFAULT 'PENDING',
    charges_enabled    BOOLEAN      NOT NULL DEFAULT false,
    payouts_enabled    BOOLEAN      NOT NULL DEFAULT false,
    updated_at         TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT pk_coach_stripe_accounts PRIMARY KEY (coach_id),
    CONSTRAINT fk_csa_coach FOREIGN KEY (coach_id) REFERENCES marketplace.coach_profiles(id),
    CONSTRAINT chk_csa_onboarding_status CHECK (onboarding_status IN ('PENDING', 'COMPLETE', 'RESTRICTED'))
);

CREATE UNIQUE INDEX idx_csa_stripe_account_id ON payment.coach_stripe_accounts(stripe_account_id);

CREATE TABLE payment.stripe_webhook_events (
    event_id      VARCHAR      NOT NULL,
    event_type    VARCHAR      NOT NULL,
    processed_at  TIMESTAMPTZ  NOT NULL,
    CONSTRAINT pk_stripe_webhook_events PRIMARY KEY (event_id)
);

-- Seed commission rate config (8% = 0.08)
-- ON CONFLICT DO NOTHING ensures idempotent re-runs (pattern from V57)
INSERT INTO main.platform_config (id, key, value, value_type, description)
VALUES (162, 'platform.commission.rate', '0.08', 'STRING',
        'Platform commission rate deducted from each session payment via Stripe application_fee_amount. Default 8% (0.08).')
ON CONFLICT (key) DO NOTHING;
