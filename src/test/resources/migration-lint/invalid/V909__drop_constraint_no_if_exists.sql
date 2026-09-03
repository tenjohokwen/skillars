-- Fixture (skillars-deferred-91 code review): DROP_NO_IF_EXISTS covered TABLE/COLUMN/INDEX but not
-- CONSTRAINT, so V124's own unguarded 'DROP CONSTRAINT chk_bp_status' was unlinted. An unguarded
-- DROP CONSTRAINT fails hard on any environment where the constraint is already absent.
ALTER TABLE main.widget DROP CONSTRAINT chk_widget_status;
