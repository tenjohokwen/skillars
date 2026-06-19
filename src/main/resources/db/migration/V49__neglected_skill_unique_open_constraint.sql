-- Story 5.2 patch: replace plain partial index with UNIQUE to prevent duplicate open flags
-- for the same (player_id, skill_code) pair before NeglectedSkillProcessor can close the old one.
DROP INDEX IF EXISTS development.idx_neglected_flags_open;
CREATE UNIQUE INDEX idx_neglected_flags_open
    ON development.neglected_skill_flags (player_id, skill_code)
    WHERE resolved_at IS NULL;
