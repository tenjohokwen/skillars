-- Story 5.1 review: prevent duplicate SLU rows if BookingCompletedEvent fires more than once for the same session.
-- Partial (WHERE session_id IS NOT NULL) because PostgreSQL treats NULLs as distinct in unique constraints,
-- so a non-partial UNIQUE would provide no deduplication protection for null-session_id rows.
CREATE UNIQUE INDEX uq_player_skill_stats_session_skill
    ON development.player_skill_stats (session_id, skill_code)
    WHERE session_id IS NOT NULL;
