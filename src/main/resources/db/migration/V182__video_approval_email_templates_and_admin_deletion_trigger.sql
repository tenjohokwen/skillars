-- B5 (video ownership + approval emails): two CHECK widenings, both additive.
--
-- 1. main.envelope_entity.email_template — the minor-safety approval gate now emails the parent
--    (VIDEO_APPROVAL_REQUESTED) and tells the player the outcome (VIDEO_APPROVAL_APPROVED /
--    VIDEO_APPROVAL_REJECTED). Rebuilt from V168's list plus those three. EnvelopeEntitySchemaIT pins
--    enum <-> constraint parity. NOTE for a concurrent branch widening the same CHECK: whichever merges
--    second must rebuild this list to contain BOTH sets of values.
-- 2. main.video_deletion_log / main.video_deletion_outbox .triggered_by — AdminVideoService.deleteVideo now
--    goes through VideoDeletionService (quota decrement, deletion log, drill-ref-aware outbox) with
--    triggered_by = 'ADMIN_DELETION' instead of a bespoke DELETED path that skipped all three.
--
-- Migration conventions (docs/deployment/migration-conventions.md), mirroring V168:
--  - NOT VALID / VALIDATE skipped (migration-lint marker on each clause below): no production deploy of
--    this application has ever happened (prod is not live), every existing database is a dev/uat one, and
--    the validating scans are small. Every existing row already satisfies the wider lists.
--  - Rule 5 (widen one release before the first write): prod is not live, so the widening and its first
--    writes ship together.
--  - Rule 7 (pollers): EmailRetryScheduler polls envelope_entity every 60 s and VideoDeletionOutboxProcessor
--    polls video_deletion_outbox; lock_timeout makes each ALTER fail fast instead of queueing behind them.
SET LOCAL lock_timeout = '5s';

ALTER TABLE main.envelope_entity
    DROP CONSTRAINT IF EXISTS envelope_entity_email_template_check,
    -- migration-lint: allow-validating-constraint prod not live (dev/uat data only, as V168), no bulk-insert
    -- path; a fast validating CHECK swap, not a rolling-deploy lock-exposure risk.
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
        'VIDEO_APPROVAL_REJECTED'::character varying
    ])::text[])));

ALTER TABLE main.video_deletion_log
    DROP CONSTRAINT IF EXISTS video_deletion_log_triggered_by_check,
    -- migration-lint: allow-validating-constraint prod not live (dev/uat data only, as V168); small table.
    ADD CONSTRAINT video_deletion_log_triggered_by_check CHECK (((triggered_by)::text = ANY ((ARRAY[
        'USER_DELETION'::character varying,
        'ACCOUNT_DELETION'::character varying,
        'SYSTEM'::character varying,
        'ADMIN_DELETION'::character varying
    ])::text[])));

ALTER TABLE main.video_deletion_outbox
    DROP CONSTRAINT IF EXISTS video_deletion_outbox_triggered_by_check,
    -- migration-lint: allow-validating-constraint prod not live (dev/uat data only, as V168); small table.
    ADD CONSTRAINT video_deletion_outbox_triggered_by_check CHECK (((triggered_by)::text = ANY ((ARRAY[
        'USER_DELETION'::character varying,
        'ACCOUNT_DELETION'::character varying,
        'SYSTEM'::character varying,
        'ADMIN_DELETION'::character varying
    ])::text[])));
