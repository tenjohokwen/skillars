-- Fixture: an R__ repeatable with only idempotent, non-blocking content — safe to re-run on every
-- checksum change.
CREATE OR REPLACE VIEW main.active_widgets AS SELECT * FROM main.widget WHERE status = 'ACTIVE';
