-- feat/coach-workflow-gaps (D1): coach private drill create/edit/delete.
--
-- Deleting a PRIVATE drill is a soft delete (status = 'ARCHIVED'): session plans, session templates and
-- homework assignments reference drills by id, so the row must survive. The two partial unique indexes from
-- V138__baseline_schema.sql scoped uniqueness to every PRIVATE row regardless of status, so an archived drill
-- kept its name (and its clone slot) reserved forever: a coach who deleted "Rondo 4v1" could never create a
-- new "Rondo 4v1", and a coach who deleted their clone of a PLATFORM drill could never clone it again
-- (both the clone index and, since a clone inherits the source's name, the name index fired). Uniqueness is
-- now scoped to ACTIVE rows only, which is the rule the application actually means.
--
-- Same-release exception to the migration checklist ("index ships a release before code relies on it",
-- docs/deployment/migration-conventions.md): accepted because there is no production deployment, and the
-- narrower indexes only ever ADMIT rows the old ones rejected — old code running against the new schema is
-- unaffected. The new indexes are created before the old ones are dropped, so uniqueness among ACTIVE rows is
-- enforced continuously inside this migration's transaction.
SET LOCAL lock_timeout = '5s';

-- migration-lint: allow-blocking-index <reason: session.drills is tiny (20 PLATFORM seed rows plus each
-- coach's private drills, expected well under 100k rows; build is sub-second) and pre-production; CREATE
-- INDEX CONCURRENTLY has no working mechanism in this repo's Flyway runner (migration-conventions.md rule 4)>
CREATE UNIQUE INDEX IF NOT EXISTS idx_drills_coach_name_unique_active
    ON session.drills (owner_coach_id, name)
    WHERE library_type = 'PRIVATE' AND status = 'ACTIVE';

-- migration-lint: allow-blocking-index <reason: same small pre-production table as above>
CREATE UNIQUE INDEX IF NOT EXISTS idx_drills_clone_uniqueness_active
    ON session.drills (source_drill_id, owner_coach_id)
    WHERE source_drill_id IS NOT NULL AND status = 'ACTIVE';

DROP INDEX IF EXISTS session.idx_drills_coach_name_unique;

DROP INDEX IF EXISTS session.idx_drills_clone_uniqueness;
