CREATE TABLE main.tenant (
    id                  BIGINT PRIMARY KEY,
    tenant_ref          VARCHAR(36) UNIQUE NOT NULL,
    name                VARCHAR(255) NOT NULL,
    tenant_status       VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    status              VARCHAR(20) NOT NULL DEFAULT 'INACTIVE',
    created_by          VARCHAR(50),
    created_date        TIMESTAMP,
    last_modified_by    VARCHAR(50),
    last_modified_date  TIMESTAMP,
    request_id          VARCHAR(255),
    session_id          TEXT
);

CREATE TABLE main.tenant_api_key (
    id                  BIGINT PRIMARY KEY,
    tenant_id           BIGINT NOT NULL REFERENCES main.tenant(id),
    key_hash            VARCHAR(64) NOT NULL,
    key_prefix          VARCHAR(8)  NOT NULL,
    key_status          VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    status              VARCHAR(20) NOT NULL DEFAULT 'INACTIVE',
    environment         VARCHAR(10) NOT NULL DEFAULT 'LIVE',
    rotated_at          TIMESTAMP,
    created_by          VARCHAR(50),
    created_date        TIMESTAMP,
    last_modified_by    VARCHAR(50),
    last_modified_date  TIMESTAMP,
    request_id          VARCHAR(255),
    session_id          TEXT
);

CREATE INDEX idx_tenant_api_key_hash      ON main.tenant_api_key(key_hash);
CREATE INDEX idx_tenant_api_key_tenant_id ON main.tenant_api_key(tenant_id);
