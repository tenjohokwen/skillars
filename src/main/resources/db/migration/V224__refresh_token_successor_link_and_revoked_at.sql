-- skillars-deferred-166 (ledger RS01 + RS02): give a rotated refresh token a link to its successor,
-- and distinguish "rotated" (replaced; the successor is live) from "revoked" (the session was ended).
--
-- Why: main.refresh_tokens had neither, so every path that needed "the token this one became" either
-- guessed or gave up.
--   RS01 (logout): SecurityUtil.terminateSession revoked only the presented row, and only while it was
--     still unused (markUsedByTokenHash's `AND used = false`). A refresh that rotated the token a moment
--     earlier left its successor live for the rest of the 7-day TTL — "Logout" did not end the session.
--   RS02 (refresh grace window): a stale token replayed within AuthService.REFRESH_GRACE_WINDOW (30 s) was
--     redirected via findFirstByUserIdAndUsedFalseAndExpiresAtAfterOrderByCreatedAtDesc, i.e. the newest
--     live token of the WHOLE ACCOUNT — another device's session. That device's cookie then held a used
--     token, and its next refresh tripped theft detection and logged out every session of the user.
--
-- 1. replaced_by_id: the successor row's id, written by AuthService.refresh in the same transaction as the
--    rotation. Logout walks predecessor -> successor to revoke the whole chain; the grace window follows
--    this link only, never "newest live". Self-referential FK, ON DELETE SET NULL because
--    AuthCleanupService.deleteExpiredTokens bulk-deletes by expires_at and may remove a successor first.
-- 2. revoked_at: set only when a session is deliberately ended (logout, forced logout, denial). `used`
--    stays what it has always been — a monotonic flag set by BOTH rotation and revocation — so the pair is
--    what tells the two apart: used + revoked_at IS NULL = rotated (grace window may redirect);
--    revoked_at IS NOT NULL = ended (401, and deliberately NOT account-wide theft revocation, so a stale
--    tab replaying a logged-out token cannot sign the user out of their other devices).
--
-- Existing rows get replaced_by_id = NULL and revoked_at = NULL, which reads as "rotated, successor
-- unknown". AuthService treats a used token with no successor link as reuse (the pre-V224 behaviour for a
-- replay), so the only effect is on a token rotated before this migration and replayed inside its 30 s
-- window: that is theft-detected rather than redirected. Accepted and bounded (30 s per token, once; the
-- SPA does not call POST /api/auth/refresh at all — src/frontend/src/api/auth.api.js).
--
-- Migration conventions (docs/deployment/migration-conventions.md):
--  - Additive only: two nullable columns, no backfill, no DROP, no rewrite (ADD COLUMN ... NULL is
--    metadata-only on PG 11+).
--  - Rule 3: the FK goes on a table that grows, so it is added NOT VALID here; the VALIDATE ships in a
--    later migration (filed in deferred-work.md by this story). NOT VALID takes only a brief
--    ACCESS EXCLUSIVE lock and skips the full-table scan.
--  - replaced_by_id IS indexed, and not for any query: PostgreSQL does not index a referencing column
--    automatically, and ON DELETE SET NULL runs a referential-action trigger that searches it
--    (WHERE replaced_by_id = <deleted id>) once per deleted row. AuthCleanupService.purgeExpiredRefreshTokens
--    bulk-deletes by expires_at every hour, so without the index that sweep is one sequential scan per
--    deleted row, holding its locks long enough to risk outliving its own @SchedulerLock lease. Partial
--    (WHERE NOT NULL): only rotated rows carry a successor. revoked_at needs none — it is read on a row
--    already fetched by hash or id, never as a search predicate.
--  - Rule 7 (pollers): AuthCleanupService's hourly DELETE and every live /api/auth/refresh touch this
--    table, so lock_timeout makes these ALTERs fail fast instead of queueing behind one.
SET LOCAL lock_timeout = '5s';

ALTER TABLE main.refresh_tokens
    ADD COLUMN IF NOT EXISTS replaced_by_id bigint,
    ADD COLUMN IF NOT EXISTS revoked_at     timestamptz;

ALTER TABLE main.refresh_tokens
    DROP CONSTRAINT IF EXISTS fk_refresh_tokens_replaced_by,
    ADD CONSTRAINT fk_refresh_tokens_replaced_by
        FOREIGN KEY (replaced_by_id) REFERENCES main.refresh_tokens (id) ON DELETE SET NULL NOT VALID;

-- migration-lint: allow-blocking-index prod is not live (dev/uat only, as V168/V183/V185/V189/V198/V212/V223);
-- refresh_tokens is small by construction (bounded by logins x rotations inside the 7-day TTL, swept hourly),
-- so the build is a short ACCESS SHARE scan, not a rolling-deploy lock exposure. CONCURRENTLY cannot be used:
-- Flyway runs each migration in one transaction.
CREATE INDEX IF NOT EXISTS idx_rt_replaced_by ON main.refresh_tokens USING btree (replaced_by_id)
    WHERE replaced_by_id IS NOT NULL;

COMMENT ON COLUMN main.refresh_tokens.replaced_by_id IS
    'skillars-deferred-166: the successor row this token was rotated into, or NULL when it was never '
    'rotated (or was rotated before V224). Logout revokes the whole chain by walking it; the refresh '
    'grace window redirects a stale token only along this link.';
COMMENT ON COLUMN main.refresh_tokens.revoked_at IS
    'skillars-deferred-166: when the session was deliberately ended (logout / forced logout / denial), '
    'NULL when the row is merely rotated. used = true + revoked_at IS NULL means rotated; revoked_at '
    'IS NOT NULL means ended, which refresh answers with 401 and no account-wide revocation.';
