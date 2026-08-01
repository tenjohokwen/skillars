-- Story 3.11 follow-up (review decision D1): DB-level backstop for coach slot double-booking.
-- The app-layer overlap check (BookingService) only guards createBookingRequest/acceptBooking;
-- other write paths (BookingBatchService, RescheduleService) do not call it. This constraint
-- guarantees no two *committed* bookings for the same coach can have overlapping time ranges,
-- regardless of which code path writes the row.
--
-- REQUESTED is deliberately excluded here: two overlapping REQUESTED bookings competing for the
-- same slot is expected, in-band behavior (that's exactly the race AC3's accept-time re-check
-- exists to resolve) — not a data-integrity violation. Including REQUESTED in this constraint
-- would also make the migration itself unsafe to deploy against any table that already has
-- legacy overlapping REQUESTED rows predating this story.
CREATE EXTENSION IF NOT EXISTS btree_gist;

ALTER TABLE booking.bookings
    ADD CONSTRAINT excl_bkg_coach_slot_overlap
    EXCLUDE USING gist (
        coach_id WITH =,
        tstzrange(requested_start_time, requested_end_time, '[)') WITH &&
    )
    WHERE (status IN (
        'ACCEPTED', 'PAYMENT_PENDING', 'CONFIRMED', 'UPCOMING', 'IN_PROGRESS', 'PAUSED'
    ));
