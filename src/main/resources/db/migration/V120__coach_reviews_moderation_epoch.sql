-- Story skillars-deferred-88 AC1: per-review moderation epoch, closing the
-- skillars-deferred-14 code-review D3 design gap defensively.
--
-- ReviewModerationService.handleReviewSubmitted runs the Gemini call outside any transaction, then
-- writes the verdict only if the row is still PENDING. ReviewSubmissionService.submitReview and
-- .updateReview are the only publishers of ReviewSubmittedEvent and both set PENDING immediately
-- before publishing, so when a review is edited again while the previous edit's Gemini call is still
-- in flight, both deliveries race the same PENDING status with nothing to tell them apart -- the
-- slower call (possibly evaluating already-overwritten body text) can land its verdict, and the
-- fresher delivery then discards itself. Unreachable today only because updateReview rejects any edit
-- within 365 days of the last one; a GDPR/admin re-publish path or a relaxed edit rule opens it.
--
-- moderation_epoch is a monotonic per-review counter. submitReview leaves it at 0; updateReview bumps
-- it under the row lock; ReviewModerationService discards any verdict whose event epoch != the row's
-- current epoch. The column is only ever read via the already-locked findByIdForUpdate row, so it
-- needs no index.
--
-- Migration shape: small table, additive, DEFAULT 0 backfills every existing row in one pass -- a
-- brief ACCESS EXCLUSIVE on a tiny table, same accepted class as V117 / V118. The codebase-wide
-- online-safe-migration convention item (skillars-deferred-84) stays open and is not in scope here.

ALTER TABLE reviews.coach_reviews
    ADD COLUMN moderation_epoch BIGINT NOT NULL DEFAULT 0;
