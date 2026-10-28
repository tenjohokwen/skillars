-- C1 (feat/video-minor-refusal-and-coach-video-subject): a coach-owned video can name the session and
-- the child it shows. Product decision 2026-10-28 ("Minors never upload"): a player who is a minor can
-- no longer upload at all, and a coach video of a child is linked to the booking + player it is about,
-- so that child's parent (and the player themself, if self-registered) can PLAY it.
--
--   subject_booking_id  booking.bookings.id the video is about (validated server-side at upload
--                       initiation: the booking belongs to the uploading coach)
--   subject_player_id   that booking's player_id - a PlayerProfile id, NOT a user id
--
-- Both NULL (no subject: every video uploaded before this release, every player HOMEWORK upload, a
-- DRILL_DEMO with no session) or both set. No FK: booking.* and main.player_profiles belong to other
-- modules, booking rows are never hard-deleted (GDPR anonymises them in place), and a GDPR child
-- erasure purges the linked videos instead (AccountDeletionCascadeListener: onAccountDeleted for a
-- PLAYER / PARENT account, onPlayerProfileErased for a parent-requested child erasure).
--
-- main.video_approval_requests is deliberately NOT dropped here: this release removes its last
-- reader/writer (VideoApprovalService), so per migration-conventions.md rule 2 the guarded DROP ships
-- in a LATER release with a `drop-prepared-in: V184` marker. Existing rows are left as they are.
--
-- main.envelope_entity.email_template CHECK is deliberately NOT rebuilt: the three VIDEO_APPROVAL_*
-- values stay in the list (and the EmailTemplate constants stay, @Deprecated) because envelope rows
-- carrying them may already be stored - removing a value a stored row holds would fail this migration,
-- and Hibernate could no longer load those rows.
--
-- Migration conventions (docs/deployment/migration-conventions.md):
--  - Rule 1: additive, nullable columns, no default - a metadata-only ALTER (brief ACCESS EXCLUSIVE).
--  - Rule 3 (NOT VALID / VALIDATE): skipped via marker - both columns are brand new, so every existing
--    row is NULL/NULL and satisfies the CHECK; the validating scan reads a small dev/uat-only table
--    (prod is not live, as V168/V182).
--  - Rule 4: plain CREATE INDEX via marker, same reasoning as V161.
--  - Rule 7: lock_timeout so the ALTER fails fast instead of queueing behind the video pollers
--    (ReconciliationWorkerScheduler, VideoLifecycleScheduler, ModerationSlaMonitorService).
SET LOCAL lock_timeout = '5s';

ALTER TABLE main.videos
    ADD COLUMN IF NOT EXISTS subject_booking_id uuid,
    ADD COLUMN IF NOT EXISTS subject_player_id bigint;

ALTER TABLE main.videos
    DROP CONSTRAINT IF EXISTS chk_videos_subject_pair,
    -- migration-lint: allow-validating-constraint brand-new columns (every row NULL/NULL passes); small
    -- dev/uat-only table, prod not live (as V168/V182).
    ADD CONSTRAINT chk_videos_subject_pair CHECK (
        (subject_booking_id IS NULL AND subject_player_id IS NULL)
        OR (subject_booking_id IS NOT NULL AND subject_player_id IS NOT NULL));

-- Serves VideoRepository.findBySubjectPlayerId... (parent/player list of coach videos about a child)
-- and the GDPR subject purge. Partial: only the few subject-linked rows are indexed.
-- migration-lint: allow-blocking-index <reason: main.videos is small (no production deployment yet, as
-- V161); partial index over the new, all-NULL column builds instantly; CREATE INDEX CONCURRENTLY has no
-- working mechanism in this Flyway runner (migration-conventions.md rule 4)>
CREATE INDEX IF NOT EXISTS idx_videos_subject_player
    ON main.videos USING btree (subject_player_id)
    WHERE subject_player_id IS NOT NULL;
