-- UAT.3 AC1. A booking_payments row may now exist BEFORE the Stripe capture, so that
-- "no row" provably means "no charge was ever attempted" (see PaymentPendingSweeper).
--
-- chk_bp_status is declared inline in V62__session_payment_credit_wallet.sql:90 and PostgreSQL
-- names it exactly as written there, so dropping it by that name is safe.
-- CAPTURE_PENDING is 15 characters and status is VARCHAR(16) — it fits with one character to
-- spare. Do not rename it to anything longer without widening the column.
ALTER TABLE payment.booking_payments
    DROP CONSTRAINT chk_bp_status,
    ADD CONSTRAINT chk_bp_status
        CHECK (status IN ('CAPTURE_PENDING', 'CAPTURED', 'CHARGE_FAILED', 'FROZEN'));
