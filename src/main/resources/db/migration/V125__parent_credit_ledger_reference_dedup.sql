-- skillars-deferred-91 AC2: make credit-wallet refunds idempotent so
-- CancellationRefundService's BOOKING_REFUND listeners can be re-driven from the durable outbox
-- (skillars-deferred-91 AC1) without double-crediting the parent.
--
-- CreditWalletService.writeLedgerEntry INSERTs unconditionally (PK is a generated tx_id). This
-- partial unique index gives the reference-carrying ledger types a dedup key: a booking is
-- deducted once, reversed at most once, and refunded once (across the cancellation and dispute
-- paths alike). Rows with reference_id IS NULL (CASH_OUT_DEBIT / STRIPE_FEE_DEBIT /
-- CASH_OUT_REVERSAL) are unaffected. The CREDIT_WALLET_REFUND outbox handler also checks existence
-- before writing, so this index is the backstop against a pathological double-enqueue, not the
-- primary guard.
--
-- migration-lint: allow-blocking-index no production system exists; parent_credit_ledger is empty
-- at deploy, and this codebase runs Flyway migrations in a transaction so CREATE INDEX CONCURRENTLY
-- is not available (see V121's identical documented tradeoff for uq_pot_one_active_per_user).
CREATE UNIQUE INDEX uq_pcl_reference_type
    ON payment.parent_credit_ledger (reference_id, type)
    WHERE reference_id IS NOT NULL;
