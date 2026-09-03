-- skillars-deferred-91 AC5 Part A: bound the slot-hold harm of a stuck CAPTURE_PENDING
-- booking_payments row (skillars-uat-3 D3 / skillars-deferred-90 line 1325).
--
-- PaymentPendingSweeper can now age a CAPTURE_PENDING row: past
-- booking.payment_pending.capture_pending_max_hours it transitions to the new terminal
-- CAPTURE_ABANDONED status, releases the coach's slot and unblocks the parent's cancel, and emits
-- booking.payment_pending.unrecoverable{reason="CAPTURE_TIMEOUT"}. No automatic charge/confirm — an
-- operator still reconciles the Stripe side (runbook: CAPTURE_ABANDONED). CAPTURE_ABANDONED is
-- distinct from CHARGE_FAILED: CHARGE_FAILED asserts "no money moved", CAPTURE_ABANDONED asserts
-- "we stopped waiting; the Stripe side is unknown".
--
-- reserved_at: added nullable (no default) so the ADD COLUMN is catalog-only, no table rewrite.
-- BookingPaymentPersistenceService.reserveCapture stamps it; a pre-existing row with NULL
-- reserved_at cannot be aged and stays on the existing CAPTURE_UNCONFIRMED manual path.
ALTER TABLE payment.booking_payments ADD COLUMN reserved_at TIMESTAMPTZ;

-- 'CAPTURE_ABANDONED' is 17 chars; the column was VARCHAR(16). Widening a varchar length is
-- catalog-only in PostgreSQL (no rewrite, no scan).
ALTER TABLE payment.booking_payments ALTER COLUMN status TYPE VARCHAR(20);

-- migration-lint: allow-validating-constraint no production system exists; this widens an existing
-- enumerated CHECK to admit one new terminal status value on a single-node deploy (mirrors V94's
-- own DROP+ADD of chk_bp_status). The re-validation scans an empty table.
--
-- Rolling-deploy rule 5 (enum/CHECK widening one release ahead of the first write) is knowingly
-- deviated from here: PaymentPendingSweeper.abandonCapture writes CAPTURE_ABANDONED in this same
-- release. Sanctioned by the standing "no production system exists" project fact — see the
-- pre-production grandfathering clause in docs/deployment/migration-conventions.md. Before the
-- first production deploy this must be split into widen-then-write across two releases.
-- migration-lint: allow-enum-widen-same-release no production system exists; single-node deploy.
--
-- IF EXISTS on the DROP (skillars-deferred-91 code review): without it this migration fails hard on
-- any environment where chk_bp_status is already absent — a re-run, or a diverged dev database.
ALTER TABLE payment.booking_payments
    DROP CONSTRAINT IF EXISTS chk_bp_status,
    ADD CONSTRAINT chk_bp_status
        CHECK (status IN ('CAPTURE_PENDING', 'CAPTURED', 'CHARGE_FAILED', 'FROZEN', 'CAPTURE_ABANDONED'));
