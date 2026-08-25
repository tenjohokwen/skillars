-- Deferred-64 AC6: upfront format guard for platform.payment.currency, mirroring V100/V101's
-- established NOT VALID + VALIDATE CONSTRAINT pattern for validating an external-provider-facing
-- config value at the DB boundary. main.platform_config is a generic key/value table shared by
-- many unrelated config keys, so the guard is scoped to only this one key's row via the `key !=`
-- escape, not a table-wide CHECK. Exactly 3 lowercase letters (ISO 4217's alphabetic form,
-- lowercase because V99's own seed comment already documents "lowercase, as Stripe's API expects").
-- Guards both a future migration AND today's live write path: ConfigResource.updateValue (admin-only
-- PUT /api/config/values/{key}) writes this key through ConfigService.updateConfig with no format
-- validation of its own. Added NOT VALID here for the same brief-ACCESS-EXCLUSIVE-lock,
-- no-full-table-scan reason V100 documents; the scan is deferred to V106.
ALTER TABLE main.platform_config
    ADD CONSTRAINT chk_payment_currency_format
    CHECK (key != 'platform.payment.currency' OR value ~ '^[a-z]{3}$') NOT VALID;
