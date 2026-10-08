-- skillars-deferred-149 AC3: refresh-token-reuse ("theft") detection revokes refresh_tokens but
-- never touches the user row, so a standing JWT keeps self-extending via the filter's fast path
-- indefinitely. This column is a monotonic, account-wide "last theft revocation" epoch, set only
-- by UserRepository.invalidateSessionsForUser (called from AuthService.refresh()'s two
-- theft-detection branches) and NEVER cleared by login -- JWTAuthorizationFilter's DB-reauth
-- branch compares it against each JWT's own SESSION_ISSUED_AT claim, bounding a stolen JWT's
-- survival to the filter's existing 5-minute DB-reauth cycle (DB_REFRESH_TOKEN_INTERVAL)
-- PERMANENTLY, regardless of any later unrelated login. (Code review 2026-10-08: an earlier draft
-- bound this to Principal.instanceFrom's credentialsNonExpired and cleared it on login -- that
-- let a victim's own re-login re-arm a still-live stolen JWT, and separately locked the
-- still-live /authenticate endpoint out of a flagged account. See Principal.java's and
-- AuthService.login()'s own comments for the corrected mechanism.)
--
-- Additive, nullable ADD COLUMN per the expand/contract standard (migration-conventions.md rule 1).
-- timestamp without time zone, matching every other nullable timestamp column on this table
-- (activation_date, reset_expiration, account_expiration, created_date, last_modified_date).
SET LOCAL lock_timeout = '5s';

ALTER TABLE main."user"
    ADD COLUMN IF NOT EXISTS security_session_invalidated_at timestamp without time zone;

-- Code review (2026-10-08), unrelated to the column above but added to this migration because
-- V158 is new/unmerged and this is cheap: skillars-deferred-149 AC5 changed
-- ConfigStartupAssertion/ConfigService's review-eligibility cross-field guard from strict
-- ordering (gap > 0) to a minimum 7-day gap. V156's own seed (7/30, gap 23) is safe and only
-- ever applies ON CONFLICT DO NOTHING, so any environment where an operator hand-set either key
-- via PUT /api/config between V156 and this migration — legally, under the OLD rule — could hold
-- a stored pair with a gap in [1,6]. Unnormalised, that pair now fails ConfigStartupAssertion's
-- fail-fast check on every non-dev boot, with no way to fix it except direct SQL (PUT /api/config
-- needs the app already running). Idempotent: a no-op once both rows already satisfy the gap.
--
-- Prefers lowering minSessionAgeDays (loosens the maturity floor -- strictly safe, makes more
-- reviews eligible sooner) over raising updateCooldownDays (which would lengthen an operator's
-- already-configured cooldown). Only raises updateCooldownDays as a second pass, for the rarer
-- case where updateCooldownDays itself is too small to leave room for any minSessionAgeDays >= 1
-- with a 7-day gap.
WITH current_review_window AS (
    SELECT
        (SELECT value::integer FROM main.platform_config WHERE key = 'reviews.minSessionAgeDays') AS min_age,
        (SELECT value::integer FROM main.platform_config WHERE key = 'reviews.updateCooldownDays') AS cooldown
)
UPDATE main.platform_config
SET value = GREATEST(1, (SELECT cooldown FROM current_review_window) - 7)::text
WHERE key = 'reviews.minSessionAgeDays'
    AND EXISTS (SELECT 1 FROM current_review_window WHERE cooldown - min_age < 7);

WITH current_review_window AS (
    SELECT
        (SELECT value::integer FROM main.platform_config WHERE key = 'reviews.minSessionAgeDays') AS min_age,
        (SELECT value::integer FROM main.platform_config WHERE key = 'reviews.updateCooldownDays') AS cooldown
)
UPDATE main.platform_config
SET value = LEAST(365, (SELECT min_age FROM current_review_window) + 7)::text
WHERE key = 'reviews.updateCooldownDays'
    AND EXISTS (SELECT 1 FROM current_review_window WHERE cooldown - min_age < 7);
