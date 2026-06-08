ALTER TABLE main.tenant
    ADD COLUMN IF NOT EXISTS webhook_url    VARCHAR(2048),
    ADD COLUMN IF NOT EXISTS webhook_secret VARCHAR(255);
COMMENT ON COLUMN main.tenant.webhook_url    IS 'Tenant outbound webhook delivery URL — nullable';
COMMENT ON COLUMN main.tenant.webhook_secret IS 'HMAC-SHA256 signing key for outbound payload — nullable';
