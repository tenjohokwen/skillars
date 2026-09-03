-- Fixture: the guarded shape of V909 — an idempotent DROP CONSTRAINT that is safe to re-run.
ALTER TABLE main.widget DROP CONSTRAINT IF EXISTS chk_widget_status;
