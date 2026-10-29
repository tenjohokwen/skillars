-- fix/payment-subscription-entitlements: subscription entitlement integrity.
--
-- 1. version: optimistic-lock backstop for payment.coach_subscriptions / payment.player_subscriptions
--    (mirrors V188). The persist paths now re-read the row under a pessimistic lock; @Version turns any
--    remaining stale full-row save into a loud ObjectOptimisticLockingFailureException (API -> 409,
--    webhook -> rollback + Stripe retry, scheduler -> per-item skip, retried next run).
-- 2. last_stripe_event_at: `created` of the newest Stripe event applied to the row, so a late-delivered
--    older customer.subscription.updated / invoice.* event can no longer overwrite newer state.
-- 3. status CHECKs widened to the non-entitled Stripe statuses SubscriptionService.normalizeStripeStatus
--    now persists (INCOMPLETE, UNPAID, PAUSED). Before this, a customer.subscription.updated carrying
--    `unpaid`/`paused`/`incomplete` violated chk_pcs_status/chk_pps_status, rolled back the webhook and
--    put Stripe into a retry loop while the paid tier stayed granted.
--
-- Migration conventions (docs/deployment/migration-conventions.md):
--  - Rule 1 (additive): ADD COLUMN ... DEFAULT is metadata-only on Postgres 11+; old code never writes
--    the new columns. Widening a CHECK only admits more values, so old code stays valid.
--  - Rule 7: lock_timeout so the brief ACCESS EXCLUSIVE lock fails fast.
SET LOCAL lock_timeout = '5s';

ALTER TABLE payment.coach_subscriptions
    ADD COLUMN IF NOT EXISTS version bigint NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS last_stripe_event_at timestamp with time zone;

ALTER TABLE payment.player_subscriptions
    ADD COLUMN IF NOT EXISTS version bigint NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS last_stripe_event_at timestamp with time zone;

-- Drop + re-add in one statement so there is no window without a status CHECK. NOT VALID (rule 3):
-- the widened set is a strict superset of the old one, so every existing row already satisfies it and
-- the scan would prove nothing; new writes are checked immediately either way.
ALTER TABLE payment.coach_subscriptions
    DROP CONSTRAINT IF EXISTS chk_pcs_status,
    ADD CONSTRAINT chk_pcs_status CHECK (status IN
        ('ACTIVE', 'PAST_DUE', 'CANCELLED', 'TRIALLING', 'INCOMPLETE', 'UNPAID', 'PAUSED')) NOT VALID;

ALTER TABLE payment.player_subscriptions
    DROP CONSTRAINT IF EXISTS chk_pps_status,
    ADD CONSTRAINT chk_pps_status CHECK (status IN
        ('ACTIVE', 'PAST_DUE', 'CANCELLED', 'TRIALLING', 'INCOMPLETE', 'UNPAID', 'PAUSED')) NOT VALID;
