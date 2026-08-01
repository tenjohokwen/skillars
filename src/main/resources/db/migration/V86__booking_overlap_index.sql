CREATE INDEX idx_bkg_coach_status_time
    ON booking.bookings (coach_id, status, requested_start_time, requested_end_time);
