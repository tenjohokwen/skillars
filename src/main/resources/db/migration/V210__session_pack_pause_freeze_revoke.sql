-- fix/payment-pack-integrity.
--
-- 1. session_pack_purchases.paused_from: when a scheduled pause starts. Only paused_until used to be
--    stored, so a pause scheduled for a later date blocked the pack (booking, accept-time deduction,
--    locker-room drills) from the moment it was scheduled — the parent lost validity days and pending
--    requests failed at accept. The pack is now paused only over [paused_from, paused_until). NULL for a
--    pause recorded before this migration: those keep the old meaning (paused until paused_until).
-- 2. session_pack_purchases.dispute_frozen_at: set while a card dispute on the pack's charge is open —
--    no booking request and no session deduction against the pack until it is won (cleared) or lost.
-- 3. session_pack_purchases.revoked_at / revoked_reason: the pack's remaining sessions were taken away —
--    CHARGEBACK_LOST (the payer got the money back through their bank), EXTERNAL_REFUND (the charge was
--    refunded in full outside the app) or COACH_SUSPENDED (the coach can no longer be booked; the unused
--    sessions were refunded to the payer's wallet at the pack's locked price).
--
-- Migration conventions (docs/deployment/migration-conventions.md), mirroring V195/V200:
--  - Additive only: nullable columns, no default, catalog-only.
--  - Rule 5: the columns and their first writes ship together — prod is not live (the V149/V152/V162/V186
--    owner decision).
SET LOCAL lock_timeout = '5s';

ALTER TABLE payment.session_pack_purchases
    ADD COLUMN IF NOT EXISTS paused_from timestamptz,
    ADD COLUMN IF NOT EXISTS dispute_frozen_at timestamptz,
    ADD COLUMN IF NOT EXISTS revoked_at timestamptz,
    ADD COLUMN IF NOT EXISTS revoked_reason varchar(40);

ALTER TABLE payment.session_pack_purchases
    -- migration-lint: allow-validating-constraint prod not live; every existing row has paused_from NULL and
    -- revoked_reason NULL, so both checks pass on the scan of dev/uat data.
    ADD CONSTRAINT chk_spp_pause_window CHECK (
        paused_from IS NULL OR (paused_until IS NOT NULL AND paused_from < paused_until)),
    ADD CONSTRAINT chk_spp_revoked_reason CHECK (
        revoked_reason IS NULL OR revoked_reason IN ('CHARGEBACK_LOST', 'EXTERNAL_REFUND', 'COACH_SUSPENDED'));
