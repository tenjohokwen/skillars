-- skillars-deferred-163 AC2.4: a QUICK-mode booking's session plan stays editable until the delayed
-- BookingCompletedEvent (parent confirm, or QuickCompleteTimeoutService's timeout auto-confirm) finally
-- fires -- sometimes hours later. Snapshotting the plan's BLOCKS at wrap-up time (the same
-- List<SessionBlockData> JSON shape as session.sessions.blocks: drill refs with their order AND each
-- block's duration) lets both the timeline's drill list and SLU be computed from "the plan as of
-- wrap-up" instead of racing a later edit -- drill ids alone would not be enough for SLU, which needs
-- per-block durations. Additive, nullable ADD COLUMN per the expand/contract standard
-- (migration-conventions.md rule 1); no backfill is possible for existing rows.
SET LOCAL lock_timeout = '5s';

ALTER TABLE booking.session_completion_data
    ADD COLUMN IF NOT EXISTS plan_blocks_snapshot text;
