-- Story 7.2: Session payment lifecycle, credit wallet, session pack tiers & purchases

-- Parent credit ledger (append-only: no UPDATE, no DELETE)
CREATE TABLE payment.parent_credit_ledger (
    tx_id         UUID          NOT NULL DEFAULT gen_random_uuid(),
    parent_id     BIGINT        NOT NULL,
    amount        NUMERIC(10,2) NOT NULL,
    type          VARCHAR(32)   NOT NULL,
    reference_id  UUID,
    description   VARCHAR(500),
    created_at    TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT pk_parent_credit_ledger  PRIMARY KEY (tx_id),
    CONSTRAINT chk_ledger_type CHECK (type IN (
        'BOOKING_DEDUCTION',
        'BOOKING_DEDUCTION_REVERSAL',
        'BOOKING_REFUND',
        'CASH_OUT_DEBIT',
        'STRIPE_FEE_DEBIT',
        'CASH_OUT_REVERSAL'
    )),
    CONSTRAINT chk_ledger_amount_sign CHECK (
        CASE WHEN type IN ('BOOKING_DEDUCTION', 'CASH_OUT_DEBIT', 'STRIPE_FEE_DEBIT')
             THEN amount < 0
             ELSE amount > 0
        END
    )
);

-- Stripe customer mapping (parent_id is Long TSID, not UUID)
CREATE TABLE payment.stripe_customers (
    parent_id                  BIGINT  NOT NULL,
    stripe_customer_id         VARCHAR NOT NULL,
    stripe_payment_method_id   VARCHAR,
    last_payment_intent_id     VARCHAR,
    created_at                 TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT pk_stripe_customers PRIMARY KEY (parent_id)
);

-- Session pack tiers defined by coaches
CREATE TABLE payment.session_pack_tiers (
    pack_tier_id      UUID          NOT NULL DEFAULT gen_random_uuid(),
    coach_id          UUID          NOT NULL,
    label             VARCHAR(200)  NOT NULL,
    session_count     INT           NOT NULL,
    total_price       NUMERIC(10,2) NOT NULL,
    price_per_session NUMERIC(10,2) NOT NULL,
    is_active         BOOLEAN       NOT NULL DEFAULT true,
    version           INT           NOT NULL DEFAULT 0,
    created_at        TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT pk_session_pack_tiers PRIMARY KEY (pack_tier_id),
    CONSTRAINT fk_spt_coach FOREIGN KEY (coach_id) REFERENCES marketplace.coach_profiles(id),
    CONSTRAINT chk_spt_session_count_positive CHECK (session_count > 0),
    CONSTRAINT chk_spt_total_price_positive CHECK (total_price > 0),
    CONSTRAINT chk_spt_price_per_session_positive CHECK (price_per_session > 0)
);

-- Enforce at most one active tier per coach
CREATE UNIQUE INDEX idx_spt_one_active_per_coach ON payment.session_pack_tiers(coach_id) WHERE is_active = true;

-- Session pack purchases by parents
CREATE TABLE payment.session_pack_purchases (
    purchase_id               UUID          NOT NULL DEFAULT gen_random_uuid(),
    parent_id                 BIGINT        NOT NULL,
    coach_id                  UUID          NOT NULL,
    pack_tier_id              UUID          NOT NULL,
    price_per_session         NUMERIC(10,2) NOT NULL,
    remaining_sessions        INT           NOT NULL,
    CONSTRAINT chk_spp_remaining_non_negative CHECK (remaining_sessions >= 0),
    expires_at                TIMESTAMPTZ   NOT NULL DEFAULT now() + INTERVAL '60 days',
    extended_at               TIMESTAMPTZ,
    stripe_payment_intent_id  VARCHAR,
    version                   INT           NOT NULL DEFAULT 0,
    created_at                TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT pk_session_pack_purchases PRIMARY KEY (purchase_id),
    CONSTRAINT fk_spp_coach FOREIGN KEY (coach_id) REFERENCES marketplace.coach_profiles(id),
    CONSTRAINT fk_spp_tier FOREIGN KEY (pack_tier_id) REFERENCES payment.session_pack_tiers(pack_tier_id)
);

-- Payment record per booking (no FK to booking.bookings — see Dev Notes on cross-schema FK risk)
CREATE TABLE payment.booking_payments (
    booking_id               UUID          NOT NULL,
    batch_payment_intent_id  UUID,
    stripe_payment_intent_id VARCHAR,
    credit_debited           NUMERIC(10,2) NOT NULL DEFAULT 0,
    stripe_charged           NUMERIC(10,2) NOT NULL DEFAULT 0,
    status                   VARCHAR(16)   NOT NULL,
    captured_at              TIMESTAMPTZ,
    frozen_at                TIMESTAMPTZ,
    CONSTRAINT pk_booking_payments PRIMARY KEY (booking_id),
    CONSTRAINT chk_bp_status CHECK (status IN ('CAPTURED', 'CHARGE_FAILED', 'FROZEN'))
);

-- View: real-time parent credit balance
CREATE OR REPLACE VIEW payment.parent_credit_balance AS
    SELECT parent_id, COALESCE(SUM(amount), 0) AS balance
    FROM payment.parent_credit_ledger
    GROUP BY parent_id;

-- Link bookings to payment session pack purchases (nullable)
ALTER TABLE booking.bookings
    ADD COLUMN session_pack_purchase_id UUID
    REFERENCES payment.session_pack_purchases(purchase_id);

-- Index for balance aggregation queries
CREATE INDEX idx_pcl_parent_id ON payment.parent_credit_ledger(parent_id);

-- Seed Stripe fee config
INSERT INTO main.platform_config (id, key, value, value_type, description, updated_at)
VALUES
    (501, 'payment.stripe.feeRate',  '0.025', 'STRING', 'Stripe processing fee rate for credit cash-outs. Default 2.5% (0.025).', NOW()),
    (502, 'payment.stripe.feeFixed', '0.25',  'STRING', 'Stripe fixed fee per credit cash-out transaction (EUR). Default €0.25.',  NOW())
ON CONFLICT (key) DO NOTHING;
