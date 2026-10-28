-- Admin console: account enforcement + coach verification tier (feat/admin-moderation-and-enforcement).
--
-- 1. admin.admin_action_log.action_type CHECK widened with the four new admin actions:
--      COACH_TIER_CHANGE  (17 chars) - admin sets a coach's marketplace verification tier
--      USER_SUSPEND       (12 chars) - admin suspends any (non-admin) user account
--      USER_REACTIVATE    (15 chars) - admin reactivates a suspended account
--      USER_LOGIN_UNLOCK  (17 chars) - admin clears a user's failed-login rate limit
--    All fit the existing varchar(25); no column widening needed.
--
--    NOT VALID / VALIDATE skipped (migration-lint: allow-validating-constraint, marker on the clause
--    below), mirroring V162__admin_action_log_booking_force_complete_type.sql's reasoning verbatim:
--    admin.admin_action_log is a small internal admin table with no bulk-insert path, so a validating
--    CHECK swap is a fast full-table scan. Rule 5 (widen one release before the first write): no
--    production deploy of this application has ever happened, so - as at V149/V152/V153/V162 - the
--    widening and its first write ship together.
--
-- 2. main.login_attempts.subject_hash: SHA-256 of the lower-cased login email ALONE. The existing
--    `identifier` is SHA-256(email|ip) (AuthService.login's account-DoS-safe rate-limit key), which is
--    deliberately not reversible to an email - so an admin "unlock this user's login" could never find
--    that user's rows. This additive, nullable column lets AuthService.clearLoginRateLimit delete every
--    attempt row for one account across all IPs. Rows written before this column existed keep NULL;
--    they age out of the (default 15-minute) rate-limit window on their own and are purged by
--    AuthCleanupService. Indexed on (subject_hash, attempted_at): its readers are the admin user-detail
--    view (count inside the lock window) and the admin unlock DELETE, and under a credential-stuffing
--    attack the table can hold ~24h of failed attempts, so neither should scan it.
SET LOCAL lock_timeout = '5s';

ALTER TABLE admin.admin_action_log
    DROP CONSTRAINT IF EXISTS admin_action_log_action_type_check,
    -- migration-lint: allow-validating-constraint small, internal admin table; no bulk-insert path,
    -- prod not live (V162 precedent) - a fast validating CHECK swap, not a rolling-deploy lock risk.
    ADD CONSTRAINT admin_action_log_action_type_check CHECK (((action_type)::text = ANY ((ARRAY[
        'MESSAGE_APPROVE'::character varying,
        'MESSAGE_BLOCK'::character varying,
        'CONVERSATION_UNBLOCK'::character varying,
        'REVIEW_APPROVE'::character varying,
        'REVIEW_BLOCK'::character varying,
        'COACH_SUSPEND'::character varying,
        'COACH_REINSTATE'::character varying,
        'COACH_STRIKE_ISSUED'::character varying,
        'COACH_STRIKE_DELETED'::character varying,
        'DISPUTE_RESOLVE'::character varying,
        'BOOKING_FORCE_COMPLETE'::character varying,
        'COACH_TIER_CHANGE'::character varying,
        'USER_SUSPEND'::character varying,
        'USER_REACTIVATE'::character varying,
        'USER_LOGIN_UNLOCK'::character varying
    ])::text[])));

ALTER TABLE main.login_attempts
    ADD COLUMN IF NOT EXISTS subject_hash character varying(64);

-- migration-lint: allow-blocking-index <reason: the column is brand new (all NULL) and the table only
-- holds ~24h of failed logins; CREATE INDEX CONCURRENTLY has no working online-safe mechanism in this
-- repo's Flyway runner yet (migration-conventions.md rule 4) and there is no production deployment>
CREATE INDEX IF NOT EXISTS idx_la_subject_hash ON main.login_attempts (subject_hash, attempted_at);
