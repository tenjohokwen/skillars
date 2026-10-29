-- fix/payment-charge-idempotency: booking charges get a per-attempt Stripe idempotency key, persisted
-- BEFORE the Stripe call, and a subscribe attempt marker that serialises double-submitted subscribes.
--
-- 1. booking_payments.charge_idempotency_key: the key the CAPTURE_PENDING reservation's charge is sent
--    under ("pi-booking-{bookingId}" for a single booking, "pi-batch-{batchId}-{hash of the reserved
--    booking ids}" for a batch — so two concurrent accepts of one batch can no longer share a key and
--    replay each other's PaymentIntent). It is also put on the PaymentIntent's metadata (chargeKey), so
--    ChargeOutcomeReconciler can find the PaymentIntent of a charge whose response was lost (timeout,
--    5xx, connection reset, idempotency conflict) and settle or decline the reservation instead of
--    the booking being declined while the parent's card was charged.
-- 2. coach_subscriptions / player_subscriptions.subscribe_attempt_key / _at: a short-lived marker
--    written under the subscription row lock before the Stripe subscription is created. A second
--    subscribe for the same owner while it stands is refused (payment.subscription.inProgress) instead
--    of creating — and charging — a second subscription.
--
-- Migration conventions (docs/deployment/migration-conventions.md):
--  - Rule 1: additive, nullable columns, no default — metadata-only ALTERs.
--  - Rule 4: plain CREATE INDEX via marker — a partial index over a brand-new, all-NULL column (no
--    existing row qualifies, so it builds instantly); prod is not live (as V184/V200).
--  - Rule 5: the columns and their first writes ship together.
SET LOCAL lock_timeout = '5s';

ALTER TABLE payment.booking_payments
    ADD COLUMN IF NOT EXISTS charge_idempotency_key varchar(128);

-- Serves BookingPaymentRepository.findReconcilableCharges (ChargeOutcomeReconciler) and
-- findByChargeIdempotencyKey (the sibling rows of one batch charge).
-- migration-lint: allow-blocking-index partial index over a new all-NULL column, no row qualifies yet.
CREATE INDEX IF NOT EXISTS idx_bp_charge_key_pending
    ON payment.booking_payments (charge_idempotency_key)
    WHERE charge_idempotency_key IS NOT NULL;

ALTER TABLE payment.coach_subscriptions
    ADD COLUMN IF NOT EXISTS subscribe_attempt_key varchar(64),
    ADD COLUMN IF NOT EXISTS subscribe_attempt_at timestamptz;

ALTER TABLE payment.player_subscriptions
    ADD COLUMN IF NOT EXISTS subscribe_attempt_key varchar(64),
    ADD COLUMN IF NOT EXISTS subscribe_attempt_at timestamptz;
