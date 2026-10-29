-- feat/payment-forfeit-payouts-and-clawback: forfeits pay the coach, an admin can uphold the coach on a
-- dispute about a refunded booking and claw the refund back from the parent credit wallet.
--
-- 1. payment.parent_credit_ledger: new type BOOKING_REFUND_REVERSAL (a negative row: the part of a
--    BOOKING_REFUND taken back by an UPHOLD_COACH dispute resolution, capped at the wallet balance).
--    chk_ledger_type and chk_ledger_amount_sign are rebuilt from their V138 definitions (no later
--    migration changed either) plus the new type. uq_pcl_booking_refund_reversal: at most one reversal
--    per booking, the same shape as V138 uq_pcl_booking_refund.
-- 2. admin.disputes: recovered_amount / absorbed_amount (what an UPHOLD_COACH resolution took back from the
--    parent, and the part of the refund the platform absorbed). disputes_resolution_check widened with
--    UPHOLD_COACH (rebuilt from V138, never changed since).
-- 3. main.envelope_entity.envelope_entity_email_template_check widened with DISPUTE_COACH_UPHELD (the
--    notice to the party that did not raise an upheld-coach dispute). Rebuilt as the union: V189 54
--    values + this one. EnvelopeEntitySchemaIT pins enum <-> constraint parity. MERGE NOTE: any other
--    branch widening this CHECK must re-union the list.
--
-- Migration conventions (docs/deployment/migration-conventions.md), mirroring V189/V193:
--  - Additive only, nullable columns without a default are catalog-only.
--  - Validating CHECK swaps / a non-concurrent unique index: prod is not live (dev/uat data only), so each
--    is a fast scan, not a rolling-deploy lock risk (markers on the clauses below).
--  - Rule 5: the widenings and their first writes ship together (the V149/V152/V162/V186 owner decision).
SET LOCAL lock_timeout = '5s';

ALTER TABLE payment.parent_credit_ledger
    DROP CONSTRAINT IF EXISTS chk_ledger_type,
    -- migration-lint: allow-validating-constraint prod not live (dev/uat data only, as V183/V189/V193);
    -- the widened list is a superset of the old one, so every existing row passes the scan.
    ADD CONSTRAINT chk_ledger_type CHECK (((type)::text = ANY ((ARRAY[
        'BOOKING_DEDUCTION'::character varying,
        'BOOKING_DEDUCTION_REVERSAL'::character varying,
        'BOOKING_REFUND'::character varying,
        'BOOKING_REFUND_REVERSAL'::character varying,
        'CASH_OUT_DEBIT'::character varying,
        'STRIPE_FEE_DEBIT'::character varying,
        'CASH_OUT_REVERSAL'::character varying
    ])::text[]))),
    DROP CONSTRAINT IF EXISTS chk_ledger_amount_sign,
    -- migration-lint: allow-validating-constraint prod not live; no existing row has the new type, so the
    -- sign rule is unchanged for every row the scan reads.
    -- Same rule as V138 CASE form, written without CASE ... END (the migration lint reads a bare END as a
    -- transaction boundary): debit types negative, every other type positive.
    ADD CONSTRAINT chk_ledger_amount_sign CHECK (
        ((type)::text = ANY ((ARRAY[
            'BOOKING_DEDUCTION'::character varying,
            'CASH_OUT_DEBIT'::character varying,
            'STRIPE_FEE_DEBIT'::character varying,
            'BOOKING_REFUND_REVERSAL'::character varying
        ])::text[]) AND amount < (0)::numeric)
        OR (NOT ((type)::text = ANY ((ARRAY[
            'BOOKING_DEDUCTION'::character varying,
            'CASH_OUT_DEBIT'::character varying,
            'STRIPE_FEE_DEBIT'::character varying,
            'BOOKING_REFUND_REVERSAL'::character varying
        ])::text[])) AND amount > (0)::numeric));

-- migration-lint: allow-blocking-index partial index over a type no row has yet, so the build reads the
-- table once and writes nothing, prod is not live (V181/V186/V193 precedent).
CREATE UNIQUE INDEX IF NOT EXISTS uq_pcl_booking_refund_reversal
    ON payment.parent_credit_ledger (reference_id)
    WHERE reference_id IS NOT NULL AND type = 'BOOKING_REFUND_REVERSAL';

ALTER TABLE admin.disputes
    ADD COLUMN IF NOT EXISTS recovered_amount numeric(10,2),
    ADD COLUMN IF NOT EXISTS absorbed_amount numeric(10,2);

ALTER TABLE admin.disputes
    DROP CONSTRAINT IF EXISTS disputes_resolution_check,
    -- migration-lint: allow-validating-constraint small admin table, prod not live; superset of the old list.
    ADD CONSTRAINT disputes_resolution_check CHECK (((resolution)::text = ANY ((ARRAY[
        'FULL_CREDIT'::character varying,
        'PARTIAL_CREDIT'::character varying,
        'NO_ACTION'::character varying,
        'COACH_WARNING'::character varying,
        'UPHOLD_COACH'::character varying
    ])::text[])));

ALTER TABLE main.envelope_entity
    DROP CONSTRAINT IF EXISTS envelope_entity_email_template_check,
    -- migration-lint: allow-validating-constraint prod not live (dev/uat data only, as V168/V183/V185/V189), no
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
        'DISPUTE_COACH_UPHELD'::character varying
    ])::text[])));
