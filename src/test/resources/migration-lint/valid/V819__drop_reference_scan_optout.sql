-- Fixture (skillars-deferred-92 code review): allow-drop-reference-scan had no fixture and no
-- assertion anywhere. This drops a column the fixture source corpus DOES still read (the same
-- 'obsolete_reading' V911 uses to prove the scan is load-bearing) but opts out of the scan itself,
-- with a marker and a reason — proving the opt-out silences the scan without needing the marker
-- content to be right about the code (that is what the opt-out is FOR: a stated, reviewed exception).
-- migration-lint: drop-prepared-in: V806
-- migration-lint: allow-drop-reference-scan the remaining WidgetRepository.java reference is to a
-- read replica projection that is refreshed from a different column post-migration; tracked
-- separately, not a live production reader of this column.
SET lock_timeout = '5s';
ALTER TABLE main.widget DROP COLUMN IF EXISTS obsolete_reading;
