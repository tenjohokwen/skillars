ALTER TABLE session.drills
    ADD COLUMN source_drill_id UUID REFERENCES session.drills(id);
CREATE INDEX idx_drills_source_drill_id ON session.drills(source_drill_id);
CREATE UNIQUE INDEX idx_drills_clone_uniqueness ON session.drills(source_drill_id, owner_coach_id)
    WHERE source_drill_id IS NOT NULL;
