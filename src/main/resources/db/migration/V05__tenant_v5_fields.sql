ALTER TABLE main.tenant
    ADD COLUMN IF NOT EXISTS key_prefix VARCHAR(4)   NOT NULL DEFAULT 'UNK',
    ADD COLUMN IF NOT EXISTS email      VARCHAR(255);

-- Backfill existing tenant rows: derive prefix from name (uppercase, 0-padded to 3 chars)
UPDATE main.tenant SET key_prefix =
    CASE WHEN LENGTH(TRIM(name)) = 0 THEN 'UNK'
         WHEN LENGTH(TRIM(name)) = 1 THEN UPPER(SUBSTRING(TRIM(name), 1, 1)) || '00'
         WHEN LENGTH(TRIM(name)) = 2 THEN UPPER(SUBSTRING(TRIM(name), 1, 2)) || '0'
         ELSE UPPER(SUBSTRING(TRIM(name), 1, 3))
    END
WHERE key_prefix = 'UNK';

COMMENT ON COLUMN main.tenant.key_prefix IS 'Immutable 3-char prefix (uppercase, 0-padded) derived from tenant name at creation time';
COMMENT ON COLUMN main.tenant.email      IS 'Tenant notification email — optional; used for lifecycle event emails';
