-- Fixture (skillars-deferred-92 AC7.2): the marker claims preparation, but a reader is still live.
-- A marker nobody checks is decoration; this is the failure mode worth catching.
-- migration-lint: drop-prepared-in: V905
SET lock_timeout = '5s';
ALTER TABLE main.widget DROP COLUMN IF EXISTS obsolete_reading;
