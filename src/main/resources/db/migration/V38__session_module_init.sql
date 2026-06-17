-- Story 4.1: Session module — schema, tables, feature gate config keys

-- Step 1: Create session schema
CREATE SCHEMA IF NOT EXISTS session;

-- Step 2: Create drills table
CREATE TABLE session.drills (
    id              UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    name            VARCHAR(200) NOT NULL,
    description     TEXT,
    library_type    VARCHAR(10)  NOT NULL CHECK (library_type IN ('PLATFORM', 'COACH')),
    owner_coach_id  UUID,
    status          VARCHAR(10)  NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'ARCHIVED')),
    metadata        JSONB        NOT NULL,
    trans_key       VARCHAR(100) UNIQUE,
    version         INT          NOT NULL DEFAULT 0,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT chk_drill_owner CHECK (
        (library_type = 'PLATFORM' AND owner_coach_id IS NULL) OR
        (library_type = 'COACH'    AND owner_coach_id IS NOT NULL)
    )
);

-- Step 3: Create drill_video_refs table
CREATE TABLE session.drill_video_refs (
    drill_id    UUID PRIMARY KEY REFERENCES session.drills(id) ON DELETE CASCADE,
    video_id    UUID,
    ref_count   INT NOT NULL DEFAULT 1
);

-- Step 4: Indexes
CREATE INDEX idx_drills_library_type    ON session.drills (library_type);
CREATE INDEX idx_drills_owner_coach_id  ON session.drills (owner_coach_id);
CREATE INDEX idx_drills_status          ON session.drills (status);

-- Step 5: Feature gate config keys
INSERT INTO main.platform_config (id, key, value, value_type, description, updated_at) VALUES
    (60, 'feature.sessionBuilder.enabled.SCOUT',      'false', 'STRING', 'Session Builder gate: Scout tier',      NOW()),
    (61, 'feature.sessionBuilder.enabled.INSTRUCTOR', 'true',  'STRING', 'Session Builder gate: Instructor tier', NOW()),
    (62, 'feature.sessionBuilder.enabled.ACADEMY',    'true',  'STRING', 'Session Builder gate: Academy tier',    NOW())
ON CONFLICT (key) DO NOTHING;
