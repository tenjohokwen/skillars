-- Expand booking status constraint to cover the full 16-state machine
ALTER TABLE booking.bookings
    DROP CONSTRAINT chk_bkg_status;

ALTER TABLE booking.bookings
    ADD CONSTRAINT chk_bkg_status CHECK (status IN (
        'REQUESTED',
        'ACCEPTED',
        'PAYMENT_PENDING',
        'CONFIRMED',
        'UPCOMING',
        'IN_PROGRESS',
        'COMPLETED_PENDING_CONFIRMATION',
        'COMPLETED',
        'DECLINED',
        'CANCELLED_PARENT',
        'CANCELLED_COACH',
        'NO_SHOW_PLAYER',
        'NO_SHOW_COACH',
        'DISPUTED',
        'REFUND_PENDING',
        'REFUNDED'
    ));

-- Nullable refund columns populated on cancellation/no-show transitions; Epic 7 processes actual transfers
ALTER TABLE booking.bookings
    ADD COLUMN refund_amount    NUMERIC(10, 2),
    ADD COLUMN refund_eligibility VARCHAR(10) CHECK (refund_eligibility IN ('FULL', 'PARTIAL', 'NONE'));
