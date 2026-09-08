-- skillars-deferred-102 AC10: bound the VARCHAR length of stripe_webhook_events.event_id (the primary key).
-- Stripe event ids are evt_ + 24 base62 ≈ 28 chars; 255 is generous and matches other id columns.
--
-- varchar → varchar(255) is binary-coercible (same on-disk representation) so there is NO table
-- rewrite, but PostgreSQL still takes an ACCESS EXCLUSIVE lock and scans the table once to verify
-- every existing value fits in 255. At current row counts (one row per Stripe webhook, ~28 chars)
-- this is negligible; the bounded lock_timeout below keeps it safe under a rolling deploy anyway.

SET lock_timeout = '5s';

ALTER TABLE payment.stripe_webhook_events
    ALTER COLUMN event_id TYPE VARCHAR(255);

RESET lock_timeout;
