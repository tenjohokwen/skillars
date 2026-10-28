-- fix/payment-session-pack-integrity.
--
-- 1. session_pack_purchases.stripe_payment_intent_id: partial UNIQUE index. One Stripe charge funds at
--    most one pack — a double-submitted purchase (same attempt key, so Stripe replays the same
--    PaymentIntent) can no longer insert a second pack for the one charge; SessionPackPaymentService
--    returns the pack that already holds the intent instead. Packs without a charge (NULL) are exempt.
-- 2. pack_session_restores: the per-booking idempotency marker for a pack session given back on a
--    cancellation / no-show / pause. Restores now run off the durable outbox (PACK_SESSION_RESTORE);
--    the handler inserts this row in the same transaction as the remaining_sessions increment, so a
--    re-driven outbox row is a no-op and the PK backstops a concurrent double delivery.
--
-- Additive only (rule 1). No production deployment of this application exists yet (the V149/V152/V162
-- owner decision), so the index ships together with its first reliance.
SET LOCAL lock_timeout = '5s';

-- migration-lint: allow-blocking-index session_pack_purchases is a small table (one row per pack sold)
-- and prod is not live, so a non-concurrent build is a fast scan of dev/uat data only (V181/V186 precedent).
CREATE UNIQUE INDEX IF NOT EXISTS uq_spp_stripe_payment_intent_id
    ON payment.session_pack_purchases (stripe_payment_intent_id)
    WHERE stripe_payment_intent_id IS NOT NULL;

-- New, empty table: constraints inline, nothing to validate (V148/V175/V186 precedent).
CREATE TABLE IF NOT EXISTS payment.pack_session_restores (
    booking_id   uuid        NOT NULL PRIMARY KEY,
    purchase_id  uuid        NOT NULL
        CONSTRAINT fk_psr_purchase REFERENCES payment.session_pack_purchases (purchase_id) ON DELETE CASCADE,
    reason       varchar(40) NOT NULL,
    restored_at  timestamptz NOT NULL
);
