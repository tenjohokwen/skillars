-- fix/audit3-booking-scheduling-integrity: supports BookingRepository.countOpenPackReservations,
-- the request-time pack-session reservation count (BookingService.hasUnreservedPackSession, statuses
-- BookingService.PACK_RESERVING_STATUSES). Run under the coach and pack row locks on every pack booking
-- request and coach duplicate, so it must not scan booking.bookings. Partial and whitelist-scoped
-- (StatusScopedPartialIndexConventionIT): only open, not-yet-settled bookings drawing on a pack are indexed.
SET LOCAL lock_timeout = '5s';

-- migration-lint: allow-blocking-index <reason: CREATE INDEX CONCURRENTLY has no working online-safe
-- mechanism in this repo's single-connection Flyway runner yet (docs/deployment/migration-conventions.md
-- rule 4); the partial predicate keeps the build small, and lock_timeout bounds the wait on a busy table>
CREATE INDEX IF NOT EXISTS idx_bkg_open_pack_reservation
    ON booking.bookings USING btree (session_pack_purchase_id)
    WHERE session_pack_purchase_id IS NOT NULL
      AND status IN ('REQUESTED', 'ACCEPTED', 'PAYMENT_PENDING');
