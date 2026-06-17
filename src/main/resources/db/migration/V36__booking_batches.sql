CREATE TABLE booking.booking_batches (
    id              UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    parent_id       BIGINT       NOT NULL,
    coach_id        UUID         NOT NULL,
    requested_count INT          NOT NULL,
    total_amount    NUMERIC(10,2) NOT NULL,
    status          VARCHAR(20)  NOT NULL DEFAULT 'PENDING'
                        CHECK (status IN ('PENDING','FULLY_ACCEPTED','PARTIALLY_ACCEPTED','DECLINED')),
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX idx_bkg_batch_coach_status ON booking.booking_batches (coach_id, status);
CREATE INDEX idx_bkg_batch_parent       ON booking.booking_batches (parent_id);

ALTER TABLE booking.bookings
    ADD COLUMN batch_id UUID REFERENCES booking.booking_batches(id);
CREATE INDEX idx_bkg_batch_id ON booking.bookings (batch_id);

INSERT INTO main.platform_config (id, key, value, value_type, description, updated_at)
VALUES (50, 'booking.batch.maxSize', '5', 'LONG',
        'Maximum number of slots a parent can request in a single bulk batch', NOW())
ON CONFLICT DO NOTHING;
