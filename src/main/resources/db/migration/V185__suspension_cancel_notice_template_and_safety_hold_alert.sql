-- C2 (feat/suspension-cancels-upcoming-bookings): a coach suspension / child-safety hold now cancels the
-- coach's upcoming bookings (DECISIONS.md item 2).
--
-- 1. main.envelope_entity.envelope_entity_email_template_check widened with
--    BOOKING_CANCELLED_COACH_UNAVAILABLE - the neutral "your session was cancelled because the coach is
--    unavailable; any payment has been refunded in full" notice BookingEmailListener sends the payer.
--    No existing template fits: BOOKING_CANCELLED_BY_COACH's subject says the coach cancelled (false, and
--    the parent must not be told why), BOOKING_DECLINED promises credits were untouched. The full list is
--    rebuilt as the union: V183's 52 values + this one. EnvelopeEntitySchemaIT pins enum <-> constraint
--    parity. MERGE NOTE: chunk C1 rebuilds this same CHECK in V184 - whichever of the two merges second
--    must re-union both lists.
--
-- 2. admin.admin_alerts.admin_alerts_type_check widened with SAFETY_HOLD_BOOKING (19 chars, fits the
--    varchar(25)): an urgent alert, one per booking, that a child-safety hold could not cancel (live
--    session, or a payment capture in flight). reference_type BOOKING already exists. AdminQueueIT pins
--    enum <-> constraint parity. Last widened by V153.
--
-- Migration conventions (docs/deployment/migration-conventions.md), mirroring V153 and V183:
--  - NOT VALID / VALIDATE skipped (migration-lint: allow-validating-constraint, markers on the clauses
--    below): both tables are small, have no bulk-insert path, and no production deploy of this application
--    has ever happened (prod is not live), so each validating scan touches dev/uat data only.
--  - Rule 5 (widen one release before the first write): prod is not live, so - as at V162/V168/V171/V183 -
--    the widening and its first write ship together.
--  - Rule 7 (pollers): EmailRetryScheduler polls envelope_entity every 60 s; lock_timeout makes the ALTER
--    fail fast instead of queueing behind it.
SET LOCAL lock_timeout = '5s';

ALTER TABLE main.envelope_entity
    DROP CONSTRAINT IF EXISTS envelope_entity_email_template_check,
    -- migration-lint: allow-validating-constraint prod not live (dev/uat data only, as V168/V183), no
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
        'BOOKING_CANCELLED_COACH_UNAVAILABLE'::character varying
    ])::text[])));

ALTER TABLE admin.admin_alerts
    DROP CONSTRAINT IF EXISTS admin_alerts_type_check,
    -- migration-lint: allow-validating-constraint small, internal admin-queue table; no bulk-insert
    -- path and no row count anywhere near large-table territory (V152/V153 precedent) - a fast
    -- validating CHECK swap, not a rolling-deploy lock-exposure risk.
    ADD CONSTRAINT admin_alerts_type_check CHECK (((type)::text = ANY ((ARRAY[
        'MESSAGE_REPORT'::character varying,
        'CONVERSATION_REPORT'::character varying,
        'REVIEW_FLAG'::character varying,
        'STRIKE_THRESHOLD'::character varying,
        'DISPUTE_RAISED'::character varying,
        'MODERATION_UNRESOLVED'::character varying,
        'GDPR_ERASURE_DEADLINE'::character varying,
        'SUBSCRIPTION_ORPHANED'::character varying,
        'SAFETY_HOLD_BOOKING'::character varying
    ])::text[])));
