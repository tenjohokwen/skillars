-- Extend CHECK constraint on videos.operational_state to include PURGED
ALTER TABLE main.videos
    DROP CONSTRAINT IF EXISTS chk_videos_operational_state;

ALTER TABLE main.videos
    ADD CONSTRAINT chk_videos_operational_state CHECK (
        operational_state IN (
            'UPLOADING', 'PROCESSING',
            'SCANNING',
            'TRANSCODING',
            'READY',
            'LOCKED',
            'HIDDEN',
            'FAILED',
            'DELETED',
            'PURGED'        -- logically deleted; physical Bunny deletion pending in video_deletion_outbox
        )
    );

-- Optimistic locking for concurrent deletion guard
ALTER TABLE main.videos ADD COLUMN IF NOT EXISTS version BIGINT NOT NULL DEFAULT 0;

-- Append-only audit log for video deletions (separate from video_lifecycle_log)
-- video_id is nullable so the audit row survives if the videos row is ever hard-deleted
CREATE TABLE IF NOT EXISTS main.video_deletion_log (
    id              UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    video_id        UUID         REFERENCES main.videos(id) ON DELETE SET NULL,
    deleted_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    triggered_by    VARCHAR(32)  NOT NULL CHECK (triggered_by IN ('USER_DELETION', 'ACCOUNT_DELETION', 'SYSTEM')),
    bunny_video_id  VARCHAR(255)
);

CREATE INDEX IF NOT EXISTS idx_vdlog_video_id ON main.video_deletion_log(video_id);

-- At-least-once outbox for physical Bunny.net deletion
-- UNIQUE index prevents duplicate PENDING rows from concurrent cascade listener re-fires
CREATE TABLE IF NOT EXISTS main.video_deletion_outbox (
    id              UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    video_id        UUID         NOT NULL,
    bunny_video_id  VARCHAR(255),
    status          VARCHAR(16)  NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING', 'CLAIMED', 'COMPLETED', 'FAILED', 'DEAD')),
    attempts        INT          NOT NULL DEFAULT 0,
    next_retry_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    last_error      TEXT,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    triggered_by    VARCHAR(32)  NOT NULL DEFAULT 'USER_DELETION' CHECK (triggered_by IN ('USER_DELETION', 'ACCOUNT_DELETION', 'SYSTEM'))
);

CREATE INDEX IF NOT EXISTS idx_vdoutbox_status_retry ON main.video_deletion_outbox(status, next_retry_at) WHERE status = 'PENDING';
CREATE INDEX IF NOT EXISTS idx_vdoutbox_status_claimed ON main.video_deletion_outbox(status) WHERE status = 'CLAIMED';
CREATE UNIQUE INDEX IF NOT EXISTS idx_vdoutbox_unique_pending ON main.video_deletion_outbox(video_id) WHERE status = 'PENDING';
CREATE INDEX IF NOT EXISTS idx_vdoutbox_video_id ON main.video_deletion_outbox(video_id);

-- Stub table for video approval requests (Story 6.6 adds full schema)
-- Required so VideoDeletionService can cancel pending approval records on deletion.
-- Cancellation is gated by platform.video.approvalCancellation.enabled (seeded false below).
CREATE TABLE IF NOT EXISTS main.video_approval_requests (
    id       UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    video_id UUID        NOT NULL,
    status   VARCHAR(16) NOT NULL DEFAULT 'PENDING'
);

-- ConfigService seed keys
INSERT INTO main.platform_config (id, key, value, value_type, description) VALUES
(143, 'platform.video.deletion.max_attempts',        '5',     'LONG',    'Max Bunny.net deletion attempts before outbox row transitions to DEAD'),
(144, 'platform.video.deletion.outbox_poll_delay_ms','60000', 'LONG',    'Outbox processor poll interval in milliseconds'),
(145, 'platform.video.access.coach_window_days',     '90',    'LONG',    'Days within which a coach with a completed booking can access a player video'),
(146, 'platform.video.approvalCancellation.enabled', 'true',  'STRING',  'Enable approval-request cancellation on video deletion (Story 6.6 deploys before or alongside Story 6.5)')
ON CONFLICT (key) DO NOTHING;
