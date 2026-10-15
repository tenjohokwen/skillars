-- skillars-12-3 AC1/AC2: player_timeline_events gains a nullable coach_id column so the
-- coach-access clock can read an attendance-gated "coach completed an attended session with
-- this player" signal directly off the timeline, not just the SLU-only proxy
-- (SluRepository.findLastSessionDate). Additive ADD COLUMN per the expand/contract standard
-- (migration-conventions.md rule 1). The index that makes the new column queryable lands in
-- its own migration (V161) so the blocking CREATE INDEX's SHARE lock is never held
-- concurrently with this backfill's row locks.
SET LOCAL lock_timeout = '5s';

ALTER TABLE development.player_timeline_events
    ADD COLUMN IF NOT EXISTS coach_id uuid;

-- migration-lint: allow-full-table-dml <reason: pre-production table, cardinality not yet
-- measured (see deferred-work.md's open pagination item on this same table); this project has
-- no production deployment yet, so there is no real traffic a full-table UPDATE could stall>
UPDATE development.player_timeline_events
    SET coach_id = (metadata->>'coachId')::uuid
    WHERE coach_id IS NULL AND metadata ? 'coachId';
