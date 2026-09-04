-- Fixture (skillars-deferred-92 AC9): a WHERE-less UPDATE locks every row for the whole statement.
SET lock_timeout = '5s';
UPDATE main.widget SET label = upper(label);
