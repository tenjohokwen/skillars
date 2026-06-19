-- Story 5.4: Skills Radar Display & Development Correlation

-- Baseline snapshot: written ONCE per player+skill on first composite calculation — never overwritten
CREATE TABLE development.player_radar_baselines (
    player_id       BIGINT          NOT NULL,   -- TSID Long; NOT UUID
    skill_code      VARCHAR(10)     NOT NULL REFERENCES development.skill_definitions(code),
    baseline_score  NUMERIC(5,2)    NOT NULL,
    recorded_at     TIMESTAMPTZ     NOT NULL,
    PRIMARY KEY (player_id, skill_code)
);

-- Per coach-player skill subset selection; persists chart view preference
CREATE TABLE development.coach_radar_preferences (
    coach_id         UUID            NOT NULL,   -- marketplace.coach_profiles.id (UUID)
    player_id        BIGINT          NOT NULL,   -- TSID Long; NOT UUID
    selected_skills  VARCHAR(10)[]   NOT NULL DEFAULT '{}',
    updated_at       TIMESTAMPTZ     NOT NULL,
    PRIMARY KEY (coach_id, player_id)
);

-- ConfigService key for the Development Correlation Engine minimum session threshold
INSERT INTO main.platform_config (id, key, value, value_type, description, updated_at) VALUES
    (115, 'development.correlation.minSessionCount', '5', 'LONG',
     'Minimum distinct completed sessions required before the Development Correlation Engine shows insights', NOW())
ON CONFLICT (key) DO NOTHING;
