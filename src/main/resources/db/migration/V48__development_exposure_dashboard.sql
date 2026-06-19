-- Story 5.2: Skill Exposure Dashboard & Neglected Skill Detection

-- Weekly SLU snapshot for sub-second trend queries (NFR-001)
-- Maintained by SluCalculationService after each saveAll(); upserted on new SLU writes.
CREATE TABLE development.player_slu_weekly_snapshot (
    player_id   BIGINT       NOT NULL,   -- TSID Long; NOT UUID despite epics spec
    skill_code  VARCHAR(10)  NOT NULL REFERENCES development.skill_definitions(code),
    iso_year    SMALLINT     NOT NULL,
    iso_week    SMALLINT     NOT NULL,
    total_slu   NUMERIC(12,4) NOT NULL DEFAULT 0,
    PRIMARY KEY (player_id, skill_code, iso_year, iso_week)
);
CREATE INDEX idx_player_slu_snapshot_player_year_week
    ON development.player_slu_weekly_snapshot (player_id, iso_year, iso_week);

-- Coach-defined weekly SLU targets per skill per player
-- Multiple coaches may each set independent targets; evaluation uses the highest.
CREATE TABLE development.player_slu_targets (
    coach_id          UUID         NOT NULL,  -- marketplace.coach_profiles.id (UUID)
    player_id         BIGINT       NOT NULL,  -- TSID Long; NOT UUID despite epics spec
    skill_code        VARCHAR(10)  NOT NULL REFERENCES development.skill_definitions(code),
    weekly_target_slu NUMERIC(10,4) NOT NULL,
    updated_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    PRIMARY KEY (coach_id, player_id, skill_code)
);
CREATE INDEX idx_player_slu_targets_player_id ON development.player_slu_targets (player_id);

-- Neglected skill flags: open when actual SLU < (target * (1 - threshold)); resolved when met
CREATE TABLE development.neglected_skill_flags (
    id          UUID          PRIMARY KEY DEFAULT gen_random_uuid(),
    player_id   BIGINT        NOT NULL,   -- TSID Long; NOT UUID despite epics spec
    skill_code  VARCHAR(10)   NOT NULL REFERENCES development.skill_definitions(code),
    detected_at TIMESTAMPTZ   NOT NULL DEFAULT now(),
    resolved_at TIMESTAMPTZ              -- NULL = still neglected
);
CREATE INDEX idx_neglected_flags_player_id ON development.neglected_skill_flags (player_id);
CREATE INDEX idx_neglected_flags_open
    ON development.neglected_skill_flags (player_id, skill_code)
    WHERE resolved_at IS NULL;

-- Neglected skill detection threshold (30% deficit triggers a flag)
-- Next available ID after V46 (70-72): 73
INSERT INTO main.platform_config (id, key, value, value_type, description, updated_at) VALUES
    (73, 'slu.neglected.threshold', '0.30', 'STRING',
     'Neglected skill threshold: flag if actual < target × (1 - threshold)', NOW())
ON CONFLICT (key) DO NOTHING;
