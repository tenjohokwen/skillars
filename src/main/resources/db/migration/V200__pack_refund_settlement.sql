-- fix/payment-pack-refund-rounding: pack session refunds settle on the same ledger as coach payouts.
--
-- A pack's sessions settle one of two ways: paid to the coach (payout_sessions_allocated /
-- payout_gross_allocated, V186) or refunded to the payer as wallet credit (a cancellation / coach no-show /
-- admin cancellation / pause of a booking on a pack that has since expired). Each settles at
-- amount_charged / session_count rounded DOWN, except the LAST to settle, which takes the remainder. Refunds
-- used to always take the rounded-down share and were never counted, so up to session_count - 1 cents per
-- pack reached neither coach nor payer.
--
-- 1. session_pack_purchases.refund_sessions_issued / refund_gross_issued: the running "refunded so far".
-- 2. pack_session_refunds: the per-booking marker (PK booking_id) and the amount refunded, so a refund is
--    settled once per booking and an UPHOLD_COACH clawback can un-settle exactly what was recorded.
-- 3. Backfill (dev/uat data only): the BOOKING_REFUND ledger rows already written for a pack booking that
--    never produced a coach payout and was not clawed back are those expired-pack refunds; they are recorded
--    as markers and summed onto their pack, so the next settlement sees them.
--
-- Migration conventions (docs/deployment/migration-conventions.md), mirroring V186/V195/V198:
--  - Additive only; the NOT NULL columns carry a constant default (catalog-only on PG 11+).
--  - Rule 5: the columns and their first writes ship together — prod is not live (the V149/V152/V162/V186
--    owner decision).
SET LOCAL lock_timeout = '5s';

ALTER TABLE payment.session_pack_purchases
    ADD COLUMN IF NOT EXISTS refund_sessions_issued integer NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS refund_gross_issued numeric(10,2) NOT NULL DEFAULT 0;

-- New, empty table: constraints inline, nothing to validate (V148/V175/V186/V195 precedent).
CREATE TABLE IF NOT EXISTS payment.pack_session_refunds (
    booking_id   uuid          NOT NULL PRIMARY KEY,
    purchase_id  uuid          NOT NULL
        CONSTRAINT fk_psrf_purchase REFERENCES payment.session_pack_purchases (purchase_id) ON DELETE CASCADE,
    amount       numeric(10,2) NOT NULL CONSTRAINT chk_psrf_amount_non_negative CHECK (amount >= 0),
    refunded_at  timestamptz   NOT NULL
);

-- migration-lint: allow-blocking-index new empty table, nothing to scan.
CREATE INDEX IF NOT EXISTS idx_psrf_purchase_id ON payment.pack_session_refunds (purchase_id);

INSERT INTO payment.pack_session_refunds (booking_id, purchase_id, amount, refunded_at)
SELECT b.id, b.session_pack_purchase_id, l.amount, l.created_at
FROM payment.parent_credit_ledger l
JOIN booking.bookings b ON b.id = l.reference_id
WHERE l.type = 'BOOKING_REFUND'
  AND l.amount > 0
  AND b.session_pack_purchase_id IS NOT NULL
  AND NOT EXISTS (SELECT 1 FROM payment.coach_payouts cp WHERE cp.booking_id = b.id AND cp.gross_amount > 0)
  AND NOT EXISTS (SELECT 1 FROM payment.parent_credit_ledger r
                  WHERE r.reference_id = l.reference_id AND r.type = 'BOOKING_REFUND_REVERSAL')
ON CONFLICT (booking_id) DO NOTHING;

UPDATE payment.session_pack_purchases p
SET refund_sessions_issued = agg.sessions,
    refund_gross_issued = agg.gross
FROM (SELECT purchase_id, count(*) AS sessions, sum(amount) AS gross
      FROM payment.pack_session_refunds
      GROUP BY purchase_id) agg
WHERE p.purchase_id = agg.purchase_id;
