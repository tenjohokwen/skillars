-- V29__booking_module_init.sql
CREATE SCHEMA IF NOT EXISTS booking;

CREATE TABLE booking.coach_availability_blocks (
    id             UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    coach_id       UUID        NOT NULL,
    start_datetime TIMESTAMPTZ NOT NULL,
    end_datetime   TIMESTAMPTZ NOT NULL,
    reason         VARCHAR(500),
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT chk_block_end_after_start CHECK (end_datetime > start_datetime)
);

CREATE INDEX idx_availability_blocks_coach_id ON booking.coach_availability_blocks (coach_id);
