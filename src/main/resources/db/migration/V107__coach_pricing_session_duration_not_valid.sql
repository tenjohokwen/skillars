-- Story Deferred-70 AC3: chk_coach_pricing_session_duration (V93) was added via a single ALTER TABLE
-- ADD COLUMN + ADD CONSTRAINT, taking an ACCESS EXCLUSIVE lock for the full duration of the
-- constraint's initial full-table CHECK validation — the same pattern V100/V101 and V105/V106
-- established a NOT VALID + VALIDATE CONSTRAINT split to avoid. Table is small today (one row per
-- coach); the split costs nothing and keeps this migration's locking behavior consistent with every
-- CHECK constraint added since V100. The constraint already exists in every environment that has run
-- V93 (an already-applied migration cannot be edited), so it is dropped and re-added here rather than
-- added fresh.
ALTER TABLE marketplace.coach_pricing
    DROP CONSTRAINT chk_coach_pricing_session_duration;

ALTER TABLE marketplace.coach_pricing
    ADD CONSTRAINT chk_coach_pricing_session_duration
    CHECK (session_duration_minutes IS NULL
           OR (session_duration_minutes BETWEEN 15 AND 240)) NOT VALID;
