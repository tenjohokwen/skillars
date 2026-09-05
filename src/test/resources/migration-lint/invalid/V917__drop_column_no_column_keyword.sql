-- Fixture (skillars-deferred-92 code review): COLUMN is optional on a DROP clause in PostgreSQL —
-- 'ALTER TABLE t DROP col' is the identical hazard as 'ALTER TABLE t DROP COLUMN col'. No marker,
-- no lock_timeout, no IF EXISTS: proves all three rules see the bare-keyword spelling.
ALTER TABLE main.widget DROP legacy_flag;
