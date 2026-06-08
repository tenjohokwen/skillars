CREATE TABLE main.videos
(
    id                 UUID         NOT NULL DEFAULT gen_random_uuid() PRIMARY KEY,
    owner_id           VARCHAR      NOT NULL,
    provider           VARCHAR      NOT NULL,
    provider_asset_id  VARCHAR,
    operational_state  VARCHAR      NOT NULL,
    access_state       VARCHAR      NOT NULL,
    title              VARCHAR      NOT NULL,
    description        TEXT,
    duration_ms        BIGINT,
    storage_bytes      BIGINT,
    visibility         VARCHAR      NOT NULL CHECK (visibility IN ('PRIVATE', 'GROUP', 'UNLISTED')),
    created_at         TIMESTAMP    NOT NULL,
    updated_at         TIMESTAMP    NOT NULL
);
