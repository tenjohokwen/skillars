-- Story 3.7: Add PAUSED status to support session pause/resume
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
        'PAUSED',
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
