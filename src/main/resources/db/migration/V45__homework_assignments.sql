CREATE TABLE session.homework_assignments (
    id          UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    booking_id  UUID        NOT NULL,
    session_id  UUID,
    player_id   BIGINT      NOT NULL,
    coach_id    UUID        NOT NULL,
    drill_id    UUID        NOT NULL,
    pack_id     UUID,
    assigned_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_homework_booking_drill UNIQUE (booking_id, drill_id)
);
CREATE INDEX idx_homework_assignments_player_id ON session.homework_assignments (player_id);
CREATE INDEX idx_homework_assignments_coach_id  ON session.homework_assignments (coach_id);

CREATE TABLE session.homework_completions (
    id            UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    assignment_id UUID        NOT NULL REFERENCES session.homework_assignments(id) ON DELETE CASCADE,
    player_id     BIGINT      NOT NULL,
    completed_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_homework_completion UNIQUE (assignment_id)
);
CREATE INDEX idx_homework_completions_assignment_id ON session.homework_completions (assignment_id);
