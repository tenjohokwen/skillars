-- Story 5.3: Skills Radar Assessment Entry & Multi-Coach Cumulation

-- Add rubric_criteria to skill_definitions so the assessment panel can show per-skill rubric tooltips (AC 1, FR-DEV-007)
ALTER TABLE development.skill_definitions ADD COLUMN IF NOT EXISTS rubric_criteria VARCHAR(500);

UPDATE development.skill_definitions SET rubric_criteria = 'Measures maximum sprint speed over 30m and acceleration in the first 10m.' WHERE code = 'PAC';
UPDATE development.skill_definitions SET rubric_criteria = 'Accuracy, power, and composure when shooting on goal from various distances and angles.' WHERE code = 'SHO';
UPDATE development.skill_definitions SET rubric_criteria = 'Range, weight, and accuracy of short, medium, and long-range passes under pressure.' WHERE code = 'PAS';
UPDATE development.skill_definitions SET rubric_criteria = 'Ability to beat an opponent 1v1 using close control, feints, and changes of direction.' WHERE code = 'DRI';
UPDATE development.skill_definitions SET rubric_criteria = 'Strength, balance, and ability to hold off challenges and win physical duels.' WHERE code = 'PHY';
UPDATE development.skill_definitions SET rubric_criteria = 'Positioning, timing of tackles, and ability to win the ball cleanly in defensive duels.' WHERE code = 'DEF';
UPDATE development.skill_definitions SET rubric_criteria = 'Quality of striking and control using the non-dominant foot in match situations.' WHERE code = 'WEF';
UPDATE development.skill_definitions SET rubric_criteria = 'Control and ability to set up a second touch instantly from various delivery types.' WHERE code = 'F1T';
UPDATE development.skill_definitions SET rubric_criteria = 'Composure and technique in front of goal in 1v1 and tight-angle situations.' WHERE code = 'FIN';
UPDATE development.skill_definitions SET rubric_criteria = 'Ability to beat a goalkeeper or defender in isolated 1v1 attacking situations.' WHERE code = '1V1';
UPDATE development.skill_definitions SET rubric_criteria = 'Accuracy and timing of headed attempts at goal or for clearances.' WHERE code = 'HED';
UPDATE development.skill_definitions SET rubric_criteria = 'Delivery quality and decision-making when crossing from wide positions.' WHERE code = 'CRO';
UPDATE development.skill_definitions SET rubric_criteria = 'Timing and angle of runs in behind a defensive line to receive through-balls.' WHERE code = 'IBS';
UPDATE development.skill_definitions SET rubric_criteria = 'Awareness and head-movement to gather visual information before receiving the ball.' WHERE code = 'OBS';
UPDATE development.skill_definitions SET rubric_criteria = 'Technique, accuracy, and power on set-piece deliveries from dead-ball situations.' WHERE code = 'FKI';

CREATE TYPE development.assessment_type AS ENUM ('OBJECTIVE', 'MATCH_OBSERVATION', 'COACH_EVALUATION');

CREATE TABLE development.radar_assessment_entries (
    id                   UUID          PRIMARY KEY DEFAULT gen_random_uuid(),
    assessment_group_id  UUID          NOT NULL,
    coach_id             UUID          NOT NULL,   -- marketplace.coach_profiles.id (UUID)
    player_id            BIGINT        NOT NULL,   -- TSID Long; NOT UUID
    skill_code           VARCHAR(10)   NOT NULL REFERENCES development.skill_definitions(code),
    score                SMALLINT      NOT NULL CHECK (score >= 1 AND score <= 100),
    assessment_date      DATE          NOT NULL,
    assessment_type      development.assessment_type NOT NULL,
    notes                VARCHAR(500),
    created_at           TIMESTAMPTZ   NOT NULL DEFAULT now()
);

CREATE INDEX idx_radar_entries_player_skill   ON development.radar_assessment_entries (player_id, skill_code);
CREATE INDEX idx_radar_entries_player_coach   ON development.radar_assessment_entries (player_id, coach_id);
CREATE INDEX idx_radar_entries_group          ON development.radar_assessment_entries (assessment_group_id);
-- Unique constraint makes client-side assessmentGroupId retries truly idempotent
CREATE UNIQUE INDEX uq_radar_entries_group_coach_skill ON development.radar_assessment_entries (assessment_group_id, coach_id, skill_code);

CREATE TABLE development.player_radar_composites (
    player_id        BIGINT          NOT NULL,   -- TSID Long; NOT UUID
    skill_code       VARCHAR(10)     NOT NULL REFERENCES development.skill_definitions(code),
    composite_score  NUMERIC(5,2)    NOT NULL,
    entry_count      INT             NOT NULL,
    last_updated_at  TIMESTAMPTZ     NOT NULL,
    PRIMARY KEY (player_id, skill_code)
);
