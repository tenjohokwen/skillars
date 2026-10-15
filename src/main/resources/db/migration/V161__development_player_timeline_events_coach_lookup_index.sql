-- skillars-12-3 AC1: supports PlayerTimelineRepository's new
-- findTopByPlayerIdAndCoachIdAndEventTypeOrderByOccurredAtDesc query (the coach-scoped
-- "last attended SESSION_COMPLETED for this player" lookup CoachPlayerLastSessionResolver and
-- the new /prep endpoint both rely on). Additive — does not touch the existing
-- idx_player_timeline_events_player_id index at V138__baseline_schema.sql:3334.
SET LOCAL lock_timeout = '5s';

-- migration-lint: allow-blocking-index <reason: pre-production table, cardinality not yet
-- measured; CREATE INDEX CONCURRENTLY has no working online-safe mechanism in this repo's
-- single-connection Flyway runner yet (docs/deployment/migration-conventions.md rule 4) and
-- this project has no production deployment to protect from the lock>
CREATE INDEX IF NOT EXISTS idx_player_timeline_events_coach_lookup
    ON development.player_timeline_events USING btree (player_id, coach_id, event_type, occurred_at DESC);
