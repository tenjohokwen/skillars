CREATE TABLE booking.booking_reschedule_requests (
    id                  UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    booking_id          UUID        NOT NULL REFERENCES booking.bookings(id),
    proposed_by         VARCHAR(10) NOT NULL CHECK (proposed_by IN ('PARENT', 'COACH')),
    proposed_start_time TIMESTAMPTZ NOT NULL,
    proposed_end_time   TIMESTAMPTZ NOT NULL,
    status              VARCHAR(10) NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING', 'ACCEPTED', 'DECLINED')),
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_reschedule_req_booking_id ON booking.booking_reschedule_requests (booking_id);
