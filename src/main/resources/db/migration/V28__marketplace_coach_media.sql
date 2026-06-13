CREATE TABLE marketplace.coach_media (
    id            UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    coach_id      UUID         NOT NULL REFERENCES marketplace.coach_profiles(id),
    file_url      VARCHAR(512) NOT NULL,
    media_type    VARCHAR(10)  NOT NULL,
    display_order INT          NOT NULL DEFAULT 0,
    uploaded_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT chk_media_type CHECK (media_type IN ('IMAGE', 'VIDEO')),
    CONSTRAINT uq_coach_media_order UNIQUE (coach_id, display_order)
);
CREATE INDEX idx_coach_media_coach_order ON marketplace.coach_media(coach_id, display_order);
