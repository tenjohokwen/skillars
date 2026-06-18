-- Story 5.1: development module — SLU engine & skill taxonomy

-- Schema
CREATE SCHEMA IF NOT EXISTS development;

-- Skill taxonomy (extensible: new codes can be inserted without schema change)
CREATE TABLE development.skill_definitions (
    code            VARCHAR(10)  PRIMARY KEY,
    display_name    VARCHAR(100) NOT NULL,
    display_order   SMALLINT     NOT NULL,
    active          BOOLEAN      NOT NULL DEFAULT true
);

-- IMMUTABLE: player_skill_stats rows are append-only — no update path exists.
-- Rows bake in SLU values at session completion time; historical data is tamper-proof.
-- Do not add UPDATE or DELETE operations to SluRepository.
CREATE TABLE development.player_skill_stats (
    id              UUID          PRIMARY KEY DEFAULT gen_random_uuid(),
    player_id       BIGINT        NOT NULL,   -- TSID Long; NOT UUID despite epics spec wording
    session_id      UUID,                      -- nullable (Quick Complete has no session plan)
    coach_id        UUID          NOT NULL,
    skill_code      VARCHAR(10)   NOT NULL REFERENCES development.skill_definitions(code),
    slu_value       NUMERIC(10,4) NOT NULL,
    calculated_at   TIMESTAMPTZ   NOT NULL DEFAULT now()
);

COMMENT ON TABLE development.player_skill_stats IS 'IMMUTABLE: append-only. SLU values are baked in at session completion time. No UPDATE or DELETE code paths exist — see SluRepository.';

CREATE INDEX idx_player_skill_stats_player_id    ON development.player_skill_stats (player_id);
CREATE INDEX idx_player_skill_stats_session_id   ON development.player_skill_stats (session_id);
CREATE INDEX idx_player_skill_stats_player_skill ON development.player_skill_stats (player_id, skill_code);

-- Seed 15 skill definitions (FR-DEV-001)
INSERT INTO development.skill_definitions (code, display_name, display_order) VALUES
    ('PAC', 'Pace',               1),
    ('SHO', 'Shooting',           2),
    ('PAS', 'Passing',            3),
    ('DRI', 'Dribbling',          4),
    ('PHY', 'Physicality',        5),
    ('DEF', 'Defending',          6),
    ('WEF', 'Weak Foot',          7),
    ('F1T', 'First Touch',        8),
    ('FIN', 'Finishing',          9),
    ('1V1', 'One vs One',        10),
    ('HED', 'Heading',           11),
    ('CRO', 'Crossing',          12),
    ('IBS', 'In Behind Runs',    13),
    ('OBS', 'Off-Ball Scanning', 14),
    ('FKI', 'Free Kick Inst.',   15);

-- SLU modifier scaling factors (tunable via admin config panel)
-- IDs 70-72 are the next available block after V42 (last: 67); V45 added none.
INSERT INTO main.platform_config (id, key, value, value_type, description, updated_at) VALUES
    (70, 'slu.intensity.scale',    '0.10', 'STRING', 'SLU intensity modifier scale (intensity × scale = multiplier)', NOW()),
    (71, 'slu.pressure.scale',     '0.10', 'STRING', 'SLU pressure modifier scale (pressureLevel × scale = multiplier)', NOW()),
    (72, 'slu.matchRealism.scale', '0.10', 'STRING', 'SLU match realism modifier scale (matchRealism × scale = multiplier)', NOW())
ON CONFLICT (key) DO NOTHING;
