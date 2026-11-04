-- skillars-deferred-167 (AC1): give a refresh-token rotation chain a stable identity, and validate the
-- self-referential FK that V224 shipped NOT VALID.
--
-- Why session_id: after skillars-deferred-166, a logout revokes the whole rotation chain (revoked_at), but
-- nothing kills the access token (JWT) that session already handed out. JWTAuthorizationFilter's fast path
-- does no DB work by design (skillars-deferred-144 AC8), and its DB-reauth branch — which runs at most once
-- per DB_REFRESH_TOKEN_INTERVAL (5 min) — only compared the JWT against the ACCOUNT-wide revocation epoch
-- (security_session_invalidated_at, V158). A revoked-token replay deliberately does not bump that epoch
-- (deferred-166 AC3, so a stale tab cannot sign the user out of every device), so after that story nothing
-- bumped it on logout at all, and a copied `potc` cookie outlived the logout meant to end it.
--
-- session_id is the chain's identity: minted at login, copied onto each successor on rotation, and carried
-- in the JWT as a claim. The filter's DB-reauth branch then asks one indexed question — "does this session
-- still have a live refresh token?" — and refuses the JWT when the answer is no. That gives per-session
-- revocation without touching the account-wide epoch, which is exactly what deferred-166 AC3 forbids.
--
-- NOT a credential: session_id identifies a chain, it is readable in any JWT, and nothing is ever
-- authorised by possessing it. It is only ever an argument to "is anything live for this chain".
--
-- Existing rows get session_id = NULL and there is deliberately no backfill: a NULL means "unknown" and
-- must never match. AuthService mints a fresh id when it rotates a row whose own id is null, so a
-- pre-V225 chain acquires one on its next rotation rather than staying null forever; and a JWT with no
-- claim skips the check entirely (deferred-167 AC5, fail-open) because failing closed here would sign out
-- every live session the moment this deploys.
--
-- Migration conventions (docs/deployment/migration-conventions.md):
--  - Rule 1 (additive first): one nullable column, no backfill, no default, no rewrite. ADD COLUMN ... NULL
--    is metadata-only on PG 11+.
--  - Rule 3 (two-step constraints): this is the SECOND step for fk_refresh_tokens_replaced_by, which V224
--    added NOT VALID and whose header promised the VALIDATE here. VALIDATE takes only
--    SHARE UPDATE EXCLUSIVE, so concurrent reads and writes continue during its scan.
--  - Rule 7 (pollers): AuthCleanupService's hourly DELETE and every live /api/auth/refresh touch this
--    table, so lock_timeout makes these statements fail fast instead of queueing behind one.
--
-- ONE CONVENTION THIS BENDS, deliberately and with the reason recorded: Flyway runs each migration in a
-- single transaction, so the ACCESS EXCLUSIVE lock taken by ADD COLUMN is held until this migration
-- commits — across the CREATE INDEX and the VALIDATE below. That defeats the lock-level argument rule 3
-- makes for validating in a later migration (VALIDATE alone would take only SHARE UPDATE EXCLUSIVE).
-- Accepted here because main.refresh_tokens is small by construction (bounded by logins x rotations inside
-- the 7-day TTL, swept hourly) so all three statements together are short, and because prod is not live
-- (dev/uat only, as V168/V183/V185/V189/V198/V212/V223/V224). MigrationLint does not recognise
-- VALIDATE CONSTRAINT as a lock-taking statement at all, so nothing flags this automatically — hence this
-- paragraph. If this table ever grows, split the VALIDATE into its own migration.
SET LOCAL lock_timeout = '5s';

ALTER TABLE main.refresh_tokens
    ADD COLUMN IF NOT EXISTS session_id uuid;

-- migration-lint: allow-blocking-index prod is not live (dev/uat only, as V224 and the migrations its
-- header lists); main.refresh_tokens holds on the order of 1e3-1e4 rows in dev/uat (one row per login plus
-- one per rotation, all inside the 7-day TTL, swept hourly by AuthCleanupService), so this build is a
-- sub-second ACCESS SHARE scan rather than a rolling-deploy lock exposure. CONCURRENTLY is unusable here:
-- Flyway runs each migration in one transaction, and docs/deployment/migration-conventions.md rule 4
-- records that the executeInTransaction=false sidecar self-deadlocks against Flyway's own bookkeeping
-- connection. NOT partial, unlike idx_rt_replaced_by: this index answers a lookup BY session_id for the
-- filter's liveness check, so it must cover every row that carries one.
CREATE INDEX IF NOT EXISTS idx_rt_session_id ON main.refresh_tokens USING btree (session_id);

-- The second half of V224's two-step FK (migration-conventions rule 3). No opt-out marker: this is the
-- conventional shape, and MigrationLint's VALIDATING_CONSTRAINT rule only fires on an ADD CONSTRAINT that
-- omits NOT VALID, which this is not. Cannot fail on existing data: replaced_by_id is only ever written
-- from a successor row that was just flushed in the same transaction (AuthService.decideAndRotate), and
-- the FK's ON DELETE SET NULL action is enforced even while the constraint is NOT VALID, so the hourly
-- sweep cannot leave a dangling id behind either.
ALTER TABLE main.refresh_tokens VALIDATE CONSTRAINT fk_refresh_tokens_replaced_by;

COMMENT ON COLUMN main.refresh_tokens.session_id IS
    'skillars-deferred-167: identity of the rotation chain this token belongs to — minted at login, copied '
    'onto each successor, and carried in the access token as a claim so the JWT filter can refuse a token '
    'whose chain has no live row left (per-session revocation, without touching the account-wide '
    'security_session_invalidated_at epoch). NULL on rows written before V225 (no backfill): NULL means '
    'unknown and never matches; a rotation of such a row mints a fresh id for the successor. Not a '
    'credential — nothing is authorised by possessing it.';
