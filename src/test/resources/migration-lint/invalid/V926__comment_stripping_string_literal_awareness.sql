-- Fixture (skillars-deferred-92 code review): a '--' inside a string literal default value used to
-- be treated as starting a real comment, deleting everything after it in the statement — hiding the
-- DROP COLUMN clause below from every rule. This carries no marker, so its firing here is the proof
-- that the clause survived comment-stripping; if the old bug were still present, nothing would fire.
SET lock_timeout = '5s';
ALTER TABLE main.widget ADD COLUMN note text DEFAULT 'a--b', DROP COLUMN legacy_col;
