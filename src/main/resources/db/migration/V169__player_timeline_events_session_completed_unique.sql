-- skillars-deferred-162 AC7: at most one SESSION_COMPLETED timeline row per (player, booking).
-- TimelineEventListener.onBookingCompleted had no existence check, so a replayed BookingCompletedEvent appended
-- a second row and double-counted the session in the timeline, the roster and the coach-access clock. The
-- writer now inserts through PlayerTimelineRepository.insertSessionCompletedIfAbsent
-- (INSERT ... ON CONFLICT (player_id, reference_id) WHERE <the predicate below> DO NOTHING), whose conflict
-- target must match this index's predicate exactly.
--
-- Same-release exception to the migration checklist ("index ships a release before code relies on it",
-- docs/deployment/migration-conventions.md): the code needs this index in the same release. Accepted because
-- (1) there is no production deployment, so no old code runs against the new schema or vice versa, and
-- (2) ON CONFLICT without a matching unique index fails loudly ("there is no unique or exclusion constraint
-- matching the ON CONFLICT specification"), it never silently writes a duplicate.
--
-- First migration to dedupe before adding a unique index: without the DELETE the CREATE UNIQUE INDEX fails on
-- any dev/test database that already holds a replayed duplicate. Keeps the earliest (occurred_at, id) row.
SET LOCAL lock_timeout = '5s';

-- migration-lint: allow-full-table-dml <reason: pre-production table, no production deployment;
-- duplicates exist only in dev/test databases, and the scan is bounded to SESSION_COMPLETED rows>
DELETE FROM development.player_timeline_events dup
    USING development.player_timeline_events keep
    WHERE dup.event_type = 'SESSION_COMPLETED'
      AND dup.reference_id IS NOT NULL
      AND keep.event_type = 'SESSION_COMPLETED'
      AND keep.player_id = dup.player_id
      AND keep.reference_id = dup.reference_id
      AND (keep.occurred_at, keep.id) < (dup.occurred_at, dup.id);

-- migration-lint: allow-blocking-index <reason: pre-production table, no production deployment to protect
-- from the SHARE lock, and row count is small (one SESSION_COMPLETED row per completed booking); CREATE INDEX
-- CONCURRENTLY has no working mechanism in this repo's single-connection Flyway runner (migration-conventions.md
-- rule 4)>
CREATE UNIQUE INDEX IF NOT EXISTS uq_pte_session_completed_per_booking
    ON development.player_timeline_events (player_id, reference_id)
    WHERE event_type = 'SESSION_COMPLETED' AND reference_id IS NOT NULL;
