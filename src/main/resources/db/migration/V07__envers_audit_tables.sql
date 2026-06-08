-- Hibernate Envers audit infrastructure for Tenant and TenantApiKey
-- Required because hibernate.ddl-auto=none — Envers will NOT auto-create these tables

CREATE SEQUENCE IF NOT EXISTS main.revinfo_seq START WITH 1 INCREMENT BY 50;

CREATE TABLE IF NOT EXISTS main.revinfo (
    rev      INTEGER NOT NULL DEFAULT nextval('main.revinfo_seq'),
    revtstmp BIGINT,
    PRIMARY KEY (rev)
);

CREATE TABLE IF NOT EXISTS main.tenant_aud (
    id                 BIGINT NOT NULL,
    rev                INTEGER NOT NULL REFERENCES main.revinfo(rev),
    revtype            SMALLINT,
    tenant_ref         VARCHAR(36),
    name               VARCHAR(255),
    tenant_status      VARCHAR(20),
    webhook_url        VARCHAR(2048),
    webhook_secret     VARCHAR(255),
    key_prefix         VARCHAR(4),
    email              VARCHAR(255),
    status             VARCHAR(20),
    created_by         VARCHAR(50),
    created_date       TIMESTAMP,
    last_modified_by   VARCHAR(50),
    last_modified_date TIMESTAMP,
    request_id         VARCHAR(255),
    session_id         TEXT,
    PRIMARY KEY (id, rev)
);

CREATE TABLE IF NOT EXISTS main.tenant_api_key_aud (
    id                 BIGINT NOT NULL,
    rev                INTEGER NOT NULL REFERENCES main.revinfo(rev),
    revtype            SMALLINT,
    tenant_id          BIGINT,
    key_hash           VARCHAR(64),
    key_prefix         VARCHAR(8),
    key_status         VARCHAR(20),
    environment        VARCHAR(10),
    rotated_at         TIMESTAMP,
    status             VARCHAR(20),
    created_by         VARCHAR(50),
    created_date       TIMESTAMP,
    last_modified_by   VARCHAR(50),
    last_modified_date TIMESTAMP,
    request_id         VARCHAR(255),
    session_id         TEXT,
    PRIMARY KEY (id, rev)
);
