-- Fixture: guarded drop. The last reader of legacy_widget was removed in the prior
-- release; this drop carries IF EXISTS and this header block (rule 2).
DROP TABLE IF EXISTS main.legacy_widget;
ALTER TABLE main.widget DROP COLUMN IF EXISTS deprecated_flag;
DROP INDEX IF EXISTS main.idx_widget_deprecated;
