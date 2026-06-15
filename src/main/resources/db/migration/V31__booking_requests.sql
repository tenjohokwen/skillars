-- New platform config for booking request expiry
INSERT INTO main.platform_config (id, key, value, value_type, description, updated_at)
VALUES (38, 'booking.request_expiry_hours', '48', 'LONG', 'Hours before unaccepted booking request auto-expires', NOW())
ON CONFLICT DO NOTHING;

CREATE TABLE booking.bookings (
    id                         UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    parent_id                  BIGINT       NOT NULL,
    player_id                  BIGINT       NOT NULL,
    coach_id                   UUID         NOT NULL,
    requested_start_time       TIMESTAMPTZ  NOT NULL,
    requested_end_time         TIMESTAMPTZ  NOT NULL,
    status                     VARCHAR(30)  NOT NULL DEFAULT 'REQUESTED',
    canonical_timezone         VARCHAR(50)  NOT NULL,
    notes                      VARCHAR(500),
    version                    INT          NOT NULL DEFAULT 0,
    created_at                 TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at                 TIMESTAMPTZ  NOT NULL DEFAULT now(),
    primary_reminder_sent_at   TIMESTAMPTZ,
    secondary_reminder_sent_at TIMESTAMPTZ,
    CONSTRAINT chk_bkg_status CHECK (status IN (
        'REQUESTED','ACCEPTED','CONFIRMED','UPCOMING',
        'DECLINED','COMPLETED','DISPUTED','CANCELLED'
    )),
    CONSTRAINT chk_bkg_end_after_start CHECK (requested_end_time > requested_start_time)
);

CREATE INDEX idx_bkg_parent_id           ON booking.bookings (parent_id);
CREATE INDEX idx_bkg_coach_id            ON booking.bookings (coach_id);
CREATE INDEX idx_bkg_status_created      ON booking.bookings (status, created_at);
CREATE INDEX idx_bkg_status_start        ON booking.bookings (status, requested_start_time);
CREATE INDEX idx_bkg_player_coach_status ON booking.bookings (player_id, coach_id, status);
