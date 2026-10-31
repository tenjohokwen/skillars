-- feat/noshow-reminders-autorefund-alert (S-07 part 2): coach start reminders, the unstarted-session auto-refund
-- and the repeat no-show claimant alert.
--
-- 1. bookings.coach_start_reminder_sent_at / coach_late_start_reminder_sent_at — set by CoachStartReminderScheduler
--    when it queues the coach's "press Start, or this session can be refunded" reminder at the scheduled start and
--    again at start + 10 minutes for a session still UPCOMING. Once each per booking (the dedupe).
-- 2. idx_bkg_no_show_claim_payer — supports the repeat-claimant count (a payer's coach no-show claims in a rolling
--    window, V218 coach_no_show_claimed_at). Partial: only claimed bookings are indexed.
-- 3. main.envelope_entity.envelope_entity_email_template_check widened with COACH_START_REMINDER. Rebuilt as the
--    union: V212's values + this one (no migration between V212 and this one touched it). EnvelopeEntitySchemaIT
--    pins enum <-> constraint parity. MERGE NOTE: any other branch widening this CHECK must re-union the list.
-- 4. admin.admin_alerts.admin_alerts_type_check widened with REPEAT_NO_SHOW_CLAIMANT (23 chars, fits the
--    25-char column). Rebuilt as the union: V209's values + this one. MERGE NOTE: as above, for this CHECK.
--
-- No platform_config seed rows: booking.unresolvedSession.autoRefundHours (48) and
-- booking.coachNoShow.repeatClaimAlertThreshold (3) / repeatClaimAlertWindowDays (90) are HAS_CODE_DEFAULT keys in
-- ConfigBounds, editable from the admin config editor without a stored row.
--
-- Migration conventions (docs/deployment/migration-conventions.md), mirroring V209/V212:
--  - 1 is two nullable columns with no default (catalog-only, PG 11+).
--  - 2 builds a small partial index non-concurrently (marker below), lock_timeout bounds the wait.
--  - 3 and 4 are validating CHECK swaps on tables that are small or polled: prod is not live (dev/uat data only), the
--    new lists are supersets of the old ones, so every existing row passes the scan.
--  - Rule 5: the widenings and their first writes ship together (the V149/V152/V162/V186 owner decision).
--  - Rule 7 (pollers): EmailRetryScheduler polls envelope_entity every 60 s; lock_timeout makes the ALTER fail fast
--    instead of queueing behind it.
SET LOCAL lock_timeout = '5s';

ALTER TABLE booking.bookings
    ADD COLUMN IF NOT EXISTS coach_start_reminder_sent_at timestamp with time zone,
    ADD COLUMN IF NOT EXISTS coach_late_start_reminder_sent_at timestamp with time zone;

-- migration-lint: allow-blocking-index <reason: CREATE INDEX CONCURRENTLY has no working online-safe
-- mechanism in this repo's single-connection Flyway runner yet (docs/deployment/migration-conventions.md
-- rule 4); the partial predicate keeps the build tiny (only claimed bookings), and lock_timeout bounds the wait>
CREATE INDEX IF NOT EXISTS idx_bkg_no_show_claim_payer
    ON booking.bookings USING btree (parent_id, coach_no_show_claimed_at)
    WHERE coach_no_show_claimed_at IS NOT NULL;

ALTER TABLE main.envelope_entity
    DROP CONSTRAINT IF EXISTS envelope_entity_email_template_check,
    -- migration-lint: allow-validating-constraint prod not live (dev/uat data only, as V168/V183/V185/V189/V198), no
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
        'COACH_START_REMINDER'::character varying
    ])::text[])));

ALTER TABLE admin.admin_alerts
    DROP CONSTRAINT IF EXISTS admin_alerts_type_check,
    -- migration-lint: allow-validating-constraint small, internal admin-queue table; no bulk-insert
    -- path and no row count anywhere near large-table territory, so a validating CHECK swap is a fast
    -- full-table scan, not a rolling-deploy lock-exposure risk.
    ADD CONSTRAINT admin_alerts_type_check CHECK (((type)::text = ANY ((ARRAY[
        'MESSAGE_REPORT'::character varying,
        'CONVERSATION_REPORT'::character varying,
        'REVIEW_FLAG'::character varying,
        'STRIKE_THRESHOLD'::character varying,
        'DISPUTE_RAISED'::character varying,
        'MODERATION_UNRESOLVED'::character varying,
        'GDPR_ERASURE_DEADLINE'::character varying,
        'SUBSCRIPTION_ORPHANED'::character varying,
        'SAFETY_HOLD_BOOKING'::character varying,
        'CASH_OUT_UNRESOLVED'::character varying,
        'CHARGEBACK'::character varying,
        'EXTERNAL_REFUND'::character varying,
        'PAYOUT_HELD'::character varying,
        'PAYOUT_REVERSAL_FAILED'::character varying,
        'BOOKING_ACTION_REQUIRED'::character varying,
        'SESSION_NEVER_STARTED'::character varying,
        'REPEAT_NO_SHOW_CLAIMANT'::character varying
    ])::text[])));
