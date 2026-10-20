-- skillars-deferred-155 AC4: the player/parent-initiated session-request flag ("work on X next
-- time"), surfaced on the coach's Session Prep Card. Additive, nullable ADD COLUMN per the
-- expand/contract standard (migration-conventions.md rule 1) -- no backfill is possible (there is
-- no prior data to reconstruct this from), mirroring the existing `notes` column's own precedent on
-- this same table and V159's identical reasoning for booking.session_completion_data.
SET LOCAL lock_timeout = '5s';

ALTER TABLE booking.bookings
    ADD COLUMN IF NOT EXISTS player_request varchar(500);
