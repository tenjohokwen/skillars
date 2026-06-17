CREATE TABLE session.drill_tags (
    drill_id    UUID        NOT NULL REFERENCES session.drills(id) ON DELETE CASCADE,
    tag         VARCHAR(50) NOT NULL,
    coach_id    UUID        NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT pk_drill_tags PRIMARY KEY (drill_id, tag, coach_id)
);
CREATE INDEX idx_drill_tags_drill_id ON session.drill_tags(drill_id);
CREATE INDEX idx_drill_tags_coach_id ON session.drill_tags(coach_id);
