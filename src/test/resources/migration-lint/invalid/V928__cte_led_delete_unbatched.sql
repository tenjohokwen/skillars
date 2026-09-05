-- Fixture (skillars-deferred-92 code review): a leading CTE used to make DELETE invisible to
-- UNBATCHED_DML, since the old anchor required the statement to start with the keyword itself.
SET lock_timeout = '5s';
WITH doomed AS (SELECT 1) DELETE FROM main.widget;
