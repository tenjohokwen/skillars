-- C5 (fix/optimistic-versions-and-quota-lock): optimistic-lock version columns for
-- marketplace.coach_profiles and session.sessions.
--
-- Neither entity had @Version, so two concurrent full-row saves (Hibernate writes every mapped column)
-- could silently revert each other: e.g. GdprErasureService's coach scrub (an unlocked findByUserId +
-- save) racing a suspension/strike status write, or a stale Session snapshot racing the booking-completion
-- listener. With the column + @Version, the losing save's UPDATE ... WHERE version = ? matches zero rows
-- and fails loudly (ObjectOptimisticLockingFailureException -> ApiAdvice 409) instead of winning.
--
-- Migration conventions (docs/deployment/migration-conventions.md):
--  - Rule 1 (additive): ADD COLUMN ... NOT NULL DEFAULT 0 is a metadata-only change on Postgres 11+
--    (no table rewrite, no backfill): every existing row reads version 0, which is exactly what a freshly
--    loaded entity expects. Old code still running during a rolling deploy simply never writes the column
--    and inserts get the default, so no expand/contract split is needed.
--  - Rule 7: lock_timeout so the brief ACCESS EXCLUSIVE lock fails fast instead of queueing behind a
--    long transaction (the coach row lock is held by many request paths).
SET LOCAL lock_timeout = '5s';

ALTER TABLE marketplace.coach_profiles
    ADD COLUMN IF NOT EXISTS version bigint NOT NULL DEFAULT 0;

ALTER TABLE session.sessions
    ADD COLUMN IF NOT EXISTS version bigint NOT NULL DEFAULT 0;
