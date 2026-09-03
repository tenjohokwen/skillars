-- skillars-deferred-91 code review (2026-09-03), decision D6: outbox retry backoff.
--
-- V123 shipped the outbox with no backoff: claimNextChunk had no predicate, so a row whose handler
-- fails deterministically (a removed EmailTemplate, an aggregate_type with no registered handler on
-- this instance, a malformed payload) was re-claimed on EVERY drain and every 5-minute sweep,
-- forever. ORDER BY attempts ASC only demotes it relative to FRESHER work, so with fewer than
-- CHUNK_SIZE rows in the table a poison row is in every single chunk.
--
-- next_attempt_at makes the retry schedule explicit: OutboxRowProcessor.recordFailure stamps
-- now() + backoff(attempts) (30s doubling, capped at 1h) and claimNextChunk filters on it. A row is
-- still NEVER dropped — the [OUTBOX_STUCK] ERROR at STUCK_ATTEMPTS_THRESHOLD is unchanged; it just
-- stops starving fresh work while it waits for a human.
--
-- Expand/contract: additive ADD COLUMN with a constant DEFAULT. On PostgreSQL 11+ a non-volatile
-- DEFAULT is stored in the catalog, so this is metadata-only — no table rewrite, no long lock —
-- even though outbox_messages is a hot, high-churn table. NOT NULL is safe for the same reason.
-- now() is STABLE (evaluated once per statement), not VOLATILE, so it qualifies.
ALTER TABLE main.outbox_messages
    ADD COLUMN next_attempt_at timestamptz NOT NULL DEFAULT now();

-- The claim predicate is (next_attempt_at <= now()) ORDER BY attempts, id. Indexing it keeps the
-- claim a cheap index scan once the table carries a backlog.
-- migration-lint: allow-blocking-index no production system exists; main.outbox_messages is empty
-- at deploy, and this codebase runs Flyway migrations in a transaction so CREATE INDEX CONCURRENTLY
-- is not available (see V121's identical documented tradeoff for uq_pot_one_active_per_user).
CREATE INDEX idx_outbox_claim ON main.outbox_messages (next_attempt_at, attempts, id);
