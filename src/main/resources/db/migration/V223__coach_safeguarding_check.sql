-- feat/coach-safeguarding-check (S-09): the OPTIONAL safeguarding check badge on coach profiles.
--
-- A coach may ask Skillars to inspect their enhanced criminal-record certificate (DE: erweitertes
-- Fuehrungszeugnis, code DE_EFZ). Skillars staff inspect it; the PUBLIC profile then shows "check on record,
-- valid until MM/YYYY". Nothing is gated or blocked on it. Data minimisation (GDPR Art. 10 / Art. 5(1)(c)):
-- NOTHING from the certificate is ever stored in a column, and the optional upload is deleted the moment staff
-- decide (and by a sweeper after coach.safeguarding.uploadRetentionDays).
--
-- 1. marketplace.coach_safeguarding_requests: the inspection queue. One row per coach. PENDING until staff
--    decide; REJECTED stays only so the coach sees the outcome (deleted by withdraw / a new request / erasure).
--    upload_key / upload_stored_at / upload_content_type: the optional, application-encrypted upload in the
--    private bucket (filestorage); NULL once deleted. No file name is kept (it could carry the coach's name).
-- 2. marketplace.coach_safeguarding_checks: the record behind the badge. Written only by a staff inspection
--    (inspected_by = main."user".id of the admin). expires_at = inspected_at + coach.safeguarding.validityYears;
--    after it the public badge reverts to "not on record" (read-time check, no job needed). reminder_sent_at:
--    the ~30-day expiry reminder email, reset on renewal.
-- 3. envelope_entity_email_template_check widened with COACH_SAFEGUARDING_EXPIRING (the reminder). Rebuilt as
--    the union: V219's 58 values + this one. EnvelopeEntitySchemaIT pins enum <-> constraint parity. MERGE NOTE:
--    any other branch widening this CHECK must re-union the list.
-- 4. admin.admin_action_log.action_type CHECK widened with SAFEGUARDING_INSPECTED / _REJECTED / _REVOKED /
--    _DOC_VIEWED (longest 23 chars, fits the varchar(25)). Rebuilt as the union: V189's 20 values + these four.
--    The admin-alert CHECK is untouched.
--
-- Migration conventions (docs/deployment/migration-conventions.md), mirroring V189/V212/V217:
--  - New, empty tables: constraints inline, nothing to validate.
--  - The two CHECK swaps are validating, small tables, prod not live (V162/V171/V183/V189/V212 precedent); the
--    new lists are supersets of the old, so every existing row passes (markers on the clauses below).
--  - Rule 5: the widening and its first write ship together (the V149/V152/V162/V186 owner decision).
--  - Rule 7 (pollers): EmailRetryScheduler polls envelope_entity every 60 s; lock_timeout makes the ALTER
--    fail fast instead of queueing behind it.
SET LOCAL lock_timeout = '5s';

CREATE TABLE IF NOT EXISTS marketplace.coach_safeguarding_requests (
    coach_id            uuid         NOT NULL PRIMARY KEY REFERENCES marketplace.coach_profiles (id),
    status              varchar(16)  NOT NULL,
    check_type          varchar(16)  NOT NULL,
    requested_at        timestamptz  NOT NULL,
    upload_key          varchar(512),
    upload_stored_at    timestamptz,
    upload_content_type varchar(64),
    decided_at          timestamptz,
    version             bigint       NOT NULL DEFAULT 0,
    CONSTRAINT chk_csr_status CHECK (status IN ('PENDING', 'REJECTED')),
    CONSTRAINT chk_csr_check_type CHECK (check_type ~ '^[A-Z0-9_]{2,16}$'),
    CONSTRAINT chk_csr_upload_pair CHECK ((upload_key IS NULL) = (upload_stored_at IS NULL))
);

-- The sweeper's lookup (uploads older than the retention window) and the admin queue's ordering.
-- migration-lint: allow-blocking-index new empty table, nothing to scan.
CREATE INDEX IF NOT EXISTS idx_csr_upload_stored ON marketplace.coach_safeguarding_requests USING btree (upload_stored_at)
    WHERE upload_key IS NOT NULL;
-- migration-lint: allow-blocking-index new empty table, nothing to scan.
CREATE INDEX IF NOT EXISTS idx_csr_pending ON marketplace.coach_safeguarding_requests USING btree (requested_at)
    WHERE status = 'PENDING';

CREATE TABLE IF NOT EXISTS marketplace.coach_safeguarding_checks (
    coach_id         uuid        NOT NULL PRIMARY KEY REFERENCES marketplace.coach_profiles (id),
    check_type       varchar(16) NOT NULL,
    inspected_at     timestamptz NOT NULL,
    expires_at       timestamptz NOT NULL,
    inspected_by     bigint      NOT NULL,
    reminder_sent_at timestamptz,
    version          bigint      NOT NULL DEFAULT 0,
    CONSTRAINT chk_csc_check_type CHECK (check_type ~ '^[A-Z0-9_]{2,16}$'),
    CONSTRAINT chk_csc_expires_after_inspected CHECK (expires_at > inspected_at)
);

-- The public badge read ("valid" = expires_at in the future) and the reminder sweep.
-- migration-lint: allow-blocking-index new empty table, nothing to scan.
CREATE INDEX IF NOT EXISTS idx_csc_expires ON marketplace.coach_safeguarding_checks USING btree (expires_at);

ALTER TABLE main.envelope_entity
    DROP CONSTRAINT IF EXISTS envelope_entity_email_template_check,
    -- migration-lint: allow-validating-constraint prod not live (dev/uat data only, as V168/V183/V185/V189/V198/V212), no
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
        'COACH_SAFEGUARDING_EXPIRING'::character varying
    ])::text[])));

ALTER TABLE admin.admin_action_log
    DROP CONSTRAINT IF EXISTS admin_action_log_action_type_check,
    -- migration-lint: allow-validating-constraint small, internal admin table; no bulk-insert path,
    -- prod not live (V162/V171/V183/V189 precedent) - a fast validating CHECK swap, not a rolling-deploy lock risk.
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
        'USER_LOGIN_UNLOCK'::character varying,
        'DISPUTE_DISMISS'::character varying,
        'APPEAL_UPHOLD'::character varying,
        'APPEAL_OVERTURN'::character varying,
        'COACH_SAFETY_HOLD'::character varying,
        'BOOKING_ADMIN_CANCEL'::character varying,
        'SAFEGUARDING_INSPECTED'::character varying,
        'SAFEGUARDING_REJECTED'::character varying,
        'SAFEGUARDING_REVOKED'::character varying,
        'SAFEGUARDING_DOC_VIEWED'::character varying
    ])::text[])));
