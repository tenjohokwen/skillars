-- Fixture (rule 4): plain CREATE INDEX on a non-tiny table without CONCURRENTLY and without opt-out.
CREATE INDEX idx_widget_created_at ON main.widget (created_at);
