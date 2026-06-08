CREATE TABLE IF NOT EXISTS main.storage_access_events (
    id          BIGINT NOT NULL,
    key         VARCHAR(1024) NOT NULL,
    owner_id    VARCHAR(255) NOT NULL,
    size_bytes  BIGINT,
    accessed_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (id)
);

CREATE INDEX idx_storage_access_events_owner
    ON main.storage_access_events(owner_id, accessed_at);
