CREATE TABLE IF NOT EXISTS main.file_storage_objects (
    id                   BIGINT NOT NULL,
    key                  VARCHAR(1024) NOT NULL,
    owner_id             VARCHAR(255) NOT NULL,
    original_filename    VARCHAR(255),
    content_type         VARCHAR(255),
    size_bytes           BIGINT NOT NULL,
    checksum             VARCHAR(128),
    tags                 JSONB,
    provider             VARCHAR(50) NOT NULL,
    bucket               VARCHAR(255) NOT NULL,
    upload_confirmed_at  TIMESTAMPTZ,
    deleted_at           TIMESTAMPTZ,
    created_at           TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (id)
);

CREATE UNIQUE INDEX idx_storage_objects_key
    ON main.file_storage_objects(key);

CREATE INDEX idx_storage_objects_owner_id
    ON main.file_storage_objects(owner_id);

CREATE INDEX idx_storage_objects_deleted_at
    ON main.file_storage_objects(deleted_at);

CREATE TABLE IF NOT EXISTS main.outbox_replication_jobs (
    id                   BIGINT NOT NULL,
    storage_object_id    BIGINT NOT NULL,
    status               VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    job_type             VARCHAR(20) NOT NULL CHECK (job_type IN ('REPLICATE', 'DELETE')),
    attempt_count        INTEGER NOT NULL DEFAULT 0,
    last_attempted_at    TIMESTAMPTZ,
    error_message        TEXT,
    created_at           TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (id),
    CONSTRAINT fk_replication_job_storage_object
        FOREIGN KEY (storage_object_id) REFERENCES main.file_storage_objects(id)
);

CREATE INDEX idx_replication_jobs_status
    ON main.outbox_replication_jobs(status, created_at);

CREATE INDEX idx_replication_jobs_storage_object
    ON main.outbox_replication_jobs(storage_object_id);
