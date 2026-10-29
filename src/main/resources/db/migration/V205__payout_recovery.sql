-- fix/payment-payout-recovery: coach payouts that end at the right amount, and money a failed reversal
-- left with the coach is recovered.
--
-- 1. coach_payouts.original_net_amount / original_gross_amount / original_commission_amount: the payout as
--    first computed, before any dispute / chargeback cut. A refund's coach share is computed from it (so
--    two partial refunds cost the coach the same whether the payout had released or not), and an
--    UPHOLD_COACH resolution restores a cut pending payout to it, or tops a released one up to it.
--    Backfilled from the current amounts (prod is not live; a row already cut keeps its cut as "original").
-- 2. coach_payouts.topup_amount / topup_transfer_id: what an UPHOLD_COACH top-up transfer paid the coach
--    on top of the original transfer (the original transfer was reversed, or released at a cut net).
-- 3. coach_payouts.debt_offset_amount: the part of this payout's net withheld from its transfer to repay
--    the coach's debt (coach_payout_debts). The coach still earned the whole net.
-- 4. coach_payout_debts: money the platform refunded to a payer that a transfer reversal could not take
--    back from the coach (balance withdrawn, account closed). OPEN debts are deducted from the coach's
--    next transfers, oldest first; an UPHOLD_COACH on the booking waives its debt.
--
-- Migration conventions (docs/deployment/migration-conventions.md), mirroring V186/V198/V200:
--  - Additive only; the NOT NULL columns carry a constant default (catalog-only on PG 11+).
--  - Rule 5: the columns and their first writes ship together — prod is not live.
SET LOCAL lock_timeout = '5s';

ALTER TABLE payment.coach_payouts
    ADD COLUMN IF NOT EXISTS original_net_amount numeric(10,2),
    ADD COLUMN IF NOT EXISTS original_gross_amount numeric(10,2),
    ADD COLUMN IF NOT EXISTS original_commission_amount numeric(10,2),
    ADD COLUMN IF NOT EXISTS topup_amount numeric(10,2) NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS topup_transfer_id text,
    ADD COLUMN IF NOT EXISTS debt_offset_amount numeric(10,2) NOT NULL DEFAULT 0;

UPDATE payment.coach_payouts
SET original_net_amount = net_amount,
    original_gross_amount = gross_amount,
    original_commission_amount = commission_amount
WHERE original_net_amount IS NULL;

-- New, empty table: constraints inline, nothing to validate (V148/V175/V186/V200 precedent).
CREATE TABLE IF NOT EXISTS payment.coach_payout_debts (
    id               uuid          NOT NULL PRIMARY KEY,
    coach_id         uuid          NOT NULL,
    booking_id       uuid          NOT NULL,
    amount           numeric(10,2) NOT NULL CONSTRAINT chk_cpd_amount_positive CHECK (amount > 0),
    recovered_amount numeric(10,2) NOT NULL DEFAULT 0,
    status           varchar(20)   NOT NULL,
    reason           text,
    created_at       timestamptz   NOT NULL DEFAULT now(),
    updated_at       timestamptz   NOT NULL DEFAULT now(),
    CONSTRAINT chk_cpd_status CHECK (status IN ('OPEN', 'RECOVERED', 'WAIVED')),
    CONSTRAINT chk_cpd_recovered CHECK (recovered_amount >= 0 AND recovered_amount <= amount)
);

-- migration-lint: allow-blocking-index new empty table, nothing to scan.
CREATE INDEX IF NOT EXISTS idx_cpd_coach_open ON payment.coach_payout_debts (coach_id, created_at, id)
    WHERE status = 'OPEN';

-- migration-lint: allow-blocking-index new empty table, nothing to scan.
CREATE INDEX IF NOT EXISTS idx_cpd_booking ON payment.coach_payout_debts (booking_id);
