-- skillars-deferred-153 AC1: adds the admin force-complete action's log entry type.
-- admin.admin_action_log's `action_type` CHECK constraint (V138__baseline_schema.sql) has no value
-- that fits the new admin force-complete-booking action — BOOKING_FORCE_COMPLETE is 22 chars, fits
-- the existing action_type varchar(25), no column widening needed.
--
-- NOT VALID / VALIDATE skipped (migration-lint: allow-validating-constraint), mirroring
-- V153__admin_alerts_subscription_orphaned_type.sql's own reasoning verbatim: admin.admin_action_log
-- is an internal admin-queue-shaped table with no bulk-insert path and no expected row count
-- anywhere near large-table territory, so a validating ADD CONSTRAINT here is a normal, fast
-- full-table CHECK scan, not the kind of lock exposure rule 3 exists to avoid on a large/hot table.
-- Per the same owner decision re-confirmed at V149/V152/V153 — no production deploy of this
-- application has ever happened — there is no live rolling-deploy window this could break today, so
-- the widening and its first write ship together in this same story.
SET LOCAL lock_timeout = '5s';

ALTER TABLE admin.admin_action_log
    DROP CONSTRAINT IF EXISTS admin_action_log_action_type_check,
    -- migration-lint: allow-validating-constraint small, internal admin-queue table; no bulk-insert
    -- path and no row count anywhere near large-table territory, so a validating CHECK swap is a fast
    -- full-table scan, not a rolling-deploy lock-exposure risk.
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
        'BOOKING_FORCE_COMPLETE'::character varying
    ])::text[])));
