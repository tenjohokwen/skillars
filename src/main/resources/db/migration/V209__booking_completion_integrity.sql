-- fix/booking-completion-integrity: sessions that ended without being settled.
--
-- 1. bookings.auto_ended_at — set by UnresolvedSessionSweeperService when it moves a live session
--    (IN_PROGRESS / PAUSED) the coach left open past its scheduled end + booking.unresolvedSession.graceMinutes
--    into COMPLETED_PENDING_CONFIRMATION. The coach's wrap-up of such a booking is a QUICK one (the parent's
--    confirm / dispute window applies) rather than a LIVE one that completes and releases the payout at once.
-- 2. bookings.unresolved_alerted_at — set when the same sweeper raises the SESSION_NEVER_STARTED admin alert
--    for an UPCOMING booking whose session ended without being started (payment captured, nothing settled).
--    Once per booking, so an alert an admin resolved is not raised again on the next sweep.
-- 3. admin_alerts: SESSION_NEVER_STARTED type (reference_type BOOKING, already allowed). The CHECK is rebuilt
--    as the UNION with everything V208 allows (V208 added BOOKING_ACTION_REQUIRED).
--
-- No platform_config seed rows: booking.playerNoShow.graceMinutes (15) and booking.unresolvedSession.graceMinutes
-- (120) are HAS_CODE_DEFAULT keys in ConfigBounds, editable from the admin config editor without a stored row.
--
-- Additive only: nullable columns with no default are a catalog-only change. CHECK widening and its first
-- writes ship together — no production deployment yet (the V149/V152/V153/V162/V186 owner decision).
-- Existing rows are untouched; the first sweep after deploy picks up any already-ended live / never-started
-- sessions (bounded pages of 200 per 5-minute tick).
SET LOCAL lock_timeout = '5s';

ALTER TABLE booking.bookings
    ADD COLUMN IF NOT EXISTS auto_ended_at timestamp with time zone,
    ADD COLUMN IF NOT EXISTS unresolved_alerted_at timestamp with time zone;

ALTER TABLE admin.admin_alerts
    DROP CONSTRAINT IF EXISTS admin_alerts_type_check,
    -- migration-lint: allow-validating-constraint small, internal admin-queue table; no bulk-insert
    -- path and no row count anywhere near large-table territory, so a validating CHECK swap is a fast
    -- full-table scan, not a rolling-deploy lock-exposure risk.
    ADD CONSTRAINT admin_alerts_type_check CHECK (((type)::text = ANY ((ARRAY[
        'MESSAGE_REPORT'::character varying,
        'CONVERSATION_REPORT'::character varying,
        'REVIEW_FLAG'::character varying,
        'STRIKE_THRESHOLD'::character varying,
        'DISPUTE_RAISED'::character varying,
        'MODERATION_UNRESOLVED'::character varying,
        'GDPR_ERASURE_DEADLINE'::character varying,
        'SUBSCRIPTION_ORPHANED'::character varying,
        'SAFETY_HOLD_BOOKING'::character varying,
        'CASH_OUT_UNRESOLVED'::character varying,
        'CHARGEBACK'::character varying,
        'EXTERNAL_REFUND'::character varying,
        'PAYOUT_HELD'::character varying,
        'PAYOUT_REVERSAL_FAILED'::character varying,
        'BOOKING_ACTION_REQUIRED'::character varying,
        'SESSION_NEVER_STARTED'::character varying
    ])::text[])));
