-- Fixture (skillars-deferred-92 AC8/AC9): a bounded lock wait and a bounded UPDATE.
SET lock_timeout = '5s';
ALTER TABLE main.widget ADD COLUMN nickname VARCHAR(50);
UPDATE main.widget SET nickname = label WHERE nickname IS NULL AND id < 1000;
