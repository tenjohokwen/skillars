-- fix/booking-integrity (B2): when a booking entered its CURRENT status. The dispute window
-- (DisputeService.raiseDispute) and review maturity (BookingRepository.findQualifyingCompletedBookings)
-- were measured from updated_at, which every later write bumps (a recap edit, a reminder stamp, GDPR
-- note anonymisation, a plan note), so any such write silently reopened the dispute window and reset
-- review maturity. Written by Booking.setStatus whenever the status value changes, and on insert.
--
-- Additive, nullable ADD COLUMN per the expand/contract standard (migration-conventions.md rule 1).
-- Deliberately NO default and NO backfill: a default of now() would be evaluated once for every
-- existing row (fast default), stamping them all with the migration time and reopening every old
-- dispute window. Pre-existing rows stay NULL and readers fall back to updated_at
-- (Booking.getStatusAnchor / COALESCE in JPQL) -- exactly the pre-V178 behaviour for those rows.
SET LOCAL lock_timeout = '5s';

ALTER TABLE booking.bookings
    ADD COLUMN IF NOT EXISTS status_changed_at timestamp with time zone;
