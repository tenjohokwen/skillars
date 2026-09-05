-- Fixture (skillars-deferred-92 code review): a multi-clause DROP used to have only the FIRST
-- clause's identifier reference-scanned. This carries a valid marker and IF EXISTS on both clauses;
-- only the second column (obsolete_reading) is still read anywhere (see WidgetRepository.java) —
-- proving the scan now checks every clause, not just the first.
-- migration-lint: drop-prepared-in: V806
SET lock_timeout = '5s';
ALTER TABLE main.widget
    DROP COLUMN IF EXISTS harmless_col,
    DROP COLUMN IF EXISTS obsolete_reading;
