-- skillars-deferred-91 code review (2026-09-03), decision D4: narrow V125's dedup index.
--
-- V125 created uq_pcl_reference_type on (reference_id, type) WHERE reference_id IS NOT NULL, but
-- AC2's requirement was only "one BOOKING_REFUND per booking". The broad form also binds every
-- other reference-carrying ledger type, and two existing synchronous writers legitimately reuse a
-- (reference_id, type) pair:
--
--   * BookingPaymentPersistenceService:200/229 — BOOKING_DEDUCTION then BOOKING_DEDUCTION_REVERSAL
--     keyed on bookingId. A booking whose payment fails (reversal written) and is then re-attempted
--     writes a SECOND BOOKING_DEDUCTION for the same bookingId.
--   * PaymentLifecycleService:326/338 — the same pair keyed on batchId, so a retried batch collides.
--
-- Under V125 either case raised a unique violation on a payment path. Narrowing the predicate to
-- BOOKING_REFUND keeps exactly the AC2 guarantee (the CreditWalletRefundOutboxHandler existence
-- check is the primary guard; this index is the backstop against a pathological double-enqueue) and
-- releases the deduction/reversal types.
--
-- Expand/contract: DROP INDEX IF EXISTS then CREATE. Dropping an index takes a brief ACCESS
-- EXCLUSIVE lock on the table; parent_credit_ledger is empty at deploy and no production system
-- exists, so there is nothing to block.
DROP INDEX IF EXISTS payment.uq_pcl_reference_type;

-- Defensive de-duplication before the unique index is created (skillars-deferred-91 code review).
-- V125 asserted "parent_credit_ledger is empty at deploy" and created its index unconditionally, so
-- ONE pre-existing duplicate in any long-lived environment (a staging box, a developer database, a
-- demo instance) aborted the migration and left Flyway's schema history in a failed state with the
-- application refusing to start. The most plausible duplicate is precisely what the index exists to
-- prevent: a booking refunded once at cancellation and again through a dispute. Keep the earliest
-- row per booking and delete the rest, loudly enough to be found in the migration log afterwards.
WITH ranked AS (
    SELECT tx_id,
           ROW_NUMBER() OVER (PARTITION BY reference_id ORDER BY created_at, tx_id) AS rn
      FROM payment.parent_credit_ledger
     WHERE reference_id IS NOT NULL
       AND type = 'BOOKING_REFUND'
)
DELETE FROM payment.parent_credit_ledger l
 USING ranked r
 WHERE l.tx_id = r.tx_id
   AND r.rn > 1;

-- migration-lint: allow-blocking-index no production system exists; parent_credit_ledger is empty
-- at deploy, and this codebase runs Flyway migrations in a transaction so CREATE INDEX CONCURRENTLY
-- is not available (see V121's identical documented tradeoff for uq_pot_one_active_per_user).
CREATE UNIQUE INDEX uq_pcl_booking_refund
    ON payment.parent_credit_ledger (reference_id)
    WHERE reference_id IS NOT NULL AND type = 'BOOKING_REFUND';
