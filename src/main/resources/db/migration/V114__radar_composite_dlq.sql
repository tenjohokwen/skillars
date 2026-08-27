-- Story Deferred-77 AC10 Phase 2: dead-letter queue for radar composite recalculations that fail
-- even after the transaction rolls back — without this, a failed @Async listener invocation leaves
-- the composite permanently stale until the player's next radar-entry submission (which may be
-- weeks away). Mirrors development.video_deletion_outbox's poll/claim/backoff shape (V59).

CREATE TABLE development.radar_composite_dlq (
    id             UUID          PRIMARY KEY DEFAULT gen_random_uuid(),
    player_id      BIGINT        NOT NULL,
    parent_id      BIGINT,
    skill_codes    VARCHAR(10)[] NOT NULL,
    status         VARCHAR(16)   NOT NULL DEFAULT 'PENDING',
    attempts       INT           NOT NULL DEFAULT 0,
    next_retry_at  TIMESTAMPTZ   NOT NULL DEFAULT now(),
    last_error     TEXT,
    created_at     TIMESTAMPTZ   NOT NULL DEFAULT now()
);

CREATE INDEX idx_radar_composite_dlq_status_retry ON development.radar_composite_dlq (status, next_retry_at);
