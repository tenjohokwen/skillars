-- ============================================================
-- Story 6.6: Player Video Management Portal
-- ============================================================

-- Parental approval requests for minor-uploaded videos.
-- V59 created a stub table with only (id, video_id, status).
-- For DBs where V59 already ran: CREATE TABLE below is a no-op; ALTER TABLE adds missing columns.
-- For fresh DBs: CREATE TABLE creates the full schema; ALTER TABLE / DO blocks are no-ops.
-- video_id FK cascades on delete so the approval row is cleaned up with the video.
-- player_id and parent_id are Long TSIDs matching the BaseEntity ID type used throughout the platform.
CREATE TABLE IF NOT EXISTS main.video_approval_requests (
    id          UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    video_id    UUID        NOT NULL REFERENCES main.videos(id) ON DELETE CASCADE,
    player_id   BIGINT      NOT NULL,
    parent_id   BIGINT      NOT NULL,
    status      VARCHAR(16) NOT NULL DEFAULT 'PENDING'
                    CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED', 'CANCELLED')),
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    resolved_at TIMESTAMPTZ NULL
);

-- Augment V59 stub columns (ADD COLUMN IF NOT EXISTS is a no-op on fresh DBs where CREATE TABLE ran above)
ALTER TABLE main.video_approval_requests
    ADD COLUMN IF NOT EXISTS player_id   BIGINT      NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS parent_id   BIGINT      NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    ADD COLUMN IF NOT EXISTS resolved_at TIMESTAMPTZ;

-- Remove temporary defaults — table is always empty at this migration point (new feature)
ALTER TABLE main.video_approval_requests
    ALTER COLUMN player_id  DROP DEFAULT,
    ALTER COLUMN parent_id  DROP DEFAULT,
    ALTER COLUMN created_at DROP DEFAULT;

-- Add video_id FK if V59 stub omitted it (fresh DBs already have it from CREATE TABLE above)
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint
        WHERE conname = 'video_approval_requests_video_id_fkey'
          AND conrelid = 'main.video_approval_requests'::regclass
    ) THEN
        ALTER TABLE main.video_approval_requests
            ADD CONSTRAINT video_approval_requests_video_id_fkey
            FOREIGN KEY (video_id) REFERENCES main.videos(id) ON DELETE CASCADE;
    END IF;
END$$;

-- Add status CHECK if V59 stub omitted it
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint
        WHERE conname = 'chk_var_status'
          AND conrelid = 'main.video_approval_requests'::regclass
    ) THEN
        ALTER TABLE main.video_approval_requests
            ADD CONSTRAINT chk_var_status
            CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED', 'CANCELLED'));
    END IF;
END$$;

CREATE INDEX IF NOT EXISTS idx_var_video_id
    ON main.video_approval_requests(video_id);

CREATE INDEX IF NOT EXISTS idx_var_player_status
    ON main.video_approval_requests(player_id, status) WHERE status = 'PENDING';

CREATE INDEX IF NOT EXISTS idx_var_parent_status
    ON main.video_approval_requests(parent_id, status) WHERE status = 'PENDING';

-- Prevents duplicate PENDING approval rows for the same video
CREATE UNIQUE INDEX IF NOT EXISTS idx_var_unique_pending
    ON main.video_approval_requests(video_id) WHERE status = 'PENDING';

-- Extend operational_state CHECK to include REJECTED.
-- Lists all 11 states explicitly so no previously-added value is dropped.
ALTER TABLE main.videos
    DROP CONSTRAINT IF EXISTS chk_videos_operational_state;

ALTER TABLE main.videos
    ADD CONSTRAINT chk_videos_operational_state CHECK (
        operational_state IN (
            'UPLOADING',
            'PROCESSING',
            'SCANNING',
            'TRANSCODING',
            'READY',
            'LOCKED',
            'HIDDEN',
            'REJECTED',     -- parental approval rejected; video invisible to player
            'FAILED',
            'DELETED',
            'PURGED'
        )
    );

-- Approval notification feature flag and auto-reject placeholder
INSERT INTO main.platform_config (id, key, value, value_type, description) VALUES
(160, 'platform.video.approval.notification_enabled', 'true', 'STRING',
     'Controls whether VideoApprovalParentNotificationEvent is published when a minor-gate approval is created'),
-- WARNING: auto_reject_days is not yet active — see Story backlog
(161, 'platform.video.approval.auto_reject_days', '7', 'STRING',
     'Days until a PENDING approval would auto-reject. WARNING: not yet active — no scheduler wired. See Story backlog.')
ON CONFLICT (key) DO NOTHING;
