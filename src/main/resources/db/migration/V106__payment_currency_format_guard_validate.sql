-- Validates the NOT VALID constraint added in V105 against every existing row, under a SHARE
-- UPDATE EXCLUSIVE lock that does not block concurrent reads/writes — a separate migration
-- (separate transaction) from V105, mirroring V101's own reasoning exactly.
ALTER TABLE main.platform_config
    VALIDATE CONSTRAINT chk_payment_currency_format;
