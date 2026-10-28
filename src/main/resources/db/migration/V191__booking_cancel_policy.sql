-- fix/booking-cancel-policy-loopholes: the start time a booking was confirmed with, set the first
-- time a reschedule is accepted (NULL = never rescheduled). BookingService.cancelBookingAsParent
-- measures the 24h refund window from the earlier of this and requested_start_time.
--
-- No platform_config seed rows: booking.lateCancel.graceMinutes and coach.excusedCancellations.*
-- are HAS_CODE_DEFAULT keys in ConfigBounds (defaults 30 / 3 / 90), editable from the admin config
-- editor without a stored row. coach_reliability_strikes.reason has no CHECK constraint, so the new
-- COACH_CANCELLATION_EXCUSED_LIMIT reason needs no schema change.
-- Fail fast instead of queueing behind live booking traffic (migration-conventions rule 7).
SET LOCAL lock_timeout = '5s';

ALTER TABLE booking.bookings ADD COLUMN original_start_time timestamp with time zone;
