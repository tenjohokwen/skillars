CREATE TABLE session.session_templates (
    id                UUID          PRIMARY KEY DEFAULT gen_random_uuid(),
    coach_id          UUID          NOT NULL,
    name              VARCHAR(200)  NOT NULL,
    blocks            JSONB         NOT NULL DEFAULT '[]',
    session_dna       JSONB,
    equipment_list    JSONB         NOT NULL DEFAULT '[]',
    development_focus JSONB         NOT NULL DEFAULT '[]',
    last_deployed_at  TIMESTAMPTZ,
    deploy_count      INT           NOT NULL DEFAULT 0,
    status            VARCHAR(20)   NOT NULL DEFAULT 'ACTIVE'
                      CHECK (status IN ('ACTIVE', 'ARCHIVED')),
    created_at        TIMESTAMPTZ   NOT NULL DEFAULT now()
);
CREATE INDEX idx_session_templates_coach_id ON session.session_templates (coach_id);

-- Add template provenance columns to session.sessions
ALTER TABLE session.sessions
    ADD COLUMN source_template_id   UUID,
    ADD COLUMN source_template_name VARCHAR(200);
