-- Platform config: quick-complete timeout
INSERT INTO main.platform_config (id, key, value, value_type, description, updated_at)
VALUES (39, 'booking.quick_complete_timeout_hours', '24', 'LONG', 'Hours before un-confirmed Quick Complete auto-confirms', NOW())
ON CONFLICT DO NOTHING;

CREATE TABLE booking.session_completion_data (
    id                  UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    booking_id          UUID        NOT NULL UNIQUE REFERENCES booking.bookings(id),
    coach_id            UUID        NOT NULL,
    player_id           BIGINT      NOT NULL,
    player_attended     BOOLEAN     NOT NULL DEFAULT true,
    effort_rating       SMALLINT    CHECK (effort_rating BETWEEN 1 AND 5),
    focus_rating        SMALLINT    CHECK (focus_rating BETWEEN 1 AND 5),
    technique_rating    SMALLINT    CHECK (technique_rating BETWEEN 1 AND 5),
    voice_note_text     VARCHAR(2000),
    homework_drill_ids  TEXT,
    completion_mode     VARCHAR(10) NOT NULL CHECK (completion_mode IN ('LIVE', 'QUICK')),
    created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_scd_booking_id ON booking.session_completion_data(booking_id);
