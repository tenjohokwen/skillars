-- fix/payment-agreed-price-lock: lock the session price a parent agreed to at request time, and give the
-- booking_payments row an honest status for a batch booking that was charged and then refunded to credit.
--
-- 1. booking.bookings.agreed_price — the coach's per-session price when the (non-pack) request was made.
--    Accept/settle charges this, not the coach's current price, so a coach re-pricing between request and
--    accept can no longer change what the parent's saved card is charged. NULL for pack bookings (the
--    pack's locked price_per_session applies), for every row written before this migration (settle falls
--    back to the current coach_pricing row, the pre-V192 behaviour), and for a request made while the coach
--    had no pricing row (accept then fails unless pricing exists by then).
--
-- 2. chk_bp_status widened with REFUNDED_TO_CREDIT (18 chars, fits the varchar(20)): the batch charge
--    succeeded but this booking's confirm failed, so it was declined and its full share (credit + card)
--    credited back to the parent's wallet as a BOOKING_REFUND. Unlike CHARGE_FAILED ("no money moved"),
--    credit_debited / stripe_charged keep the amounts actually taken. Terminal
--    (BookingPaymentStatus.isTerminal). Rebuilt as the union: V138's 5 values + this one. MERGE NOTE: any
--    other branch widening this CHECK must re-union.
--
-- Migration conventions (docs/deployment/migration-conventions.md), mirroring V189:
--  - Rule 1 (additive): a nullable column with no default is metadata-only on Postgres 11+. Old code during
--    a rolling deploy never writes it (NULL = the legacy fallback).
--  - NOT VALID / VALIDATE skipped (migration-lint: allow-validating-constraint markers below): booking_payments
--    is small with no bulk-insert path, and prod is not live, so the validating scan touches dev/uat only.
--  - Rule 5 (widen one release before the first write): prod is not live, so - as at V183/V189 - the
--    widening and its first write ship together.
--  - Rule 7: lock_timeout so the brief ACCESS EXCLUSIVE locks fail fast instead of queueing.
SET LOCAL lock_timeout = '5s';

ALTER TABLE booking.bookings
    ADD COLUMN IF NOT EXISTS agreed_price NUMERIC(10, 2);

ALTER TABLE payment.booking_payments
    DROP CONSTRAINT IF EXISTS chk_bp_status,
    -- migration-lint: allow-validating-constraint prod not live (dev/uat data only, as V183/V189), no
    -- bulk-insert path; a fast validating CHECK swap, not a rolling-deploy lock-exposure risk.
    ADD CONSTRAINT chk_bp_status CHECK (((status)::text = ANY ((ARRAY[
        'CAPTURE_PENDING'::character varying,
        'CAPTURED'::character varying,
        'CHARGE_FAILED'::character varying,
        'FROZEN'::character varying,
        'CAPTURE_ABANDONED'::character varying,
        'REFUNDED_TO_CREDIT'::character varying
    ])::text[])));
