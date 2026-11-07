-- skillars-deferred-174 (RS06b, RS06e): stuck session-pack purchase attempts.
--
-- closed_by / close_reason: who closed an attempt with the admin close action (POST
-- /api/admin/payment/pack-attempts/{id}/close) and why. The status itself is still one of the six statuses chk_sppa_status
-- allows: the action closes only to what Stripe supports (SUCCEEDED, REFUNDED, NO_CHARGE), so no CHECK widening (rule 5).
-- needs_operator_since: set once by the resolving job when only an operator can close the row (tier gone, or an
-- unreadable charge / a failed pack record past payment.packAttempt.operatorAfterMinutes); the job then skips it.
--
-- Migration conventions (docs/deployment/migration-conventions.md):
-- Lock (rule 9): ALTER TABLE takes ACCESS EXCLUSIVE on payment.session_pack_purchase_attempts for a catalog-only change
-- (three nullable columns, no default, no rewrite, no scan). The online alternative (none needed) was not used: the
-- table is small (one row per purchase attempt, terminal rows purged after 30 days) and lock_timeout (rule 7) bounds the
-- wait behind the resolving job.
-- Rolling deploy (rule 1): the code that reads and writes these columns ships in the same release, as V221 did. The
-- columns are nullable and the old code never names them, so old instances keep working against the new schema.
SET LOCAL lock_timeout = '5s';

ALTER TABLE payment.session_pack_purchase_attempts
    ADD COLUMN IF NOT EXISTS closed_by bigint,
    ADD COLUMN IF NOT EXISTS close_reason varchar(500),
    ADD COLUMN IF NOT EXISTS needs_operator_since timestamp with time zone;
