-- Fixture (skillars-deferred-92 code review): TRUNCATE is an unconditional full-table write with no
-- WHERE clause possible, and takes ACCESS EXCLUSIVE — the old UNBATCHED_DML pattern only recognised
-- UPDATE/DELETE, and TRUNCATE reached no lock-timeout rule either.
TRUNCATE TABLE main.widget;
