-- Extend the CHECK constraint on videos.operational_state to include new pipeline states
ALTER TABLE main.videos
    DROP CONSTRAINT IF EXISTS chk_videos_operational_state;

ALTER TABLE main.videos
    ADD CONSTRAINT chk_videos_operational_state CHECK (
        operational_state IN (
            'UPLOADING', 'PROCESSING',
            'SCANNING',              -- under content moderation
            'TRANSCODING',           -- moderation passed, Bunny encoding in progress
            'READY',                 -- published and playable
            'LOCKED',                -- content violation (Arachnid/VideoIntel) or lifecycle lock
            'HIDDEN',                -- minor safety gate: awaiting parent approval (Story 6.6)
            'FAILED',                -- provider or pipeline failure
            'DELETED'                -- logically deleted
        )
    );

-- Track encoding completion separately so moderation can skip triggerTranscoding() if already done
ALTER TABLE main.videos
    ADD COLUMN encoding_completed_at TIMESTAMPTZ NULL;

-- Moderation in-flight lock: prevents SLA monitor from re-queuing a video actively being processed.
-- Set to now() + lock_timeout_minutes when moderation starts; SLA monitor skips videos where this is in the future.
ALTER TABLE main.videos
    ADD COLUMN moderation_lock_until TIMESTAMPTZ NULL;

-- Tracks when SCANNING started; used as the SLA clock (not updatedAt, which resets on any field write).
ALTER TABLE main.videos
    ADD COLUMN scanning_started_at TIMESTAMPTZ NULL;

-- Counts SLA monitor retry attempts; SLA monitor fails the video permanently once this reaches platform.moderation_max_retries.
ALTER TABLE main.videos
    ADD COLUMN moderation_retry_count INT NOT NULL DEFAULT 0;
