-- Story Deferred-76 AC9.

-- NeglectedSkillProcessor flags every coach-targeted skill as neglected for a brand-new or
-- long-inactive player, because zero recorded SLU is always below any nonzero target — a guaranteed
-- flag-flood on first evaluation. This threshold gates evaluation on the player having logged at
-- least this many distinct sessions, mirroring development.correlation.minSessionCount's existing
-- config-driven "not enough data yet" pattern (V51).
INSERT INTO main.platform_config (id, key, value, value_type, description, updated_at) VALUES
    (605, 'development.neglectedSkill.warmupSessionCount', '5', 'LONG',
     'Minimum distinct completed sessions a player must have logged before NeglectedSkillProcessor evaluates them — avoids flagging every coach-targeted skill on a brand-new player''s first evaluation', NOW())
ON CONFLICT (key) DO NOTHING;
