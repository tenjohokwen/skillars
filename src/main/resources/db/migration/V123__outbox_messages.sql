-- skillars-deferred-91 AC1: a generic durable transactional outbox, extracted from
-- skillars-deferred-90's pending_blob_deletions (V122) and its 3-layer-reviewed
-- PendingBlobDeletionService / …ChunkProcessor shape.
--
-- A producer writes a row here INSIDE its own business transaction and publishes an
-- OutboxDrainRequestedEvent in the same transaction; a @TransactionalEventListener(AFTER_COMMIT)
-- drainer plus a @Scheduled @SchedulerLock sweeper process it off the request path. Each row's
-- aggregate_type selects an idempotent handler that re-drives the operation (a post-commit Stripe
-- refund, a cancellation/expiry email, an SLU snapshot delta). attempts + last_error make a
-- post-commit failure re-drivable instead of silently lost; a row is NEVER dropped.
--
-- Expand/contract: additive CREATE TABLE only — no DROP, no FK to domain tables, no secondary index
-- at creation. V123 is above the V121 grandfather baseline and passes MigrationConventionLintTest.
CREATE TABLE main.outbox_messages (
    id             bigint      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    aggregate_type text        NOT NULL,
    payload        jsonb       NOT NULL,
    attempts       integer     NOT NULL DEFAULT 0,
    last_error     text,
    created_at     timestamptz NOT NULL DEFAULT now()
);
