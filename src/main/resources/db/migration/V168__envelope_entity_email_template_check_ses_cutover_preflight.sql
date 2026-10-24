-- ses-1.5 AC3: allow the SES cutover preflight's envelope template in main.envelope_entity.
--
-- V138's baseline envelope_entity_email_template_check lists 40 EmailTemplate values; the enum has 41. The only
-- one missing is SES_CUTOVER_PREFLIGHT (added with skillars-deferred-114 AC5, which shipped without a
-- migration). SesCutoverPreflightResource sends its test email through MailManager.sendEmailSync and persists the
-- envelope AFTER the send (saveAndFlush, deliberately left to throw - see the MailManager comment), so without
-- this the preflight endpoint fails with a check violation after SES has already accepted the mail, and the
-- FAILED-row path fails the same way. EnvelopeEntitySchemaIT pins enum <-> constraint parity from now on.
--
-- Migration conventions (docs/deployment/migration-conventions.md), mirroring
-- V162__admin_action_log_booking_force_complete_type.sql:
--  - NOT VALID / VALIDATE skipped (migration-lint: allow-validating-constraint, marker on the clause below):
--    main.envelope_entity keeps every envelope (nothing deletes terminal rows), but no production deploy of this
--    application has ever happened (prod is not live), so every existing database is a dev/uat one and the
--    validating scan is small - the same reason V162 gives (rule 3). There is no bulk-insert path.
--  - Rule 5 (widen one release before the first write): the only writer of this value is the admin-only
--    preflight endpoint, which is manual and exists only under app.email.transport=ses; no production deploy of
--    this application has ever happened (prod is not live), so the widening and its first write ship together.
--  - Rule 7 (pollers): EmailRetryScheduler polls this table every 60 s. The ALTER holds ACCESS EXCLUSIVE for the
--    duration of the scan only; lock_timeout below makes it fail fast instead of queueing behind that poller.
SET LOCAL lock_timeout = '5s';

ALTER TABLE main.envelope_entity
    DROP CONSTRAINT IF EXISTS envelope_entity_email_template_check,
    -- migration-lint: allow-validating-constraint prod not live (dev/uat data only, as V162), no bulk-insert
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
        'SES_CUTOVER_PREFLIGHT'::character varying
    ])::text[])));
