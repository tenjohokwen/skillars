-- fix/audit3-completion-dispute-integrity: session completion, no-show and dispute integrity.
--
-- 1. bookings.confirmation_requested_at — set when the family is asked to confirm (or dispute) a session that
--    entered COMPLETED_PENDING_CONFIRMATION without a full live run: a Quick Complete, a live session
--    UnresolvedSessionSweeperService ended, or a live session ended below booking.live.minDurationPercent of
--    its booked length. The QuickCompleteConfirmationRequiredEvent email is sent once, at that moment (it used
--    to go out only at wrap-up submit, so a coach who never submitted one was paid by the stuck-completion
--    sweeper with the family never asked). A stamped booking's wrap-up is QUICK (parent confirm / dispute),
--    never LIVE. NULL = not asked (a full live session, or a row from before this migration).
-- 2. coach_cancellation_history.after_start — the coach cancelled at or after the booking's start. Such a
--    cancel always strikes (a no-show in disguise) and is not counted toward the excused-cancellation cap.
-- 3. admin.disputes: one OPEN dispute per booking PER SIDE (coach vs family) instead of per booking — a coach
--    who disputed first used to block the family's own dispute. idx_disputes_unique_open_per_booking is
--    replaced by idx_disputes_unique_open_per_booking_side on (booking_id, raised_by_role = 'COACH').
--
-- No platform_config seed rows: booking.live.minDurationPercent (50) and disputes.maxPerBookingPerRaiser (2)
-- are HAS_CODE_DEFAULT keys in ConfigBounds, editable from the admin config editor without a stored row.
--
-- Additive for 1/2: a nullable column with no default, and a NOT NULL column with a constant default, are both
-- catalog-only changes (PG 11+). 3 swaps a partial unique index on admin.disputes — a small admin table — and
-- the new index is strictly looser than the old one, so every existing row satisfies it.
SET LOCAL lock_timeout = '5s';

ALTER TABLE booking.bookings
    ADD COLUMN IF NOT EXISTS confirmation_requested_at timestamp with time zone;

ALTER TABLE payment.coach_cancellation_history
    ADD COLUMN IF NOT EXISTS after_start boolean DEFAULT false NOT NULL;

-- migration-lint: allow-blocking-index admin.disputes is a small admin table (one row per raised dispute, prod
-- not live); the SHARE lock is held for milliseconds.
CREATE UNIQUE INDEX IF NOT EXISTS idx_disputes_unique_open_per_booking_side
    ON admin.disputes USING btree (booking_id, ((raised_by_role)::text = 'COACH'::text))
    WHERE ((status)::text <> ALL ((ARRAY['RESOLVED'::character varying, 'DISMISSED'::character varying])::text[]));

DROP INDEX IF EXISTS admin.idx_disputes_unique_open_per_booking;
