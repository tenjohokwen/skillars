-- skillars-12-6 AC1: the coach's own private per-booking plan note ("what I intend to work on"),
-- shown back on the Session Prep Card and the Session Builder. Additive, nullable ADD COLUMN per the
-- expand/contract standard (migration-conventions.md rule 1) -- no backfill is possible (there is no
-- prior data to reconstruct this from), same reasoning as V163's player_request on this same table.
SET LOCAL lock_timeout = '5s';

ALTER TABLE booking.bookings
    ADD COLUMN IF NOT EXISTS coach_plan_note varchar(500);
