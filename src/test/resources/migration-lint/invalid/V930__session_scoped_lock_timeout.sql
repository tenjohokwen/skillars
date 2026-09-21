-- skillars-deferred-125 AC4: a plain, session-scoped SET lock_timeout above
-- FIXTURE_SESSION_SCOPED_LOCK_TIMEOUT_BASELINE. Satisfies the pre-existing MISSING_LOCK_TIMEOUT rule
-- (bounded — its regex already accepts either spelling) but must trigger the new
-- SESSION_SCOPED_LOCK_TIMEOUT rule: a plain SET is session-scoped and silently carries forward into a
-- later migration in the same Flyway deploy session, which SET LOCAL (transaction-scoped, resets at
-- COMMIT/ROLLBACK) does not.
SET lock_timeout = '5s';

ALTER TABLE main.widget ADD COLUMN session_lock_probe VARCHAR(10);
