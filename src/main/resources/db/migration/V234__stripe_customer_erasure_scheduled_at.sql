-- skillars-deferred-177 (RS03i): payment.stripe_customers.erasure_scheduled_at, set by
-- StripeCustomerErasureOutboxSupport.scheduleErasure when it enqueues the Stripe-side erasure. StripeCustomerErasureSweep
-- schedules the erasure for any row whose user carries the GDPR tombstone but whose column is still NULL: the row of a
-- customer created while its erasure was in flight, whose one late scheduling attempt failed.
--
-- No backfill, on purpose: rows of users erased before this release read NULL, so the first sweep runs (bounded per run)
-- re-enqueue their Stripe erasure once. The handler is idempotent (a customer Stripe no longer has is a no-op), and this
-- also catches any earlier erasure that never reached Stripe.
--
-- Migration conventions (docs/deployment/migration-conventions.md):
-- Lock (rule 9): ALTER TABLE takes ACCESS EXCLUSIVE on payment.stripe_customers for a catalog-only change (one nullable
-- column, no default, no rewrite, no scan). The table is small (one row per payer) and lock_timeout (rule 7) bounds the
-- wait behind card writes. No index: the sweep reads the whole (small) table joined to main."user" once an hour.
-- Rolling deploy (rule 1): the code that writes and reads the column ships in this release. The column is nullable and
-- old instances never name it, so they keep working; a row an old instance schedules reads NULL and is re-enqueued once.
SET LOCAL lock_timeout = '5s';

ALTER TABLE payment.stripe_customers
    ADD COLUMN IF NOT EXISTS erasure_scheduled_at timestamp with time zone;
