-- Defence-in-depth format guard, mirroring this project's established convention of validating
-- external-provider-sourced string columns at the DB boundary. Real Stripe API responses always
-- prefix customer ids "cus_" (StripePaymentGateway.createStripeCustomer writes the Stripe SDK
-- Customer object's own .getId() verbatim, never an app-constructed string), so this only catches a
-- misconfigured/placeholder value written directly, not a live Stripe response shape.
-- Added NOT VALID: registers the constraint for all new/future writes immediately via a brief
-- ACCESS EXCLUSIVE lock, with no full-table scan of existing rows — this table's actual current row
-- count in any live environment was never independently verified (story-review Finding 1), so the
-- scan is deferred to V101's separate, lighter-locking migration instead of asserted safe here.
ALTER TABLE payment.stripe_customers
    ADD CONSTRAINT chk_stripe_customer_id_format CHECK (stripe_customer_id LIKE 'cus_%') NOT VALID;
