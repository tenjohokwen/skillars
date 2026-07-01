-- Story deferred-3: prevent duplicate private drill names per coach
-- Partial: only applies to COACH-owned drills (library_type = 'COACH'); complements the
-- existing chk_drill_owner check constraint (V38), which does not conflict with this index.
CREATE UNIQUE INDEX IF NOT EXISTS idx_drills_coach_name_unique
    ON session.drills(owner_coach_id, name)
    WHERE library_type = 'COACH';

-- AC 7 (unique session plan per booking) is already enforced by uq_sessions_booking_id,
-- created in V43__session_plans.sql — no new constraint needed here.
