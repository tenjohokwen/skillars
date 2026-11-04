-- feature/pitchside-reg-neutral: the registrant's notice on a phone-match duplicate registration.
--
-- Registration is neutral: an email or phone already on an account creates nothing and answers like a success,
-- and the existing account is told (CREATION_DUP). Phone numbers are not verified at registration, so when the
-- match is on the phone only (the email is new), the address the registrant typed is now ALSO told, by the new
-- REGISTRATION_PHONE_IN_USE email, instead of waiting forever for a verification mail.
--
-- main.envelope_entity.envelope_entity_email_template_check widened with REGISTRATION_PHONE_IN_USE. Rebuilt as
-- the union: V223's 59 values + this one. EnvelopeEntitySchemaIT pins enum <-> constraint parity. MERGE NOTE:
-- any other branch widening this CHECK must re-union the list (and this V-number may collide with master's).
--
-- Migration conventions (docs/deployment/migration-conventions.md), mirroring V212/V219/V223:
--  - The CHECK swap is validating, small table, prod not live; the new list is a superset of the old, so every
--    existing row passes.
--  - Rule 5: the widening and its first write ship together.
--  - Rule 7 (pollers): EmailRetryScheduler polls envelope_entity every 60 s; lock_timeout makes the ALTER fail
--    fast instead of queueing behind it.
SET LOCAL lock_timeout = '5s';

ALTER TABLE main.envelope_entity
    DROP CONSTRAINT IF EXISTS envelope_entity_email_template_check,
    -- migration-lint: allow-validating-constraint prod not live (dev/uat data only, as V168/V183/V185/V189/V198/V212/V223), no
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
        'REGISTRATION_PHONE_IN_USE'::character varying
    ])::text[])));
