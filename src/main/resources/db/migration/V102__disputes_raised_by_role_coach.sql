-- Deferred-63 AC5: a coach may now raise their own dispute (DisputeService.raiseDispute's
-- ownerEligible widening), but V74's inline CHECK on raised_by_role only ever allowed 'PARENT'
-- and 'PLAYER' — a coach-raised dispute would fail this constraint at insert time. Postgres
-- named the unnamed inline CHECK from V74 disputes_raised_by_role_check (<table>_<column>_check),
-- confirmed by this codebase's own convention for dropping such constraints (see
-- admin_action_log_action_type_check in V72, admin_alerts_type_check in V91).
ALTER TABLE admin.disputes
    DROP CONSTRAINT IF EXISTS disputes_raised_by_role_check,
    ADD CONSTRAINT disputes_raised_by_role_check
        CHECK (raised_by_role IN ('PARENT', 'PLAYER', 'COACH'));
