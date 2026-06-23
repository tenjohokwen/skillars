-- Lifecycle columns for the post-subscription-expiry retention window
ALTER TABLE main.videos ADD COLUMN lifecycle_locked_at TIMESTAMPTZ NULL;
ALTER TABLE main.videos ADD COLUMN archived_at TIMESTAMPTZ NULL;

-- Guard: a BLOCKED video must always carry lifecycle_locked_at.
-- NULL < threshold is NULL in SQL, so a BLOCKED row without lifecycle_locked_at
-- is silently skipped by the scheduler query forever.
ALTER TABLE main.videos ADD CONSTRAINT chk_lifecycle_locked_at_when_blocked
    CHECK (access_state != 'BLOCKED' OR lifecycle_locked_at IS NOT NULL);

-- Append-only audit log for all lifecycle state transitions
CREATE TABLE main.video_lifecycle_log (
    id              UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    video_id        UUID         NOT NULL REFERENCES main.videos(id),
    from_state      VARCHAR(64)  NOT NULL,
    to_state        VARCHAR(64)  NOT NULL,
    triggered_by    VARCHAR(64)  NOT NULL DEFAULT 'SYSTEM',
    transitioned_at TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX idx_vllog_video_id        ON main.video_lifecycle_log(video_id);
CREATE INDEX idx_vllog_transitioned_at ON main.video_lifecycle_log(transitioned_at DESC);

-- At-least-once outbox for subscription expiry lifecycle events.
-- The event handler writes a PENDING row; a @Scheduled processor drains it.
CREATE TABLE main.subscription_lifecycle_outbox (
    id                UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    subscriber_id     UUID         NOT NULL,
    subscription_tier VARCHAR(32)  NOT NULL,
    expired_at        TIMESTAMPTZ  NOT NULL,
    status            VARCHAR(16)  NOT NULL DEFAULT 'PENDING',
    attempts          INT          NOT NULL DEFAULT 0,
    last_error        TEXT,
    processed_at      TIMESTAMPTZ,
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX idx_slo_status_created ON main.subscription_lifecycle_outbox(status, created_at);

-- Lifecycle and playback config seeds
INSERT INTO main.platform_config (id, key, value, value_type, description) VALUES
(137, 'platform.video.lifecycle.blocked_to_archived_days',  '30',    'LONG',    'Days a BLOCKED video waits before transitioning to ARCHIVED'),
(138, 'platform.video.lifecycle.archived_to_deleted_days',  '90',    'LONG',    'Days an ARCHIVED video waits before physical deletion'),
(139, 'platform.video.lifecycle.batch_size',                '100',   'LONG',    'Lifecycle scheduler batch size per phase run'),
(140, 'platform.video.playback.signed_url_ttl_minutes',     '120',   'LONG',    'Signed HLS URL TTL in minutes (default 2 hours)'),
(141, 'platform.video.playback.ip_binding_enabled',         'false', 'STRING',  'Bind signed playback URLs to the requestor client IP'),
(142, 'platform.video.lifecycle.outbox_max_attempts',       '5',     'LONG',    'Max retry attempts for subscription lifecycle outbox before dead-letter')
ON CONFLICT (key) DO NOTHING;
