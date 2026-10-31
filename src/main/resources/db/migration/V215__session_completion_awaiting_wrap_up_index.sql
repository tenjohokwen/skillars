-- feat/lgtm-application-dashboards: supports OperationalBacklogMetrics' awaited_wrap_ups backlog, polled
-- every minute. Without it each poll scans the whole of booking.session_completion_data for the few rows
-- whose family confirmed before the coach's wrap-up. Partial: only those rows are indexed.
SET LOCAL lock_timeout = '5s';

-- migration-lint: allow-blocking-index <reason: CREATE INDEX CONCURRENTLY has no working online-safe
-- mechanism in this repo's single-connection Flyway runner yet (docs/deployment/migration-conventions.md
-- rule 4); the partial predicate keeps the build small, and lock_timeout bounds the wait on a busy table>
CREATE INDEX IF NOT EXISTS idx_scd_awaiting_wrap_up
    ON booking.session_completion_data USING btree (created_at)
    WHERE awaiting_wrap_up;
