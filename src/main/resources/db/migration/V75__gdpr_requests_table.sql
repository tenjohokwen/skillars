CREATE TABLE admin.gdpr_requests (
    id              UUID          PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id         BIGINT        NOT NULL,
    request_type    VARCHAR(10)   NOT NULL CHECK (request_type IN ('EXPORT','ERASURE')),
    status          VARCHAR(15)   NOT NULL DEFAULT 'PENDING'
                                  CHECK (status IN ('PENDING','PROCESSING','COMPLETED','FAILED')),
    created_at      TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    completed_at    TIMESTAMPTZ,
    download_url    VARCHAR(2048),
    expires_at      TIMESTAMPTZ
);

CREATE INDEX idx_gdpr_requests_user_id  ON admin.gdpr_requests(user_id);
CREATE INDEX idx_gdpr_requests_status   ON admin.gdpr_requests(status, created_at);

-- At most one PENDING or PROCESSING request per user per type (prevents TOCTOU race on duplicate check)
CREATE UNIQUE INDEX idx_gdpr_requests_unique_active
    ON admin.gdpr_requests(user_id, request_type)
    WHERE status IN ('PENDING','PROCESSING');

INSERT INTO main.platform_config (id, key, value, value_type, description, updated_at)
VALUES (516, 'gdpr.export.urlExpiryHours', '48', 'LONG',
        'Hours before a GDPR export download URL expires', NOW())
ON CONFLICT DO NOTHING;
