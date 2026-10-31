-- feat/noshow-claim-window-and-split (S-07): the durable record of a coach no-show claim on a booking.
--
-- bookings.coach_no_show_claim — how the booker claimed the coach never showed: REPORT (the report action on an
-- UPCOMING session), LATE_CANCEL (a parent cancel after start + booking.lateCancel.graceMinutes of a never-started
-- session — the same claim through the other door) or SPLIT (a report on a session the coach quick-completed
-- WITHOUT a live Start: neither side is established, so the fee is shared 50/50 and nobody is struck).
-- bookings.coach_no_show_claimed_at — when. NULL / NULL = no claim. Written once, in the claiming transaction.
-- Nothing reads it for a decision today; it is the audit trail and what a repeat-claimant count can be built on.
--
-- Additive: two nullable columns with no default are catalog-only changes (PG 11+). No CHECK: the value set is
-- owned by BookingService and read nowhere that a stray value could break.
SET LOCAL lock_timeout = '5s';

ALTER TABLE booking.bookings
    ADD COLUMN IF NOT EXISTS coach_no_show_claim varchar(16),
    ADD COLUMN IF NOT EXISTS coach_no_show_claimed_at timestamp with time zone;
