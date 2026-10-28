-- fix/payment-money-integrity: the admin finance overview's gross volume omitted session-pack
-- purchases. A pack is charged to the platform account at purchase time (tier.total_price), but the
-- purchase row never recorded that amount: session_pack_tiers.total_price and session_count are both
-- coach-editable after the sale, so neither a join to the tier nor price_per_session x session_count
-- reproduces what was actually charged. amount_charged stamps it at purchase
-- (SessionPackPaymentService.createPurchase), the same "lock it at capture" rule as
-- booking_payments.commission_rate.
--
-- Additive, nullable ADD COLUMN (migration-conventions.md rule 1). The reader
-- (SessionPackPurchaseRepository.sumAmountChargedForPeriod) COALESCEs NULL to 0.
--
-- BACKFILL: pre-existing rows get their tier's CURRENT total_price — the best available
-- approximation (exact unless the coach edited the tier price after the sale; no price history
-- exists to do better). Single-transaction UPDATE, not the executeInTransaction=false sidecar: the
-- predicate matches every row on this migration's only real execution, so it is bounded by table
-- size, and session_pack_purchases is a small table (one row per pack sold) — same reasoning as V157.
SET LOCAL lock_timeout = '5s';

ALTER TABLE payment.session_pack_purchases
    ADD COLUMN IF NOT EXISTS amount_charged numeric(10,2);

UPDATE payment.session_pack_purchases p
SET amount_charged = t.total_price
FROM payment.session_pack_tiers t
WHERE t.pack_tier_id = p.pack_tier_id
  AND p.amount_charged IS NULL;
