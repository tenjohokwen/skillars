CREATE TABLE main.video_moderation_scans (
    id             UUID         DEFAULT gen_random_uuid() PRIMARY KEY,
    video_id       UUID         NOT NULL REFERENCES main.videos(id) ON DELETE RESTRICT,
    layer          VARCHAR(20)  NOT NULL CHECK (layer IN ('ARACHNID', 'VIDEOINTEL', 'MINOR_GATE')),
    outcome        VARCHAR(20)  NOT NULL CHECK (outcome IN ('PASSED', 'FLAGGED', 'FAILED', 'SKIPPED')),
    confidence     NUMERIC(5,4) NULL,  -- VideoIntel confidence score (0.0–1.0); NULL for Arachnid/minor gate
    details        TEXT         NULL,  -- raw provider response excerpt for audit
    scanned_at     TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX idx_vms_video_id ON main.video_moderation_scans(video_id);
-- Idempotency: prevent duplicate scan records on retry for the same video/layer pair
CREATE UNIQUE INDEX idx_vms_video_layer ON main.video_moderation_scans(video_id, layer);

-- NOTE: video_approval_requests table is owned by Story 6.6.
-- Story 6.3 does NOT create this table — the minor player HIDDEN path is Story 6.6 scope.
-- Story 6.6's migration must include REFERENCES main.users(id) FK constraints on player_id and parent_id.
