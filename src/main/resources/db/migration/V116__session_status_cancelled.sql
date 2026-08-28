-- Deferred-78 AC8. session.sessions.status carries an inline CHECK constraint
-- (V43__session_plans.sql:10-11) that PostgreSQL auto-named "sessions_status_check" since it
-- was declared without an explicit CONSTRAINT name. SessionPlanService.handleBookingTerminalNonCompletion
-- now transitions an orphaned DRAFT/SAVED session to CANCELLED on booking cancellation/decline/no-show —
-- without this migration, every such write violates the constraint and is silently swallowed by that
-- method's existing DataIntegrityViolationException catch, leaving the session un-locked.
-- CANCELLED is 9 characters; status is VARCHAR(20), so no column-width change is needed.
ALTER TABLE session.sessions
    DROP CONSTRAINT sessions_status_check,
    ADD CONSTRAINT sessions_status_check
        CHECK (status IN ('DRAFT', 'SAVED', 'COMPLETED', 'CANCELLED'));
