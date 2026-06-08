-- V22: Add version column for JPA @Version optimistic locking on TenantApiKey (AKEY-09)
-- Pattern follows V21 precedent: pair main table column with tenant_api_key_aud column for Envers parity.

ALTER TABLE main.tenant_api_key
    ADD COLUMN IF NOT EXISTS version BIGINT NOT NULL DEFAULT 0;

-- Envers AUD table mirrors the base schema. Pattern established by V21 when rotated_at was altered.
-- AUD column is nullable — Envers does not always populate every column on every revision.
ALTER TABLE main.tenant_api_key_aud
    ADD COLUMN IF NOT EXISTS version BIGINT;
