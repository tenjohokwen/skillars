-- skillars-deferred-169 (ledger RS05-RS09): three new tables, one per money-safety gap.
--
--  1. payment.session_pack_purchase_attempts   (RS06) one row per pack purchase attempt, written before the charge.
--     The partial unique index on open attempts IS the concurrency control: one open attempt per parent, so two
--     concurrent purchases (different keys, or one keyed and one keyless) cannot both reach Stripe.
--  2. payment.stripe_pending_subscription_events (RS05) a customer.subscription.updated/deleted that raced the
--     signup and found no local row is parked here and re-applied once the row exists. No FK: the webhook's own
--     marker row is uncommitted when the park runs.
--  3. payment.subscription_upgrade_reconciliations (RS07) a paid proration that could not be granted. FK-free on
--     purpose: the writer runs in REQUIRES_NEW while the outer transaction holds the subscription row lock, and an
--     FK to that locked row would self-deadlock.
--
-- Migration conventions (docs/deployment/migration-conventions.md): rule 1 says a new table ships a release before
-- any code uses it. This migration deliberately ships WITH its code (the lint does not enforce rule 1; deferred-167
-- and deferred-168 did the same) because the tables are brand new, empty and referenced only by the new code; an old
-- pod simply never touches them. Brand-new empty tables: constraints inline, nothing to validate; the indexes are
-- built on empty tables. Authored as V227, renumbered to V228 when the pitchside cherry-pick (#441) took V227;
-- skillars-deferred-170 also claims a number after this one and renumbers on merge.
SET LOCAL lock_timeout = '5s';

CREATE TABLE IF NOT EXISTS payment.session_pack_purchase_attempts (
    id                bigint      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    parent_id         bigint      NOT NULL,
    attempt_key       varchar(100) NOT NULL,
    pack_tier_id      uuid        NOT NULL,
    player_id         bigint      NOT NULL,
    previous_card     text,
    attempted_card    text,
    customer_existed  boolean     NOT NULL,
    payment_intent_id text,
    status            varchar(16) NOT NULL,
    created_at        timestamptz NOT NULL DEFAULT now(),
    updated_at        timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT chk_sppa_status CHECK (status IN
        ('IN_PROGRESS', 'SUCCEEDED', 'NO_CHARGE', 'REFUNDED', 'UNKNOWN', 'UNRECONCILED')),
    CONSTRAINT uq_sppa_parent_key UNIQUE (parent_id, attempt_key)
);

-- One OPEN attempt per parent (the concurrency control).
-- migration-lint: allow-blocking-index table created in this migration, empty.
CREATE UNIQUE INDEX IF NOT EXISTS uq_sppa_one_open_per_parent
    ON payment.session_pack_purchase_attempts (parent_id)
    WHERE status IN ('IN_PROGRESS', 'UNKNOWN', 'UNRECONCILED');

-- The job and the backlog gauge scan open rows by age; retention scans terminal rows by age.
-- migration-lint: allow-blocking-index table created in this migration, empty.
CREATE INDEX IF NOT EXISTS idx_sppa_status_created
    ON payment.session_pack_purchase_attempts (status, created_at);

CREATE TABLE IF NOT EXISTS payment.stripe_pending_subscription_events (
    id                     bigint      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    event_id               varchar(255) NOT NULL,
    stripe_subscription_id varchar(255) NOT NULL,
    event_type             varchar(64) NOT NULL,
    event_created          bigint,
    status                 varchar(64),
    current_period_end     bigint,
    cancel_at_period_end   boolean,
    created_at             timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uq_spse_event_id UNIQUE (event_id)
);

-- migration-lint: allow-blocking-index table created in this migration, empty.
CREATE INDEX IF NOT EXISTS idx_spse_subscription
    ON payment.stripe_pending_subscription_events (stripe_subscription_id, event_created);

CREATE TABLE IF NOT EXISTS payment.subscription_upgrade_reconciliations (
    id                     bigint      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    subscriber_kind        varchar(8)  NOT NULL,
    subscriber_ref         text        NOT NULL,
    stripe_subscription_id varchar(255) NOT NULL,
    target_tier            varchar(32) NOT NULL,
    latest_invoice_id      varchar(255),
    created_at             timestamptz NOT NULL DEFAULT now(),
    resolved_at            timestamptz,
    CONSTRAINT chk_sur_kind CHECK (subscriber_kind IN ('COACH', 'PLAYER'))
);

-- migration-lint: allow-blocking-index table created in this migration, empty.
CREATE INDEX IF NOT EXISTS idx_sur_unresolved
    ON payment.subscription_upgrade_reconciliations (created_at) WHERE resolved_at IS NULL;
