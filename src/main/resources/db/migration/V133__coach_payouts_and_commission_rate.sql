-- skillars-deferred-106 (completion-gated coach payout, B-1 separate charges & transfers).
--
-- Part B of docs/architecture/payout-and-capture-pending.md. The coach used to be paid net of
-- commission AT CAPTURE, as a property of the Stripe destination charge (transfer_data.destination
-- + application_fee_amount on the PaymentIntent) — days before the session. B-1 charges the
-- platform account at booking and transfers the net to the coach only on BookingCompletedEvent,
-- through the skillars-deferred-91 durable outbox (new COACH_PAYOUT_TRANSFER handler).
--
-- This migration is ALL ADDITIVE:
--   * CREATE TABLE payment.coach_payouts — the payout ledger, one row per booking (booking_id PK is
--     the idempotency anchor: a booking is paid out at most once). The row is created PENDING_RELEASE
--     by the completion-enqueue listener, atomically with the booking's completion write, and the
--     COACH_PAYOUT_TRANSFER / COACH_PAYOUT_REVERSAL outbox handlers transition it from there.
--   * ALTER payment.booking_payments ADD COLUMN commission_rate — nullable, no default, catalog-only
--     ADD COLUMN exactly like V124's reserved_at. Stamped going forward by persistPaymentSuccess /
--     confirmPackBatchPayment / confirmCreditBatchPayment with the platform.commission.rate in force
--     at capture, so the payout net is computed from the rate locked at charge time and never from
--     platform.commission.rate re-read at completion (which may have changed). Left NULL for pre-V133
--     rows; the cutover backfill (V135) and payout code fall back to the live rate for those legacy
--     already-CAPTURED rows only.
--
-- No CHECK/enum widen on booking_payments — rolling-deploy rule 5 does not apply here (unlike V124).
-- V133 is above the V129 baseline, so MigrationConventionLintTest binds fully; the Go-forward
-- checklist in docs/deployment/migration-conventions.md was run against it.
--
-- NB: the story text calls these V130/V131/V132; those numbers were taken by other stories merged
-- between story creation and implementation, so this bundle ships as V133/V134/V135.

-- Bounded wait for the ADD COLUMN (ACCESS EXCLUSIVE, catalog-only but still bounded per AC8) and
-- the CREATE INDEX statements below. booking_payments is read on the settle path from every node.
SET lock_timeout = '5s';

CREATE TABLE payment.coach_payouts (
    booking_id                  uuid          PRIMARY KEY,
    coach_id                    uuid          NOT NULL,
    coach_stripe_account_id     text,
    gross_amount                numeric(10,2) NOT NULL,
    commission_amount           numeric(10,2) NOT NULL,
    net_amount                  numeric(10,2) NOT NULL,
    currency                    text          NOT NULL,
    status                      varchar(20)   NOT NULL,
    stripe_transfer_id          text,
    stripe_transfer_reversal_id text,
    release_after               timestamptz   NOT NULL,
    released_at                 timestamptz,
    reversed_at                 timestamptz,
    attempts                    integer       NOT NULL DEFAULT 0,
    last_error                  text,
    created_at                  timestamptz   NOT NULL DEFAULT now(),
    updated_at                  timestamptz   NOT NULL DEFAULT now()
);

-- The status domain mirrors CoachPayoutStatus (a VARCHAR compared as a string, same convention as
-- BookingPaymentStatus — deliberately not an enum). The table is empty at creation, so validating
-- the CHECK in the same statement scans nothing.
-- migration-lint: allow-validating-constraint new empty table — CHECK on payment.coach_payouts,
-- created empty in this same migration; the re-validation scans zero rows.
ALTER TABLE payment.coach_payouts
    ADD CONSTRAINT chk_coach_payouts_status
        CHECK (status IN ('PENDING_RELEASE', 'RELEASED', 'REVERSED', 'REVERSAL_FAILED',
                          'CANCELLED', 'HOLD', 'FAILED_PERMANENT'));

-- Runbook detection query filters status IN ('HOLD','REVERSAL_FAILED','FAILED_PERMANENT'); the
-- COACH_PAYOUT_TRANSFER handler and DisputeService both load-by-PK, so no index is needed for them.
-- migration-lint: allow-blocking-index new empty table — payment.coach_payouts is empty at deploy;
-- this codebase runs Flyway inside a transaction so CREATE INDEX CONCURRENTLY is unavailable (see
-- V126 / V121 for the identical documented tradeoff).
CREATE INDEX idx_coach_payouts_status ON payment.coach_payouts (status);

-- Revenue reporting (AC12) sums/counts/pages RELEASED payouts for a coach dated by released_at.
-- migration-lint: allow-blocking-index new empty table — see idx_coach_payouts_status above.
CREATE INDEX idx_coach_payouts_coach_released
    ON payment.coach_payouts (coach_id, released_at)
    WHERE status = 'RELEASED';

ALTER TABLE payment.booking_payments
    ADD COLUMN commission_rate numeric(5,4);
