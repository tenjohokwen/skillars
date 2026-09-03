-- Fixture: looks like a versioned Flyway migration but the version cannot be parsed.
-- Before the decimal-version fix (code review, 3-layer run) any such name simply fell through the
-- VERSIONED pattern and was skipped silently, so its contents were never linted.
ALTER TABLE main.example DROP COLUMN legacy_flag;
