-- Step 1: migrate existing LIVE rows to PROD (MUST precede CHECK constraint)
UPDATE main.tenant_api_key SET environment = 'PROD' WHERE environment = 'LIVE';

-- Step 2: update column default
ALTER TABLE main.tenant_api_key ALTER COLUMN environment SET DEFAULT 'PROD';

-- Step 3: CHECK constraint (after UPDATE — ordering is critical)
ALTER TABLE main.tenant_api_key
    ADD CONSTRAINT chk_api_key_environment
    CHECK (environment IN ('PROD', 'DEV', 'SANDBOX'));

-- Step 4: partial unique index — one ACTIVE key per tenant+environment (per AKEY-03)
CREATE UNIQUE INDEX IF NOT EXISTS uidx_tenant_api_key_active_env
    ON main.tenant_api_key (tenant_id, environment)
    WHERE key_status = 'ACTIVE';

-- Step 5: unique constraint on key_hash
ALTER TABLE main.tenant_api_key
    ADD CONSTRAINT uq_tenant_api_key_hash UNIQUE (key_hash);
