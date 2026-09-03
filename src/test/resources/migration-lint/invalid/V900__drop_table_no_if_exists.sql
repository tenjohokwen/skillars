-- Fixture (rule 2): DROP TABLE without IF EXISTS. Header block present so ONLY the
-- missing-IF-EXISTS rule fires, not the missing-header rule.
DROP TABLE main.obsolete_widget;
