-- Story Deferred-75 AC10: V39 seeded these 20 drills with gen_random_uuid(), so every environment has a
-- different id for "the same" platform drill. V39 cannot be edited (already applied everywhere), so this
-- reassigns each seed drill's id to a fixed, deterministic value, matched by its unique trans_key (stable
-- since V39, UNIQUE-constrained). Guarded: aborts the whole migration if any drill_video_refs or
-- homework_assignments row already references one of these 20 drills under its current (random) id in
-- this environment -- reassigning under a live reference would silently orphan it. If this guard fires in
-- a real environment, that environment needs a hand-written follow-up migrating the specific references
-- found, not a blind re-run of this file.

DO $$
DECLARE
    ref_count INT;
BEGIN
    SELECT COUNT(*) INTO ref_count
    FROM session.drills d
    WHERE d.library_type = 'PLATFORM' AND d.trans_key LIKE 'sessDrill.%'
      AND (
        EXISTS (SELECT 1 FROM session.drill_video_refs r WHERE r.drill_id = d.id)
        OR EXISTS (SELECT 1 FROM session.homework_assignments h WHERE h.drill_id = d.id)
      );
    IF ref_count > 0 THEN
        RAISE EXCEPTION 'V111 aborted: % of the 20 V39 seed drills already have a live drill_video_refs or homework_assignments reference in this environment. Deterministic-id reassignment would orphan it -- write a hand-migration for this environment instead of re-running this file.', ref_count;
    END IF;
END $$;

UPDATE session.drills SET id = '00000000-0000-4000-8000-000000000001' WHERE trans_key = 'sessDrill.toeTaps';
UPDATE session.drills SET id = '00000000-0000-4000-8000-000000000002' WHERE trans_key = 'sessDrill.insideOutsideRoll';
UPDATE session.drills SET id = '00000000-0000-4000-8000-000000000003' WHERE trans_key = 'sessDrill.lShapeMastery';
UPDATE session.drills SET id = '00000000-0000-4000-8000-000000000004' WHERE trans_key = 'sessDrill.foundationJugglingSequence';
UPDATE session.drills SET id = '00000000-0000-4000-8000-000000000005' WHERE trans_key = 'sessDrill.coneSlalomBallMastery';
UPDATE session.drills SET id = '00000000-0000-4000-8000-000000000006' WHERE trans_key = 'sessDrill.staticFinishInsideFoot';
UPDATE session.drills SET id = '00000000-0000-4000-8000-000000000007' WHERE trans_key = 'sessDrill.turnAndShoot';
UPDATE session.drills SET id = '00000000-0000-4000-8000-000000000008' WHERE trans_key = 'sessDrill.shootingOnTheMove';
UPDATE session.drills SET id = '00000000-0000-4000-8000-000000000009' WHERE trans_key = 'sessDrill.oneVOneFinishingUnderPressure';
UPDATE session.drills SET id = '00000000-0000-4000-8000-000000000010' WHERE trans_key = 'sessDrill.volleyFinishing';
UPDATE session.drills SET id = '00000000-0000-4000-8000-000000000011' WHERE trans_key = 'sessDrill.cruyffTurn';
UPDATE session.drills SET id = '00000000-0000-4000-8000-000000000012' WHERE trans_key = 'sessDrill.stepOverToAccelerate';
UPDATE session.drills SET id = '00000000-0000-4000-8000-000000000013' WHERE trans_key = 'sessDrill.elasticoFlipFlap';
UPDATE session.drills SET id = '00000000-0000-4000-8000-000000000014' WHERE trans_key = 'sessDrill.pressureEscapeRondo3v1';
UPDATE session.drills SET id = '00000000-0000-4000-8000-000000000015' WHERE trans_key = 'sessDrill.bodyFeintAndDrive';
UPDATE session.drills SET id = '00000000-0000-4000-8000-000000000016' WHERE trans_key = 'sessDrill.passAndMove2Touch';
UPDATE session.drills SET id = '00000000-0000-4000-8000-000000000017' WHERE trans_key = 'sessDrill.wallPassOneTwoCombination';
UPDATE session.drills SET id = '00000000-0000-4000-8000-000000000018' WHERE trans_key = 'sessDrill.switchOfPlayFromCentral';
UPDATE session.drills SET id = '00000000-0000-4000-8000-000000000019' WHERE trans_key = 'sessDrill.thirdManRunCombination';
UPDATE session.drills SET id = '00000000-0000-4000-8000-000000000020' WHERE trans_key = 'sessDrill.receivingInTheHalfTurn';
