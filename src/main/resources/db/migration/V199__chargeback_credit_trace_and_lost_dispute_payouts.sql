-- fix/payment-dispute-credit-trace-and-lost-dispute: a card charge disputed after the money it paid was
-- given back as wallet credit, and payouts left stuck by a dispute lost before the session completed.
--
-- 1. payment.parent_credit_ledger:
--    * source_payment_intent_id / source_card_amount — the card charge a BOOKING_REFUND credit came from,
--      and how much of it was card money (the rest was wallet credit the charge did not fund). Written
--      where the booking's own booking_payments row does not say it (a batch share credited back for a
--      booking settled elsewhere); NULL means "trace through booking_payments / the booking's pack".
--    * types CHARGEBACK_CREDIT_REVERSAL (negative: the unspent credit a disputed charge funded, taken
--      back when the dispute opens, capped at the wallet balance) and CHARGEBACK_CREDIT_RESTORE
--      (positive: given back when the dispute is won). reference_id = a name-based UUID of the Stripe
--      dispute id (ChargebackCreditReversalService.reference). chk_ledger_type and chk_ledger_amount_sign
--      are rebuilt from their V198 definitions plus the two new types.
--    * uq_pcl_chargeback_credit_reversal / _restore: at most one row of each per (reference, payer) —
--      a re-delivered webhook can neither debit nor restore twice. The lost-close top-up uses its own
--      reference (see the service), so it is covered by the same index.
-- 2. payment.charge_disputes:
--    * credit_reversal_target — the part of the disputed amount attributed to traced wallet credit
--      (NULL until traced). Written under the dispute row lock with the debit, so a redelivery debits
--      nothing more.
--    * payout_loss_fraction — on a LOST close, the share of each funded payout's card portion the coach
--      bears; read again when a session paid by that charge completes later, so the payout is cut the
--      same way instead of being parked in HOLD.
-- 3. main.platform_config: payment.payout.stale_alert_days (default 14) for StalePayoutAlertScheduler.
--
-- Migration conventions (docs/deployment/migration-conventions.md), mirroring V193/V198:
--  - Additive only; nullable columns without a default are catalog-only.
--  - Validating CHECK swaps / non-concurrent indexes: prod is not live (dev/uat data only), so each is a
--    fast scan, not a rolling-deploy lock risk (markers on the clauses below).
--  - Rule 5: the widenings and their first writes ship together (the V149/V152/V162/V186 owner decision).
SET LOCAL lock_timeout = '5s';

ALTER TABLE payment.parent_credit_ledger
    ADD COLUMN IF NOT EXISTS source_payment_intent_id varchar(255),
    ADD COLUMN IF NOT EXISTS source_card_amount numeric(10,2);

ALTER TABLE payment.parent_credit_ledger
    DROP CONSTRAINT IF EXISTS chk_ledger_type,
    -- migration-lint: allow-validating-constraint prod not live (dev/uat data only, as V193/V198);
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
        'CHARGEBACK_CREDIT_RESTORE'::character varying
    ])::text[]))),
    DROP CONSTRAINT IF EXISTS chk_ledger_amount_sign,
    -- migration-lint: allow-validating-constraint prod not live; no existing row has a new type, so the
    -- sign rule is unchanged for every row the scan reads.
    -- Same rule as V198, written without CASE ... END (the migration lint reads a bare END as a
    -- transaction boundary): debit types negative, every other type positive.
    ADD CONSTRAINT chk_ledger_amount_sign CHECK (
        ((type)::text = ANY ((ARRAY[
            'BOOKING_DEDUCTION'::character varying,
            'CASH_OUT_DEBIT'::character varying,
            'STRIPE_FEE_DEBIT'::character varying,
            'BOOKING_REFUND_REVERSAL'::character varying,
            'CHARGEBACK_CREDIT_REVERSAL'::character varying
        ])::text[]) AND amount < (0)::numeric)
        OR (NOT ((type)::text = ANY ((ARRAY[
            'BOOKING_DEDUCTION'::character varying,
            'CASH_OUT_DEBIT'::character varying,
            'STRIPE_FEE_DEBIT'::character varying,
            'BOOKING_REFUND_REVERSAL'::character varying,
            'CHARGEBACK_CREDIT_REVERSAL'::character varying
        ])::text[])) AND amount > (0)::numeric));

-- migration-lint: allow-blocking-index partial index over a type no row has yet, so the build reads the
-- table once and writes nothing; prod is not live (V181/V186/V193/V198 precedent).
CREATE UNIQUE INDEX IF NOT EXISTS uq_pcl_chargeback_credit_reversal
    ON payment.parent_credit_ledger (reference_id, parent_id)
    WHERE type = 'CHARGEBACK_CREDIT_REVERSAL';

-- migration-lint: allow-blocking-index partial index over a type no row has yet (see above).
CREATE UNIQUE INDEX IF NOT EXISTS uq_pcl_chargeback_credit_restore
    ON payment.parent_credit_ledger (reference_id, parent_id)
    WHERE type = 'CHARGEBACK_CREDIT_RESTORE';

-- migration-lint: allow-blocking-index partial index over a column added empty just above, so the build
-- reads the table once and writes nothing; prod is not live.
CREATE INDEX IF NOT EXISTS idx_pcl_source_payment_intent
    ON payment.parent_credit_ledger (source_payment_intent_id)
    WHERE source_payment_intent_id IS NOT NULL;

ALTER TABLE payment.charge_disputes
    ADD COLUMN IF NOT EXISTS credit_reversal_target numeric(10,2),
    ADD COLUMN IF NOT EXISTS payout_loss_fraction numeric(9,6);

INSERT INTO main.platform_config (key, value, value_type, description) VALUES ('payment.payout.stale_alert_days', '14', 'LONG', 'Days a coach payout may sit in FROZEN or HOLD without any change before StalePayoutAlertScheduler raises a PAYOUT_HELD admin alert for it. Bounded 1-365; out of range falls back to 14.') ON CONFLICT (key) DO NOTHING;
