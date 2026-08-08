-- Hibernate Envers audit infrastructure.
-- Required because hibernate.ddl-auto=none — Envers will NOT auto-create these tables.
--
-- This migration originally also created main.tenant_aud and main.tenant_api_key_aud.
-- Those were removed with the tenant module. revinfo and revinfo_seq stay: they are shared
-- Envers infrastructure, not tenant-specific. Six entities are still @Audited (User,
-- Authority, SecKey, Secret, PersistentToken, VideoModerationScan), and V11 declares
-- foreign keys onto main.revinfo(rev) from every *_aud table it creates.

CREATE SEQUENCE IF NOT EXISTS main.revinfo_seq START WITH 1 INCREMENT BY 50;

CREATE TABLE IF NOT EXISTS main.revinfo (
    rev      INTEGER NOT NULL DEFAULT nextval('main.revinfo_seq'),
    revtstmp BIGINT,
    PRIMARY KEY (rev)
);
