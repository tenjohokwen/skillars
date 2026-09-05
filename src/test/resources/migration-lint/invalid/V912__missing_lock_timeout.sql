-- Fixture (skillars-deferred-92 AC8): ACCESS EXCLUSIVE DDL with no bounded lock wait.
-- Only 2 of the 121 migrations in this repository set lock_timeout. Without it the ALTER waits
-- indefinitely behind a long-running reader, and — the part that actually hurts — every subsequent
-- query on the table then queues behind the blocked ALTER, so one slow SELECT can stall the whole
-- table and exhaust the connection pool.
ALTER TABLE main.widget ADD COLUMN nickname VARCHAR(50);
