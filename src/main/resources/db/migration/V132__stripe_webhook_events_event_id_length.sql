-- Bound the VARCHAR length of stripe_webhook_events.event_id (primary key).
-- Stripe event ids are evt_ + 24 base62 ≈ 28 chars; 255 is generous and matches other id columns.
-- This is a metadata-only change: varchar → varchar(255) is binary-coercible, no table rewrite.
-- PostgreSQL still takes an ACCESS EXCLUSIVE lock and scans the table once to verify every
-- existing value fits in 255, but at current row counts (one per Stripe webhook, ~28 chars)
-- this is negligible. Added to rolling-deploy migration-unsafe list if linting flags it.

ALTER TABLE payment.stripe_webhook_events
  ALTER COLUMN event_id TYPE VARCHAR(255);
