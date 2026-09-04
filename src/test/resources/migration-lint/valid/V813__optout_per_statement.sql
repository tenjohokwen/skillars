-- Fixture (skillars-deferred-92 AC11.2): two blocking indexes, each with its OWN opt-out. Under the
-- old whole-file matching a single marker covered both; each must now carry its own.
SET lock_timeout = '5s';
-- migration-lint: allow-blocking-index widget_type is a 6-row static lookup table
CREATE INDEX idx_widget_type_label ON main.widget_type (label);

-- migration-lint: allow-blocking-index widget is empty at deploy in this fixture's scenario
CREATE INDEX idx_widget_owner ON main.widget (owner_id);
