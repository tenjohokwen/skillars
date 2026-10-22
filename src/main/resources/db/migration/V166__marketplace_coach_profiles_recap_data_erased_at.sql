-- skillars-12-7 code review (2026-10-22, Decision 1(a)): a sticky "this coach's recap text was erased"
-- tombstone, mirroring V151's player_profiles.development_data_erased_at. GdprErasureService locks the
-- coach's main."user" row in eraseTransactional's OUTER transaction (committed last) but scrubs the
-- recap copies in an inner REQUIRES_NEW transaction that commits first; a recap edit landing between
-- the two commits saw an unlocked user and wrote the scrubbed text back. This column is set inside
-- that same inner scrub transaction, so it becomes visible atomically with the scrub, and
-- BookingCompletionService.recapEditability checks it after taking the session_completion_data row lock.
--
-- Nullable, additive ADD COLUMN per the expand/contract standard (docs/deployment/
-- migration-conventions.md rule 1) -- no backfill: a coach erased before this migration already has a
-- locked main."user" row, which recapEditability still checks. Deliberately NOT mapped on the
-- CoachProfile entity: eraseTransactional's outer save(cp) flushes every mapped column at its commit,
-- AFTER the inner transaction set this one, and would overwrite it back to null.
SET LOCAL lock_timeout = '5s';

ALTER TABLE marketplace.coach_profiles
    ADD COLUMN IF NOT EXISTS recap_data_erased_at timestamp with time zone;
