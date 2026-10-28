-- B6 (feat/admin-outcome-notifications): admin-outcome notifications and admin audit completeness.
--
-- 1. main.envelope_entity.envelope_entity_email_template_check widened with the eight admin-outcome
--    EmailTemplate values AdminOutcomeEmailListener sends: COACH_SUSPENDED, COACH_REINSTATED,
--    COACH_WARNING_ISSUED, DISPUTE_RESOLVED, DISPUTE_DISMISSED, GDPR_EXPORT_READY, REVIEW_APPROVED,
--    REVIEW_BLOCKED. EnvelopeEntitySchemaIT pins enum <-> constraint parity. The full list is rebuilt
--    as the union: V182's 44 values (incl. #321's three VIDEO_APPROVAL_*) + these 8.
--
-- 2. admin.admin_action_log.action_type CHECK widened with:
--      DISPUTE_DISMISS  (15 chars) - a dismissal, previously logged as DISPUTE_RESOLVE (indistinguishable)
--      APPEAL_UPHOLD    (13 chars) - an admin upheld an appeal (decision previously not in the action log)
--      APPEAL_OVERTURN  (15 chars) - an admin overturned an appeal
--      COACH_SAFETY_HOLD (17 chars) - a system child-safety suspension of a coach profile (CSAM match),
--                                     written with admin_id = 0 (AdminActionLog.SYSTEM_ACTOR_ID; no FK)
--    All fit the existing varchar(25). Existing DISPUTE_RESOLVE rows for past dismissals are left as they
--    are (no backfill): the disputes table's own status column still tells them apart.
--
-- Migration conventions (docs/deployment/migration-conventions.md), mirroring V168 and V171:
--  - NOT VALID / VALIDATE skipped (migration-lint: allow-validating-constraint, markers on the clauses
--    below): both tables are small, have no bulk-insert path, and no production deploy of this application
--    has ever happened (prod is not live), so each validating scan touches dev/uat data only.
--  - Rule 5 (widen one release before the first write): prod is not live, so - as at V162/V168/V171 - the
--    widening and its first write ship together.
--  - Rule 7 (pollers): EmailRetryScheduler polls envelope_entity every 60 s; lock_timeout makes the ALTER
--    fail fast instead of queueing behind it.
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
        'VIDEO_APPROVAL_REJECTED'::character varying,
        'COACH_SUSPENDED'::character varying,
        'COACH_REINSTATED'::character varying,
        'COACH_WARNING_ISSUED'::character varying,
        'DISPUTE_RESOLVED'::character varying,
        'DISPUTE_DISMISSED'::character varying,
        'GDPR_EXPORT_READY'::character varying,
        'REVIEW_APPROVED'::character varying,
        'REVIEW_BLOCKED'::character varying
    ])::text[])));

ALTER TABLE admin.admin_action_log
    DROP CONSTRAINT IF EXISTS admin_action_log_action_type_check,
    -- migration-lint: allow-validating-constraint small, internal admin table; no bulk-insert path,
    -- prod not live (V162/V171 precedent) - a fast validating CHECK swap, not a rolling-deploy lock risk.
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
        'COACH_SAFETY_HOLD'::character varying
    ])::text[])));

