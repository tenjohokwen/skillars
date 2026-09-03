-- Fixture: plain CREATE INDEX is fine here because the table is a tiny static lookup.
-- migration-lint: allow-blocking-index widget_type is a 6-row static lookup table
CREATE INDEX idx_widget_type_label ON main.widget_type (label);
