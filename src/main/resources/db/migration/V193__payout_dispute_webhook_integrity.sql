-- fix/payment-payouts-disputes-webhooks: payout / dispute / webhook integrity.
--
-- 1. coach_payouts:
--    * reversed_amount — what has been clawed back from a RELEASED transfer so far. A partial reversal
--      keeps the row RELEASED (the coach still earned net - reversed_amount); only a reversal of the
--      whole net moves it to REVERSED. Reporting counts RELEASED rows for (net - reversed_amount).
--    * dispute_frozen / chargeback_frozen — per-payout freeze reasons, independent of the per-coach
--      safety-hold freeze (coach_payout_freezes, V186). A FROZEN payout is released again only when
--      none of the three reasons stands, so lifting one never clobbers another.
-- 2. coach_stripe_accounts:
--    * transfers_capability — the Connect account's `transfers` capability as last reported by
--      account.updated (active / inactive / pending); NULL until the first event after this release.
--    * last_event_created_at — the `created` of the last applied account.updated, so an older event
--      delivered late never overwrites a newer snapshot.
-- 3. charge_disputes: one row per Stripe card dispute (charge.dispute.created / closed) against a
--    platform PaymentIntent, so a payout created after the dispute opened (session completed later) is
--    frozen too, and a re-delivered close is applied once.
-- 4. admin_alerts: CHARGEBACK / EXTERNAL_REFUND / PAYOUT_HELD / PAYOUT_REVERSAL_FAILED types and a
--    PAYMENT_INTENT reference type. Both CHECKs are rebuilt as the UNION with everything V186 allows.
--
-- Additive only. Defaulted NOT NULL columns are a catalog-only change (PostgreSQL 11+, constant default).
-- CHECK widenings and their first writes ship together — no production deployment yet (the
-- V149/V152/V153/V162/V186 owner decision).
SET LOCAL lock_timeout = '5s';

ALTER TABLE payment.coach_payouts
    ADD COLUMN IF NOT EXISTS reversed_amount numeric(10,2) NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS dispute_frozen boolean NOT NULL DEFAULT false,
    ADD COLUMN IF NOT EXISTS chargeback_frozen boolean NOT NULL DEFAULT false;

ALTER TABLE payment.coach_stripe_accounts
    ADD COLUMN IF NOT EXISTS transfers_capability varchar(20),
    ADD COLUMN IF NOT EXISTS last_event_created_at timestamptz;

-- New, empty table: constraints inline, nothing to validate (V148/V175/V186 precedent).
CREATE TABLE IF NOT EXISTS payment.charge_disputes (
    stripe_dispute_id varchar(255)  NOT NULL PRIMARY KEY,
    payment_intent_id varchar(255)  NOT NULL,
    charge_id         varchar(255),
    amount            numeric(10,2) NOT NULL,
    currency          varchar(3),
    status            varchar(20)   NOT NULL,
    stripe_status     varchar(40),
    created_at        timestamptz   NOT NULL,
    closed_at         timestamptz,
    CONSTRAINT chk_charge_disputes_status CHECK (status IN ('OPEN', 'WON', 'LOST'))
);

-- migration-lint: allow-blocking-index the table is created empty just above, so the build is instant.
CREATE INDEX IF NOT EXISTS idx_charge_disputes_payment_intent
    ON payment.charge_disputes (payment_intent_id);

ALTER TABLE admin.admin_alerts
    DROP CONSTRAINT IF EXISTS admin_alerts_type_check,
    -- migration-lint: allow-validating-constraint small, internal admin-queue table; no bulk-insert
    -- path and no row count anywhere near large-table territory, so a validating CHECK swap is a fast
    -- full-table scan, not a rolling-deploy lock-exposure risk.
    ADD CONSTRAINT admin_alerts_type_check CHECK (((type)::text = ANY ((ARRAY[
        'MESSAGE_REPORT'::character varying,
        'CONVERSATION_REPORT'::character varying,
        'REVIEW_FLAG'::character varying,
        'STRIKE_THRESHOLD'::character varying,
        'DISPUTE_RAISED'::character varying,
        'MODERATION_UNRESOLVED'::character varying,
        'GDPR_ERASURE_DEADLINE'::character varying,
        'SUBSCRIPTION_ORPHANED'::character varying,
        'SAFETY_HOLD_BOOKING'::character varying,
        'CASH_OUT_UNRESOLVED'::character varying,
        'CHARGEBACK'::character varying,
        'EXTERNAL_REFUND'::character varying,
        'PAYOUT_HELD'::character varying,
        'PAYOUT_REVERSAL_FAILED'::character varying
    ])::text[])));

ALTER TABLE admin.admin_alerts
    DROP CONSTRAINT IF EXISTS admin_alerts_reference_type_check,
    -- migration-lint: allow-validating-constraint small, internal admin-queue table (see above).
    ADD CONSTRAINT admin_alerts_reference_type_check CHECK (((reference_type)::text = ANY ((ARRAY[
        'MESSAGE'::character varying,
        'CONVERSATION'::character varying,
        'REVIEW'::character varying,
        'COACH'::character varying,
        'BOOKING'::character varying,
        'GDPR_REQUEST'::character varying,
        'CASH_OUT'::character varying,
        'PAYMENT_INTENT'::character varying
    ])::text[])));
