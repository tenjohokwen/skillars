-- skillars-deferred-145 code review (D1, 2026-10-06): `sinceAfter` (AC4, ReviewSubmissionService's
-- updateReview) anchors the "new qualifying session since last review" bound on
-- coach_reviews.last_modified_at, but that column has three non-author writers --
-- AdminReviewService.approveReview (:103), AdminReviewService.blockReview (:145) and
-- ReviewFlagService's auto-hold (:163) -- none of which is an author edit. A moderation action
-- between two genuine author edits therefore retroactively voids an already-earned qualifying
-- session; any three users flagging a review can re-trigger it indefinitely.
--
-- Owner decision: add a dedicated author_last_edited_at column, written only from updateReview,
-- and anchor sinceAfter to it instead. last_modified_at itself keeps anchoring the cooldown --
-- a moderation action legitimately delaying the next edit is acceptable; silently destroying
-- earned session credit is not.
--
-- Additive, nullable ADD COLUMN per the expand/contract standard (migration-conventions.md rule
-- 1) -- no code reads it until this story's own ReviewSubmissionService change ships alongside.
--
-- BACKFILL (round-2 code review, R1): seeding every pre-existing row from last_modified_at would
-- re-inject into the new column exactly the contamination the column exists to escape -- a review
-- approved by an admin after its author's last edit would come out of this migration already
-- carrying the admin's timestamp, and the author's first edit would then 403 as
-- reviews.noQualifyingSession for a session they had already earned. moderation_epoch is an exact
-- discriminator for the population that matters: setModerationEpoch appears exactly once in all of
-- src/main (ReviewSubmissionService.updateReview) and submitReview leaves the column at its 0
-- default, so moderation_epoch = 0 means "never author-edited" and created_at is then the precise
-- answer -- that row has no author edit to point at. For a row that HAS been edited,
-- last_modified_at remains the best available approximation; it can still post-date the real last
-- author edit when a moderation action landed afterwards, and no audit trail exists to do better.
-- That residual is accepted and documented rather than silently papered over.
--
-- Single-transaction backfill (not the executeInTransaction=false sidecar): the predicate below
-- matches EVERY row on this migration's only real execution (ADD COLUMN leaves them all NULL), so
-- the UPDATE is bounded by table size rather than by its WHERE clause -- which is fine here only
-- because reviews.coach_reviews holds one row per author/coach pair. Stated explicitly because
-- migration-conventions.md:181-183 reserves "does the present WHERE actually bound anything" for
-- human review rather than the linter.
SET LOCAL lock_timeout = '5s';

ALTER TABLE reviews.coach_reviews
    ADD COLUMN IF NOT EXISTS author_last_edited_at timestamp with time zone;

UPDATE reviews.coach_reviews
SET author_last_edited_at = CASE WHEN moderation_epoch = 0 THEN created_at ELSE last_modified_at END
WHERE author_last_edited_at IS NULL;
