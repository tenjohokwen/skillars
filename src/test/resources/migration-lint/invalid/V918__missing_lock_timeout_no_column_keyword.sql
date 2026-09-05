-- Fixture (skillars-deferred-92 code review): ADD without the optional COLUMN keyword used to evade
-- MISSING_LOCK_TIMEOUT entirely, because the old pattern required the literal keyword.
ALTER TABLE main.widget ADD nickname VARCHAR(50);
