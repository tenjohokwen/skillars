-- skillars-deferred-125 AC4: SET LOCAL is transaction-scoped and resets automatically at
-- COMMIT/ROLLBACK, unlike a plain session-scoped SET. Proves SET LOCAL satisfies BOTH the
-- pre-existing MISSING_LOCK_TIMEOUT rule (its regex already accepts either spelling) and the new
-- SESSION_SCOPED_LOCK_TIMEOUT rule (which a plain SET, not SET LOCAL, triggers) — above this
-- fixture set's own FIXTURE_SESSION_SCOPED_LOCK_TIMEOUT_BASELINE.
SET LOCAL lock_timeout = '5s';

ALTER TABLE main.widget ADD COLUMN local_lock_probe VARCHAR(10);
