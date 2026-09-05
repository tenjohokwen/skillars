-- Fixture (skillars-deferred-92 code review): opt-out text appearing inside a STRING LITERAL must
-- not be honoured as a real marker — an UPDATE could otherwise opt itself out of UNBATCHED_DML via
-- its own data.
SET lock_timeout = '5s';
UPDATE main.widget SET note = 'migration-lint: allow-full-table-dml planted in data, not a comment';
