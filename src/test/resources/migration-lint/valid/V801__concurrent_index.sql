-- Fixture: index on a table expected to grow -> CONCURRENTLY (rule 4).
CREATE INDEX CONCURRENTLY idx_widget_name ON main.widget (name);
