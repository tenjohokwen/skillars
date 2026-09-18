-- skillars-deferred-122 AC9: a marker for main."user" rows that UserAdminService's daily
-- non-activated-user cleanup sweep (removeNotActivatedUsers) repeatedly fails to delete (an
-- uncovered FK, a trigger, a constraint). Excludes the row from every subsequent run's candidate set
-- (see the paged query in UserAdminService.findExpiredUsers), stopping a deterministically-undeletable
-- user from producing the identical recurring ERROR log on every run, and giving operators something
-- to query: SELECT * FROM main."user" WHERE cleanup_failed_at IS NOT NULL.
--
-- code review 2026-09-18 (Resolved: option 2, applied before this migration ever shipped): stamping
-- cleanup_failed_at on the very first failure could not distinguish a deterministically-undeletable
-- user from a transient one (a lock timeout, deadlock, or connection reset) — one bad night would
-- permanently exclude a perfectly recoverable user. cleanup_failed_attempts/cleanup_last_attempted_at/
-- cleanup_last_error track each separate-run failure (mirroring OutboxReplicationJob's
-- attemptCount/lastAttemptedAt/errorMessage idiom); cleanup_failed_at is now stamped only once
-- UserAdminService.CLEANUP_FAILURE_THRESHOLD consecutive separate-run failures have been recorded.
--
-- timestamp without time zone, not timestamptz: matches every other nullable timestamp column
-- already on this table (activation_date, reset_expiration, account_expiration, created_date,
-- last_modified_date — V138__baseline_schema.sql), consistent with hibernate.jdbc.time_zone: UTC
-- and this codebase's Instant-typed fields throughout.
--
-- User is Envers-@Audited with a real main.user_aud table, but all four new/existing columns here are
-- annotated @NotAudited on the entity — they are operational marker state, not user-facing auditable
-- history — so no matching user_aud column is needed for any of them.
--
-- Additive, nullable/defaulted ADD COLUMNs per the expand/contract standard (docs/deployment/
-- migration-conventions.md rule 1); mirrors V140__envelope_entity_recipients_delivered_flag.sql's
-- exact shape (rationale header, SET lock_timeout, ADD COLUMN IF NOT EXISTS).
SET lock_timeout = '5s';

ALTER TABLE main."user"
    ADD COLUMN IF NOT EXISTS cleanup_failed_at timestamp without time zone,
    ADD COLUMN IF NOT EXISTS cleanup_failed_attempts integer NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS cleanup_last_attempted_at timestamp without time zone,
    ADD COLUMN IF NOT EXISTS cleanup_last_error text;
