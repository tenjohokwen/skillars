-- Fixture (skillars-deferred-92 AC9): a full-table UPDATE is acceptable with a stated reason.
SET lock_timeout = '5s';
-- migration-lint: allow-full-table-dml main.widget_type is a 6-row static lookup table
UPDATE main.widget_type SET label = trim(label);
