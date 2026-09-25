-- skillars-deferred-136 AC2: GdprErasureService.markFailed (skillars-deferred-135 AC1) now reliably
-- alerts on a FAILED erasure, but nothing auto-retries it -- an operator has to notice the alert and
-- manually resubmit. This AC adds a scheduled sweep that re-drives eligible FAILED rows automatically.
--
-- Two columns, mirroring EmailRetryScheduler's own attempts/deadline shape (the closest existing
-- precedent in this codebase for "periodically re-attempt a failed durable operation"):
--   retry_count  -- how many times the sweep has already re-driven this row; bounds the retry cap.
--   failed_at    -- when this row most recently became FAILED; bounds the grace window. NOT
--                   createdAt (set once at construction, per-column comment on that field) -- a row
--                   created long ago that failed seconds ago would otherwise read as immediately
--                   eligible, with no settling time before its first automatic re-drive.
--
-- Both nullable/defaulted, additive ADD COLUMN per the expand/contract standard (docs/deployment/
-- migration-conventions.md rule 1) -- no backfill needed: a row already FAILED before this migration
-- has retry_count implicitly 0 (never auto-retried) and failed_at NULL (unknown historical failure
-- time) -- the sweep's own grace-window check treats a NULL failed_at as "not yet eligible" until the
-- next markFailed call sets it, rather than guessing a historical timestamp.
SET LOCAL lock_timeout = '5s';

ALTER TABLE admin.gdpr_requests
    ADD COLUMN IF NOT EXISTS retry_count integer NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS failed_at timestamp with time zone;
