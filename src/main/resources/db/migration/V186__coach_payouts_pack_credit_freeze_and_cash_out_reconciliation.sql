-- feat/coach-payouts-pack-and-credit (DECISIONS.md item 3 + follow-ups).
--
-- 1. session_pack_purchases: stamp what a pack's coach payout is computed from, at PURCHASE time —
--    the commission rate in force (same "lock it at capture" rule as booking_payments.commission_rate)
--    and the number of sessions sold (session_pack_tiers.session_count is coach-editable after the
--    sale). payout_sessions_allocated / payout_gross_allocated are the running "paid so far" of the pack,
--    updated under the pack row lock by CoachPayoutOutboxSupport so the last session takes the
--    remainder and the sum of per-session gross shares can never exceed amount_charged.
--    commission_rate stays NULL for pre-existing rows: the payout falls back to the live rate, exactly
--    as a pre-V133 booking_payments row does. session_count is backfilled from the tier's CURRENT
--    session_count (no history exists — the V181 reasoning), never below remaining_sessions.
-- 2. coach_payouts: session_pack_purchase_id (which pack funded a pack payout — audit/reporting) and a
--    widened status CHECK with FROZEN (payout held while the coach is under a CSAM safety hold).
-- 3. coach_payout_freezes: one row per coach whose payouts are frozen by a safety hold; lifted_at set
--    on admin reinstatement.
-- 4. cash_out_reconciliations: a durable record per credit cash-out, written atomically with the wallet
--    debit, so a refund whose Stripe outcome is unknown (or a crash between the debit and the refund) is
--    never just an error log.
-- 5. admin_alerts: CASH_OUT_UNRESOLVED type / CASH_OUT reference type for (4). Both CHECKs are rebuilt as the
--    UNION with everything V185 (and earlier) allows — V185 added SAFETY_HOLD_BOOKING.
--
-- Additive only (rule 1). CHECK widenings and their first writes ship together: no production deploy
-- of this application has happened yet (the V149/V152/V153/V162 owner decision), and the status columns
-- are plain strings that the previous release treats as "not payable" (CoachPayoutStatus.isPayable is
-- PENDING_RELEASE only), so an old instance meeting a FROZEN row no-ops instead of paying.
SET LOCAL lock_timeout = '5s';

ALTER TABLE payment.session_pack_purchases
    ADD COLUMN IF NOT EXISTS commission_rate numeric(5,4),
    ADD COLUMN IF NOT EXISTS session_count integer,
    ADD COLUMN IF NOT EXISTS payout_sessions_allocated integer NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS payout_gross_allocated numeric(10,2) NOT NULL DEFAULT 0;

-- Single-transaction backfill, not the executeInTransaction=false sidecar: session_pack_purchases is a
-- small table (one row per pack sold) and this predicate matches every row only on this migration's one
-- real execution — the same reasoning as V181.
UPDATE payment.session_pack_purchases p
SET session_count = GREATEST(t.session_count, p.remaining_sessions)
FROM payment.session_pack_tiers t
WHERE t.pack_tier_id = p.pack_tier_id
  AND p.session_count IS NULL;

ALTER TABLE payment.coach_payouts
    ADD COLUMN IF NOT EXISTS session_pack_purchase_id uuid;

ALTER TABLE payment.coach_payouts
    DROP CONSTRAINT IF EXISTS chk_coach_payouts_status,
    -- migration-lint: allow-validating-constraint coach_payouts holds one row per completed booking and
    -- this application has no production deployment yet, so the validating CHECK swap is a fast scan of
    -- a small table, not a rolling-deploy lock-exposure risk.
    ADD CONSTRAINT chk_coach_payouts_status CHECK (((status)::text = ANY ((ARRAY[
        'PENDING_RELEASE'::character varying,
        'RELEASED'::character varying,
        'REVERSED'::character varying,
        'REVERSAL_FAILED'::character varying,
        'CANCELLED'::character varying,
        'HOLD'::character varying,
        'FAILED_PERMANENT'::character varying,
        'FROZEN'::character varying
    ])::text[])));

-- New, empty tables: constraints inline, nothing to validate (V148/V175 precedent).
CREATE TABLE IF NOT EXISTS payment.coach_payout_freezes (
    coach_id   uuid         NOT NULL PRIMARY KEY,
    reason     varchar(200) NOT NULL,
    frozen_at  timestamptz  NOT NULL,
    lifted_at  timestamptz,
    lifted_by  bigint
);

CREATE TABLE IF NOT EXISTS payment.cash_out_reconciliations (
    cash_out_id       uuid          NOT NULL PRIMARY KEY,
    parent_id         bigint        NOT NULL,
    payment_intent_id varchar(255)  NOT NULL,
    net_amount        numeric(10,2) NOT NULL,
    fee_amount        numeric(10,2) NOT NULL,
    status            varchar(20)   NOT NULL,
    attempts          integer       NOT NULL DEFAULT 0,
    last_error        varchar(1000),
    created_at        timestamptz   NOT NULL,
    updated_at        timestamptz   NOT NULL,
    resolved_at       timestamptz,
    CONSTRAINT chk_cash_out_reconciliations_status CHECK (status IN
        ('PENDING', 'COMPLETED', 'UNKNOWN', 'REVERSED', 'RESOLVED'))
);

-- The re-check job and the admin finance overview read only the open rows.
-- migration-lint: allow-blocking-index the table is created empty just above, so the build is instant.
CREATE INDEX IF NOT EXISTS idx_cash_out_reconciliations_open
    ON payment.cash_out_reconciliations (created_at)
    WHERE status IN ('PENDING', 'UNKNOWN');

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
        'CASH_OUT_UNRESOLVED'::character varying
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
        'CASH_OUT'::character varying
    ])::text[])));
