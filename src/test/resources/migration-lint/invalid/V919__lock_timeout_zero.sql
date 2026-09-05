-- Fixture (skillars-deferred-92 code review): SET lock_timeout = 0 is PostgreSQL's own spelling for
-- "wait forever" — the old rule only checked the keyword's presence, never its value.
SET lock_timeout = 0;
ALTER TABLE main.widget ADD COLUMN nickname VARCHAR(50);
