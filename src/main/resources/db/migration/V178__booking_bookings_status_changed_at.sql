-- fix/booking-integrity (B2): when a booking entered its CURRENT status. The dispute window
-- (DisputeService.raiseDispute) and review maturity (BookingRepository.findQualifyingCompletedBookings)
-- were measured from updated_at, which every later write bumps (a recap edit, a reminder stamp, GDPR
-- note anonymisation, a plan note), so any such write silently reopened the dispute window and reset
-- review maturity. Written by Booking.setStatus whenever the status value changes, and on insert.
--
-- Additive, nullable ADD COLUMN per the expand/contract standard (migration-conventions.md rule 1).
-- Deliberately NO default: a default of now() would be evaluated once for every existing row (fast
-- default), stamping them all with the migration time and reopening every old dispute window.
SET LOCAL lock_timeout = '5s';

ALTER TABLE booking.bookings
    ADD COLUMN IF NOT EXISTS status_changed_at timestamp with time zone;

-- Freeze the anchor of every already-SETTLED booking at its current updated_at (the best available
-- approximation of when it settled), so a post-deploy write to an old booking (recap edit, GDPR
-- anonymisation) can no longer reopen its dispute window or reset its review maturity. Only the
-- terminal statuses the two windows read (DisputeService.ELIGIBLE_STATUSES, review = COMPLETED):
-- they never change status again, so even old code still running during a rolling deploy cannot
-- leave a stale value behind. Non-terminal rows stay NULL and are stamped by Booking.setStatus on
-- their next transition; readers fall back to updated_at while NULL (Booking.getStatusAnchor /
-- COALESCE in JPQL).
-- migration-lint: allow-full-table-dml <reason: booking.bookings is small (no production deployment yet, see V172); one-off bounded backfill of terminal rows only, idempotent via IS NULL>
UPDATE booking.bookings
   SET status_changed_at = updated_at
 WHERE status_changed_at IS NULL
   AND status IN ('COMPLETED', 'CANCELLED', 'CANCELLED_PARENT', 'CANCELLED_COACH',
                  'NO_SHOW_PLAYER', 'NO_SHOW_COACH');
