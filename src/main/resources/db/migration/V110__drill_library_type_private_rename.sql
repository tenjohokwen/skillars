-- Story Deferred-75 AC8: renames the internal library_type value 'COACH' to 'PRIVATE', aligning the
-- DB/domain-internal representation with the term already used everywhere external to this table (the
-- API's ?library=PRIVATE query param, the frontend's "My Library" PRIVATE tab, and DrillCard.vue's own
-- reuse-context reasoning from AC4). Two CHECK constraints reference the old value: library_type's own
-- inline check, confirmed via `SELECT conname FROM pg_constraint WHERE conrelid =
-- 'session.drills'::regclass AND contype = 'c'` against a live test DB to be Postgres's standard
-- auto-generated name "drills_library_type_check" for an unnamed single-column CHECK (verified at
-- dev-story time, 2026-08-27), and the explicitly-named chk_drill_owner.

UPDATE session.drills SET library_type = 'PRIVATE' WHERE library_type = 'COACH';

ALTER TABLE session.drills DROP CONSTRAINT drills_library_type_check;
ALTER TABLE session.drills ADD CONSTRAINT drills_library_type_check
    CHECK (library_type IN ('PLATFORM', 'PRIVATE'));

ALTER TABLE session.drills DROP CONSTRAINT chk_drill_owner;
ALTER TABLE session.drills ADD CONSTRAINT chk_drill_owner CHECK (
    (library_type = 'PLATFORM' AND owner_coach_id IS NULL) OR
    (library_type = 'PRIVATE'  AND owner_coach_id IS NOT NULL)
);

-- Found during dev-story implementation (2026-08-27), not in the original AC8 draft: V78's
-- idx_drills_coach_name_unique is a PARTIAL unique index scoped to "WHERE library_type = 'COACH'".
-- Left as-is after the UPDATE above, that predicate would match zero rows forever, silently
-- disabling coach-drill duplicate-name prevention entirely (a real regression, caught by
-- DrillLibraryResourceIT.cloneTwoDifferentDrillsWithSameName_secondReturns409 failing to 409).
-- V78 cannot be edited (already applied everywhere), so recreate the index here under its
-- unchanged name with the new predicate — mirroring V107/V108's drop-and-re-add-under-a-new-
-- migration-number convention for a constraint that already shipped in an applied migration.
DROP INDEX IF EXISTS session.idx_drills_coach_name_unique;
CREATE UNIQUE INDEX idx_drills_coach_name_unique
    ON session.drills(owner_coach_id, name)
    WHERE library_type = 'PRIVATE';
