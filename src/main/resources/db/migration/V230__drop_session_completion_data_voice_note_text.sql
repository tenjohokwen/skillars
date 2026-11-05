-- skillars-deferred-171 AC6: drop booking.session_completion_data.voice_note_text, step 3 of 3.
--
-- Step 1 (skillars-deferred-163 AC6.1) stopped BookingCompletionService reading the column. Step 2
-- (skillars-deferred-165 AC12, PR #304, released with V170) removed the JPA field. The two GDPR anonymise
-- queries in SessionCompletionDataRepository still nulled it by native SQL, because rows written before
-- skillars-12-7 stopped sending voice notes hold coach-authored free text in it. Those two queries stop naming
-- the column in the same change as this drop, and dropping it removes that legacy text everywhere at once.
--
-- migration-conventions.md rule 2 would hold this drop for a release after the one that removed every code
-- reference, so it is NOT met literally here: an old-release pod running the old anonymise query during the
-- migration window would fail with "column does not exist". Owner decision (2026-11-05, skillars-deferred-171,
-- D3), on the same ground as V229 and V142: no production deploy of this application has ever happened
-- (docs/deployment/migration-rebaseline.md), so there is no old release to protect. If production has gone live
-- by the time this ships, this migration must wait a release, and the anonymise queries keep nulling the column
-- until then. The marker below names V170, the release that removed the last JPA reader.
SET LOCAL lock_timeout = '5s';

-- migration-lint: drop-prepared-in: V170
ALTER TABLE booking.session_completion_data DROP COLUMN IF EXISTS voice_note_text;
