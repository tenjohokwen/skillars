-- skillars-deferred-99 AC1: durable reconciliation record for a pack-purchase compensating refund
-- that could not be issued.
--
-- SessionPackPaymentService.purchasePack charges Stripe first, then persists the purchase row; if
-- that persist throws it issues a compensating refund. Before this migration, a refund that itself
-- failed was only log.error'd — the parent was charged, no purchase row existed, no refund landed,
-- and nothing in the database pointed an operator at the money to recover. This table is that
-- pointer. Rows are written by RefundReconciliationService in its own REQUIRES_NEW transaction, so
-- a write failure here surfaces loudly (wrapped as payment.lifecycleFailure) instead of being
-- swallowed.
--
-- payment_intent_id is UNIQUE: one purchase attempt == one PaymentIntent == at most one open
-- reconciliation row. A retry of the same attempt updates the existing row (attempts++, last_error)
-- rather than inserting a duplicate.
--
-- Expand/contract: additive CREATE TABLE only — no DROP, no FK (payment_intent_id is a Stripe
-- string with no local parent table; parent_id / pack_tier_id are recorded for the operator, not
-- enforced), no secondary index, no lock-taking DDL. First and only statement; passes
-- MigrationConventionLintTest.
CREATE TABLE payment.stripe_refund_failures (
    id                bigint      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    payment_intent_id text        NOT NULL UNIQUE,
    amount            numeric(10, 2) NOT NULL,
    parent_id         bigint      NOT NULL,
    pack_tier_id      uuid        NOT NULL,
    error             text,
    attempts          integer     NOT NULL DEFAULT 1,
    resolved_at       timestamptz,
    created_at        timestamptz NOT NULL DEFAULT now(),
    updated_at        timestamptz NOT NULL DEFAULT now()
);
