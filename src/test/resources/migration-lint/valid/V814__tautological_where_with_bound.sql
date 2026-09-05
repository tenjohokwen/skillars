-- Fixture (skillars-deferred-92 code review): 'WHERE 1=1 AND <bound predicate>' is a standard
-- generated-SQL idiom and genuinely bounds the row count — it must not be flagged as unbatched.
SET lock_timeout = '5s';
UPDATE main.widget SET label = 'x' WHERE 1=1 AND id = 7;
