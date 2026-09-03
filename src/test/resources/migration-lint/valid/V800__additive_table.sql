-- Fixture: additive-only. New table, nothing dropped. (migration-conventions.md rule 1)
CREATE TABLE main.widget (
    id         bigint PRIMARY KEY,
    name       text NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now()
);
