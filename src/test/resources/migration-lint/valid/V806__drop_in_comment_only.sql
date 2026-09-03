-- Fixture: the words DROP TABLE and CREATE INDEX appear only in this comment, never as
-- real DDL, so comment-stripping must keep this file clean.
--   (we intentionally do NOT "DROP TABLE main.widget" here)
ALTER TABLE main.widget ADD COLUMN note text;
