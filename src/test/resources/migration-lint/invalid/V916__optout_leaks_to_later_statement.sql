-- Fixture (skillars-deferred-92 AC11.2): the opt-out below belongs to the FIRST index only. Before
-- AC11.2 markers were matched against the whole file, so this single opt-out silenced the rule for
-- every statement in the migration — including the second index, on a table it says nothing about.
SET lock_timeout = '5s';
-- migration-lint: allow-blocking-index widget_type is a 6-row static lookup table
CREATE INDEX idx_widget_type_label ON main.widget_type (label);

CREATE INDEX idx_widget_owner ON main.widget (owner_id);
