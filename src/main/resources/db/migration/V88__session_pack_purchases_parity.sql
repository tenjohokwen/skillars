ALTER TABLE payment.session_pack_purchases
    ADD COLUMN player_id BIGINT NOT NULL,
    ADD COLUMN paused_until TIMESTAMPTZ,
    ADD COLUMN expired_notified_at TIMESTAMPTZ;

CREATE INDEX idx_session_pack_purchases_parent_id ON payment.session_pack_purchases (parent_id);
CREATE INDEX idx_session_pack_purchases_coach_player ON payment.session_pack_purchases (coach_id, player_id);
CREATE INDEX idx_session_pack_purchases_expiry_notify ON payment.session_pack_purchases (expires_at)
    WHERE expired_notified_at IS NULL;
