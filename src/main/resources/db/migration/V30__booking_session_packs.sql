CREATE TABLE booking.session_packs_purchased (
    id                UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    parent_id         BIGINT       NOT NULL,
    player_id         BIGINT       NOT NULL,
    coach_id          UUID         NOT NULL,
    session_count     INT          NOT NULL,
    credits_remaining INT          NOT NULL,
    purchased_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    status            VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE',
    CONSTRAINT chk_spp_status CHECK (status IN ('ACTIVE', 'EXHAUSTED', 'EXPIRED')),
    CONSTRAINT chk_spp_credits_non_negative CHECK (credits_remaining >= 0),
    CONSTRAINT chk_spp_session_count_positive CHECK (session_count > 0),
    CONSTRAINT chk_spp_credits_le_count CHECK (credits_remaining <= session_count)
);

CREATE INDEX idx_spp_player_coach_status ON booking.session_packs_purchased (player_id, coach_id, status, purchased_at);
CREATE INDEX idx_spp_parent_player ON booking.session_packs_purchased (parent_id, player_id);
