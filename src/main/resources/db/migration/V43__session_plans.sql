CREATE TABLE session.sessions (
    id                UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    booking_id        UUID         NOT NULL,
    coach_id          UUID         NOT NULL,
    player_id         BIGINT       NOT NULL,
    blocks            JSONB        NOT NULL DEFAULT '[]',
    session_dna       JSONB,
    equipment_list    JSONB        NOT NULL DEFAULT '[]',
    development_focus JSONB        NOT NULL DEFAULT '[]',
    status            VARCHAR(20)  NOT NULL DEFAULT 'DRAFT'
                      CHECK (status IN ('DRAFT', 'SAVED', 'COMPLETED')),
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX uq_sessions_booking_id ON session.sessions (booking_id);
CREATE INDEX idx_sessions_coach_id ON session.sessions (coach_id);
