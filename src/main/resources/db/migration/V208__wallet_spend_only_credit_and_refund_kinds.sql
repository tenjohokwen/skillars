-- fix/payment-wallet-dispute-integrity: spend-only wallet credit, separate refund kinds per booking, and
-- the booking_payments PaymentIntent index.
--
-- 1. payment.parent_credit_ledger.spend_only_amount — the part of a row's amount in the SPEND-ONLY pool
--    (same sign as amount, |spend_only_amount| <= |amount|; 0 = all cashable). Owner decision 2026-10-29:
--    a parent's >24h cancel of a booking drawn on an EXPIRED pack is refunded at the locked price as
--    credit usable for bookings but never cashed out. The wallet's spend-only balance is
--    SUM(spend_only_amount) (clamped to [0, total]); the cashable balance is total - spend-only. Spends
--    (BOOKING_DEDUCTION) consume the spend-only pool first; cash-outs take only cashable credit. The
--    ledger stays append-only (trg_ledger_no_update): every row records its own split when written.
--    NOT NULL DEFAULT 0 is catalog-only on PG 11+ (no table rewrite). NO BACKFILL: the ledger cannot be
--    updated, and the only rows that would qualify are expired-pack parent-cancel refunds written since
--    #355 (2026-10-28) on dev/uat — prod is not live. They stay cashable.
--    chk_ledger_spend_only: the sign / magnitude rule above.
-- 2. Two new positive types, so one booking's unrelated credits never collide on uq_pcl_booking_refund
--    (one BOOKING_REFUND per booking — the cancellation / no-show / decline refund):
--    * DISPUTE_CREDIT — an admin dispute resolution's credit (FULL / PARTIAL / COACH_WARNING),
--      reference_id = the booking id, one per resolved dispute (the dispute row's status + @Version make a
--      resolution write once; the per-booking total is capped by DisputeService at what was paid minus what
--      was already refunded, so no unique index).
--    * BATCH_SHARE_REFUND — a batch booking's charged share credited back because the booking was settled
--      elsewhere (BookingPaymentPersistenceService.refundBatchShareToCredit); reference_id = the booking id,
--      at most one per booking (uq_pcl_batch_share_refund).
--    chk_ledger_type is rebuilt from V199 plus the two; chk_ledger_amount_sign is unchanged (both are
--    positive, i.e. not in its negative list).
-- 3. idx_bp_stripe_payment_intent on payment.booking_payments(stripe_payment_intent_id): the cash-out
--    dispute check (ChargeDisputeRepository.existsOpenDisputeForPayer, run under the payer's wallet lock)
--    and the chargeback credit trace filter on it; it used to be a sequential scan.
-- 4. admin.admin_alerts.admin_alerts_type_check widened with BOOKING_ACTION_REQUIRED (23 chars, fits
--    varchar(25)): a booking an automatic path could not finish and an operator must — an admin cancel whose
--    refund amount is unknown (no 0.00 refund is enqueued any more), or a booking a coach suspension could
--    not cancel (row busy / capture in flight) after its retry. Rebuilt as the V193 list plus the new type.
--    MERGE NOTE: any other branch widening this CHECK must re-union the list.
--
-- Migration conventions (docs/deployment/migration-conventions.md), mirroring V198/V199:
--  - Additive only.
--  - Validating CHECK swaps / non-concurrent indexes: prod is not live (dev/uat data only), so each is a
--    fast scan, not a rolling-deploy lock risk (markers on the clauses below).
--  - Rule 5: the widenings and their first writes ship together (the V149/V152/V162/V186 owner decision).
SET LOCAL lock_timeout = '5s';

ALTER TABLE payment.parent_credit_ledger
    ADD COLUMN IF NOT EXISTS spend_only_amount numeric(10,2) NOT NULL DEFAULT 0;

ALTER TABLE payment.parent_credit_ledger
    DROP CONSTRAINT IF EXISTS chk_ledger_spend_only,
    -- migration-lint: allow-validating-constraint prod not live (dev/uat data only, as V198/V199); every
    -- existing row has spend_only_amount = 0 (the column default), which passes.
    ADD CONSTRAINT chk_ledger_spend_only CHECK (
        spend_only_amount = (0)::numeric
        OR (amount > (0)::numeric AND spend_only_amount > (0)::numeric AND spend_only_amount <= amount)
        OR (amount < (0)::numeric AND spend_only_amount < (0)::numeric AND spend_only_amount >= amount));

ALTER TABLE payment.parent_credit_ledger
    DROP CONSTRAINT IF EXISTS chk_ledger_type,
    -- migration-lint: allow-validating-constraint prod not live (dev/uat data only, as V198/V199);
    -- the widened list is a superset of the old one, so every existing row passes the scan.
    ADD CONSTRAINT chk_ledger_type CHECK (((type)::text = ANY ((ARRAY[
        'BOOKING_DEDUCTION'::character varying,
        'BOOKING_DEDUCTION_REVERSAL'::character varying,
        'BOOKING_REFUND'::character varying,
        'BOOKING_REFUND_REVERSAL'::character varying,
        'CASH_OUT_DEBIT'::character varying,
        'STRIPE_FEE_DEBIT'::character varying,
        'CASH_OUT_REVERSAL'::character varying,
        'CHARGEBACK_CREDIT_REVERSAL'::character varying,
        'CHARGEBACK_CREDIT_RESTORE'::character varying,
        'DISPUTE_CREDIT'::character varying,
        'BATCH_SHARE_REFUND'::character varying
    ])::text[])));

-- migration-lint: allow-blocking-index partial index over a type no row has yet, so the build reads the
-- table once and writes nothing; prod is not live (V198/V199 precedent).
CREATE UNIQUE INDEX IF NOT EXISTS uq_pcl_batch_share_refund
    ON payment.parent_credit_ledger (reference_id)
    WHERE reference_id IS NOT NULL AND type = 'BATCH_SHARE_REFUND';

-- migration-lint: allow-blocking-index booking_payments is small on dev/uat and prod is not live, so the
-- build is a fast single scan (V181/V186/V193 precedent).
CREATE INDEX IF NOT EXISTS idx_bp_stripe_payment_intent
    ON payment.booking_payments (stripe_payment_intent_id)
    WHERE stripe_payment_intent_id IS NOT NULL;

ALTER TABLE admin.admin_alerts
    DROP CONSTRAINT IF EXISTS admin_alerts_type_check,
    -- migration-lint: allow-validating-constraint small, internal admin-queue table (V193 precedent); the
    -- widened list is a superset of the old one, so every existing row passes the scan.
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
        'PAYOUT_REVERSAL_FAILED'::character varying,
        'BOOKING_ACTION_REQUIRED'::character varying
    ])::text[])));
