-- Fixture (skillars-deferred-92 AC7): a DROP COLUMN with no drop-prepared-in marker.
-- This is skillars-11-3 D2's exact defect — the code deletion and the destructive migration ship
-- together, so during a rolling deploy the old pods are still reading the column when the new pod's
-- migration removes it. Passed the lint clean before AC7.
SET lock_timeout = '5s';
ALTER TABLE main.widget DROP COLUMN IF EXISTS legacy_flag;
