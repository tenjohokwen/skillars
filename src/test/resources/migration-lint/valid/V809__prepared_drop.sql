-- Fixture (skillars-deferred-92 AC7): a correctly sequenced DROP. The marker names the release that
-- removed the last reader, and no live reference to the identifier remains in the scanned sources.
-- migration-lint: drop-prepared-in: V806
SET lock_timeout = '5s';
ALTER TABLE main.widget DROP COLUMN IF EXISTS fully_retired_column;
