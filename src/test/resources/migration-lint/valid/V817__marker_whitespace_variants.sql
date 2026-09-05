-- Fixture (skillars-deferred-92 code review): whitespace after the marker's colon must not be
-- mandatory. Two spellings, neither with exactly one space, both honoured.
SET lock_timeout = '5s';
--migration-lint:allow-full-table-dml no space at all after either colon
UPDATE main.widget_type SET label = trim(label);

-- migration-lint:  allow-full-table-dml two spaces after the second colon
UPDATE main.widget_size SET label = trim(label);
