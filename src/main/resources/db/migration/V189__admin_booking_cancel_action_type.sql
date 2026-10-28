-- C6 (fix/booking-cancel-emails-and-refunds): an admin can cancel a single booking from the admin bookings
-- page (AdminBookingResource POST /api/admin/bookings/{id}/cancel), with the coach-suspension sweep's
-- semantics (CANCEL_ADMIN: full refund of captured money, no coach strike, neutral parent notice).
--
-- admin.admin_action_log.action_type CHECK widened with BOOKING_ADMIN_CANCEL (20 chars, fits the
-- varchar(25)). The list is rebuilt as the union: V183's 19 values + this one. AdminActionLog enum <->
-- constraint parity is pinned by the admin action-log IT. MERGE NOTE: any other branch widening this CHECK
-- must re-union both lists.
--
-- Migration conventions (docs/deployment/migration-conventions.md), mirroring V183:
--  - NOT VALID / VALIDATE skipped (migration-lint: allow-validating-constraint, marker on the clause
--    below): a small internal admin table with no bulk-insert path, and prod is not live, so the
--    validating scan touches dev/uat data only.
--  - Rule 5 (widen one release before the first write): prod is not live, so - as at V162/V171/V183 - the
--    widening and its first write ship together.
SET LOCAL lock_timeout = '5s';

ALTER TABLE admin.admin_action_log
    DROP CONSTRAINT IF EXISTS admin_action_log_action_type_check,
    -- migration-lint: allow-validating-constraint small, internal admin table; no bulk-insert path,
    -- prod not live (V162/V171/V183 precedent) - a fast validating CHECK swap, not a rolling-deploy lock risk.
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
        'BOOKING_ADMIN_CANCEL'::character varying
    ])::text[])));
