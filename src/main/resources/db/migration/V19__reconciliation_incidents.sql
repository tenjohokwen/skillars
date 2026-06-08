CREATE TABLE main.reconciliation_incidents (
    id                UUID        NOT NULL DEFAULT gen_random_uuid() PRIMARY KEY,
    video_id          UUID        REFERENCES main.videos(id),
    incident_type     VARCHAR     NOT NULL CHECK (incident_type IN ('ORPHANED_ASSET', 'MISSING_ASSET', 'STATE_CORRECTED')),
    provider_asset_id VARCHAR,
    description       TEXT,
    resolved_at       TIMESTAMP,
    created_at        TIMESTAMP   NOT NULL DEFAULT now()
);
