-- Story Deferred-15.

-- AC1: grace period for PaymentPendingSweeper. 120 minutes is deliberately well clear of Stripe
-- capture latency: settlement runs as an in-process AFTER_COMMIT listener, so a booking still in
-- PAYMENT_PENDING two hours after its last write is not in flight — it is stranded.
INSERT INTO main.platform_config (id, key, value, value_type, description, updated_at)
VALUES (601, 'booking.payment_pending_sweep_grace_minutes', '120', 'LONG',
        'Minutes a booking may sit in PAYMENT_PENDING before PaymentPendingSweeper treats it as stranded', NOW())
ON CONFLICT (key) DO NOTHING;

-- AC6: dedupe for the pack expiry-warning email. The notifier runs daily against a 14-day window,
-- so without this stamp one pack is re-selected on up to 14 consecutive mornings. Mirrors the
-- expired_notified_at column and partial index V88 added for the *expired* path.
ALTER TABLE payment.session_pack_purchases
    ADD COLUMN expiry_warned_at TIMESTAMPTZ;

CREATE INDEX idx_session_pack_purchases_expiry_warn ON payment.session_pack_purchases (expires_at)
    WHERE expiry_warned_at IS NULL;
