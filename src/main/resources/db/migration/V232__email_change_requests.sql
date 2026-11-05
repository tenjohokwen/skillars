-- skillars-deferred-172: account email change, verified and reversible (owner decisions D1-D6, R1-R3).
--
-- main.email_change_requests: one row per requested change. Nothing changes on the account until the link sent to
-- the new address is used (confirm, 24 h); the old address then gets an undo link (7 days) and stays reserved for
-- that window (no registration, no other change may take it). Only SHA-256 hashes of the two tokens are stored.
-- The user row is never deleted (GDPR erasure tombstones it), so there is no ON DELETE CASCADE: erasure deletes
-- these rows itself (GdprErasureService).
--
-- main.envelope_entity.envelope_entity_email_template_check widened with EMAIL_CHANGED_NOTICE and
-- EMAIL_CHANGE_ADDRESS_IN_USE. Rebuilt as the union: V227's 60 values + these two. EnvelopeEntitySchemaIT pins
-- enum <-> constraint parity. MERGE NOTE: any other branch widening this CHECK must re-union the list.
--
-- Migration conventions (docs/deployment/migration-conventions.md), mirroring V220/V222/V227/V228:
--  - New, empty table; its code ships in the same release (rules 1 and 5), prod not live.
--  - The CHECK swap is validating on a small table, prod not live; the new list is a superset of the old.
--  - Rule 7 (pollers): EmailRetryScheduler polls envelope_entity every 60 s, and the FK takes a lock on
--    main."user"; lock_timeout makes both fail fast instead of queueing.
SET LOCAL lock_timeout = '5s';

CREATE TABLE main.email_change_requests (
    id                 bigint      NOT NULL PRIMARY KEY,
    version            bigint      NOT NULL DEFAULT 0,
    user_id            bigint      NOT NULL REFERENCES main."user" (id),
    old_email          text        NOT NULL,
    old_login          text        NOT NULL,
    new_email          text        NOT NULL,
    confirm_token_hash text        NOT NULL UNIQUE,
    confirm_expires_at timestamptz NOT NULL,
    confirmed_at       timestamptz,
    undo_token_hash    text        UNIQUE,
    undo_expires_at    timestamptz,
    undone_at          timestamptz,
    superseded_at      timestamptz,
    created_at         timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT chk_ecr_new_email_lower CHECK (new_email = lower(new_email)),
    CONSTRAINT chk_ecr_undo_after_confirm CHECK (undo_token_hash IS NULL OR confirmed_at IS NOT NULL)
);

-- One open (unconfirmed, not superseded) request per user.
-- migration-lint: allow-blocking-index new empty table, created in this migration.
CREATE UNIQUE INDEX ux_ecr_one_open_per_user ON main.email_change_requests (user_id)
    WHERE confirmed_at IS NULL AND superseded_at IS NULL;

-- migration-lint: allow-blocking-index new empty table, created in this migration.
CREATE INDEX idx_ecr_user ON main.email_change_requests (user_id);

-- The reservation lookup: is this address the old address of a change still inside its undo window?
-- migration-lint: allow-blocking-index new empty table, created in this migration.
CREATE INDEX idx_ecr_old_email_lower ON main.email_change_requests (lower(old_email))
    WHERE confirmed_at IS NOT NULL AND undone_at IS NULL;

ALTER TABLE main.envelope_entity
    DROP CONSTRAINT IF EXISTS envelope_entity_email_template_check,
    -- migration-lint: allow-validating-constraint prod not live (dev/uat data only, as V212/V223/V227), no
    -- bulk-insert path; a fast validating CHECK swap, not a rolling-deploy lock-exposure risk.
    ADD CONSTRAINT envelope_entity_email_template_check CHECK (((email_template)::text = ANY ((ARRAY[
        'NONE'::character varying,
        'ACTIVATION'::character varying,
        'CREATION_DUP'::character varying,
        'PASSWORD_RESET'::character varying,
        'SEND_OTP'::character varying,
        'COACH_EMAIL_VERIFY'::character varying,
        'COACH_OTP'::character varying,
        'PARENT_EMAIL_VERIFY'::character varying,
        'PARENT_OTP'::character varying,
        'PLAYER_EMAIL_VERIFY'::character varying,
        'PLAYER_OTP'::character varying,
        'EMAIL_CHANGE'::character varying,
        'PROFILE_CHANGE'::character varying,
        'BOOKING_REQUESTED'::character varying,
        'BOOKING_CONFIRMED'::character varying,
        'BOOKING_DECLINED'::character varying,
        'BOOKING_PAYMENT_UNRESOLVED'::character varying,
        'BOOKING_EXPIRED'::character varying,
        'BOOKING_REMINDER'::character varying,
        'BOOKING_QUICK_COMPLETE_CONFIRM'::character varying,
        'BOOKING_RESCHEDULE_REQUESTED'::character varying,
        'BOOKING_RESCHEDULE_ACCEPTED'::character varying,
        'BOOKING_RESCHEDULE_DECLINED'::character varying,
        'BOOKING_RESCHEDULE_REQUESTED_BY_COACH'::character varying,
        'BOOKING_RESCHEDULE_DECLINED_BY_PARENT'::character varying,
        'BOOKING_DUPLICATE_PROPOSED'::character varying,
        'BOOKING_BATCH_REQUESTED'::character varying,
        'BOOKING_BATCH_ACCEPTED'::character varying,
        'SESSION_PACK_EXPIRY_WARNING'::character varying,
        'SESSION_PACK_EXPIRED'::character varying,
        'BOOKING_CANCELLED_DUE_TO_PAUSE'::character varying,
        'PACK_PAUSED'::character varying,
        'PERFORMANCE_REPORT_SHARED'::character varying,
        'VIDEO_MODERATION_ADMIN_ALERT'::character varying,
        'VIDEO_MODERATION_OWNER_FLAGGED'::character varying,
        'BOOKING_CANCELLED_BY_PARENT'::character varying,
        'BOOKING_CANCELLED_BY_COACH'::character varying,
        'COACH_NO_SHOW'::character varying,
        'PLAYER_NO_SHOW'::character varying,
        'COACH_VISIBILITY_REDUCED'::character varying,
        'SES_CUTOVER_PREFLIGHT'::character varying,
        'VIDEO_APPROVAL_REQUESTED'::character varying,
        'VIDEO_APPROVAL_APPROVED'::character varying,
        'VIDEO_APPROVAL_REJECTED'::character varying,
        'COACH_SUSPENDED'::character varying,
        'COACH_REINSTATED'::character varying,
        'COACH_WARNING_ISSUED'::character varying,
        'DISPUTE_RESOLVED'::character varying,
        'DISPUTE_DISMISSED'::character varying,
        'GDPR_EXPORT_READY'::character varying,
        'REVIEW_APPROVED'::character varying,
        'REVIEW_BLOCKED'::character varying,
        'BOOKING_CANCELLED_COACH_UNAVAILABLE'::character varying,
        'BOOKING_CANCELLED_BY_SKILLARS'::character varying,
        'DISPUTE_COACH_UPHELD'::character varying,
        'SESSION_FORFEITED_NO_SHOW'::character varying,
        'SESSION_FORFEITED_LATE_CANCEL'::character varying,
        'COACH_START_REMINDER'::character varying,
        'COACH_SAFEGUARDING_EXPIRING'::character varying,
        'REGISTRATION_PHONE_IN_USE'::character varying,
        'EMAIL_CHANGED_NOTICE'::character varying,
        'EMAIL_CHANGE_ADDRESS_IN_USE'::character varying
    ])::text[])));
