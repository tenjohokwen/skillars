-- skillars-12-7 code review (2026-10-22, Decision 2): when the coach started the session live
-- (BookingCompletionService.startSession). endSession (LIVE) and initiateQuickComplete (QUICK) both
-- land in COMPLETED_PENDING_CONFIRMATION, so without this the Command Center's "Resume wrap-up" cannot
-- tell an abandoned live wrap-up from an abandoned quick-complete and would resubmit a live session as
-- QUICK (parent confirmation + timeout). Additive, nullable ADD COLUMN per the expand/contract standard
-- (migration-conventions.md rule 1); no backfill is possible -- a pre-existing row reads as "not
-- started live", i.e. the previous resume behaviour.
SET LOCAL lock_timeout = '5s';

ALTER TABLE booking.bookings
    ADD COLUMN IF NOT EXISTS live_started_at timestamp with time zone;
