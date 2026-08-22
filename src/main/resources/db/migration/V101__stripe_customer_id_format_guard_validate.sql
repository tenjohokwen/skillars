-- Validates the NOT VALID constraint added in V100 against every existing row, under a SHARE UPDATE
-- EXCLUSIVE lock that does not block concurrent reads/writes — deliberately a separate migration
-- (separate transaction) from V100, so V100's brief ACCESS EXCLUSIVE lock is not held for this
-- scan's duration.
ALTER TABLE payment.stripe_customers
    VALIDATE CONSTRAINT chk_stripe_customer_id_format;
