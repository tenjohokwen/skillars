CREATE TABLE main.video_webhook_events (
    id                UUID        NOT NULL DEFAULT gen_random_uuid() PRIMARY KEY,
    event_id          VARCHAR     NOT NULL UNIQUE,
    event_type        VARCHAR     NOT NULL,
    provider_asset_id VARCHAR     NOT NULL,
    raw_payload       TEXT        NOT NULL,
    status            VARCHAR     NOT NULL CHECK (status IN ('PENDING', 'PROCESSING', 'COMPLETED', 'FAILED')),
    attempt_count     INTEGER     NOT NULL DEFAULT 0,
    error_message     TEXT,
    created_at        TIMESTAMP   NOT NULL DEFAULT now(),
    processed_at      TIMESTAMP
);

CREATE INDEX idx_video_webhook_events_status_created
    ON main.video_webhook_events (status, created_at);
