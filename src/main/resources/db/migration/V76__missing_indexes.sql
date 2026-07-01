-- Story deferred-3: DB schema hardening — missing indexes

-- AC 1: homework assignments composite index (player_id, coach_id) for coach-scoped player lookups
CREATE INDEX IF NOT EXISTS idx_homework_assignments_player_coach
    ON session.homework_assignments(player_id, coach_id);

-- AC 2: session pack purchases — parent-scoped lookups and coach+expiry window scanning
-- NOTE: authoritative table is booking.session_packs_purchased (the one queried by
-- SessionPackExpiryScheduler / SessionPackPurchasedRepository), not "session_pack_purchases".
CREATE INDEX IF NOT EXISTS idx_session_packs_purchased_parent_id
    ON booking.session_packs_purchased(parent_id);

CREATE INDEX IF NOT EXISTS idx_session_packs_purchased_coach_expires
    ON booking.session_packs_purchased(coach_id, expires_at)
    WHERE status NOT IN ('EXHAUSTED', 'EXPIRED');

-- AC 3: session template traceability
CREATE INDEX IF NOT EXISTS idx_sessions_source_template_id
    ON session.sessions(source_template_id)
    WHERE source_template_id IS NOT NULL;
