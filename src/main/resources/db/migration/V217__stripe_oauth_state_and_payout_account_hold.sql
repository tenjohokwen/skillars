-- fix/security-payout-oauth-session-revocation:
--   1. payment.stripe_oauth_states: the single-use nonce behind the Stripe Connect OAuth `state`. Only a
--      SHA-256 of the nonce is stored. Rows are consumed by the callback (DELETE ... RETURNING-style, one
--      winner) or purged when expired / superseded by the coach's next onboarding link.
--   2. coach_stripe_accounts.payout_hold_until: set when a coach replaces an existing connected account
--      with a different one; CoachPayoutTransferHandler defers transfers until it has passed.
SET LOCAL lock_timeout = '5s';

-- New, empty table: constraints inline, nothing to validate (V148/V175/V186/V200 precedent).
CREATE TABLE IF NOT EXISTS payment.stripe_oauth_states (
    nonce_hash char(64)    NOT NULL PRIMARY KEY,
    coach_id   uuid        NOT NULL,
    expires_at timestamptz NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now()
);

-- migration-lint: allow-blocking-index new empty table, nothing to scan.
CREATE INDEX IF NOT EXISTS idx_sos_coach ON payment.stripe_oauth_states USING btree (coach_id);

ALTER TABLE payment.coach_stripe_accounts
    ADD COLUMN IF NOT EXISTS payout_hold_until timestamptz;
