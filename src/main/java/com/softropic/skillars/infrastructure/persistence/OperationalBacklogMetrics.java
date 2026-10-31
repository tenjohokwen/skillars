package com.softropic.skillars.infrastructure.persistence;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.MultiGauge;
import io.micrometer.core.instrument.Tags;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Publishes work that is waiting on someone as {@code skillars.backlog.count} and
 * {@code skillars.backlog.oldest.age} (seconds), both tagged {@code backlog} and {@code detail}.
 * Counters show events; these show state the events left behind, which is what goes unnoticed:
 * a refund stuck in the outbox, a dispute nobody picked up, a wrap-up the coach never submitted.
 * The Grafana "Business health" dashboard and the Backlog* alerts read them.
 *
 * <p>Refreshed on a schedule rather than per scrape so a slow query can never stall Prometheus. No
 * {@code @SchedulerLock}: gauges are per JVM, so every instance must refresh its own (cookbook recipe
 * 5). Dashboards aggregate with {@code max}.
 *
 * <p>A failed or timed-out refresh propagates to the scheduler and keeps the previous values: clearing
 * them would read as "nothing is waiting", which is the one wrong answer. What stops it going unnoticed is
 * {@code skillars.backlog.refresh.age} (seconds since the last success, counted from startup before the
 * first), which the BacklogMetricsStale alert watches. The query runs on its own {@link JdbcTemplate} with a
 * statement timeout, so a blocked table (an ACCESS EXCLUSIVE lock from a migration, say) fails the refresh
 * after seconds instead of holding a pooled connection indefinitely.
 *
 * <p>Raw SQL because this reads across module schemas and only ever reports. Each branch reads an
 * indexed status or a table that drains (the outboxes), so the poll stays cheap as bookings grow. A
 * booking's age runs from when it entered the waiting status (its last update, or the confirmation request),
 * not from creation, which can be weeks earlier.
 * {@code outbox_stuck}'s 10 mirrors {@code OutboxService.STUCK_ATTEMPTS_THRESHOLD}. {@code payouts} /
 * {@code FROZEN} only counts payouts already past their release date: until then the freeze delays nothing.
 */
@Component
public class OperationalBacklogMetrics {

    private static final String BACKLOGS_SQL = """
        SELECT 'outbox_pending' AS backlog, aggregate_type AS detail, COUNT(*) AS items,
               EXTRACT(EPOCH FROM now() - MIN(created_at)) AS oldest_age
          FROM main.outbox_messages GROUP BY aggregate_type
        UNION ALL
        SELECT 'outbox_stuck', aggregate_type, COUNT(*), EXTRACT(EPOCH FROM now() - MIN(created_at))
          FROM main.outbox_messages WHERE attempts >= 10 GROUP BY aggregate_type
        UNION ALL
        SELECT 'video_deletion_outbox', status, COUNT(*), EXTRACT(EPOCH FROM now() - MIN(created_at))
          FROM main.video_deletion_outbox WHERE status IN ('PENDING', 'FAILED', 'DEAD') GROUP BY status
        UNION ALL
        SELECT 'subscription_lifecycle_outbox', status, COUNT(*), EXTRACT(EPOCH FROM now() - MIN(created_at))
          FROM main.subscription_lifecycle_outbox WHERE status = 'PENDING' GROUP BY status
        UNION ALL
        SELECT 'admin_alerts', type, COUNT(*), EXTRACT(EPOCH FROM now() - MIN(created_at))
          FROM admin.admin_alerts WHERE status IN ('OPEN', 'IN_PROGRESS') GROUP BY type
        UNION ALL
        SELECT 'disputes', status, COUNT(*), EXTRACT(EPOCH FROM now() - MIN(created_at))
          FROM admin.disputes WHERE status IN ('OPEN', 'UNDER_REVIEW') GROUP BY status
        UNION ALL
        SELECT 'gdpr_requests', request_type || '_' || status, COUNT(*), EXTRACT(EPOCH FROM now() - MIN(created_at))
          FROM admin.gdpr_requests WHERE status IN ('PENDING', 'PROCESSING', 'FAILED')
          GROUP BY request_type, status
        UNION ALL
        SELECT 'awaited_wrap_ups', 'all', COUNT(*), EXTRACT(EPOCH FROM now() - MIN(created_at))
          FROM booking.session_completion_data WHERE awaiting_wrap_up HAVING COUNT(*) > 0
        UNION ALL
        SELECT 'bookings', status, COUNT(*),
               EXTRACT(EPOCH FROM now() - MIN(COALESCE(confirmation_requested_at, updated_at)))
          FROM booking.bookings WHERE status IN ('PAYMENT_PENDING', 'COMPLETED_PENDING_CONFIRMATION')
          GROUP BY status
        UNION ALL
        SELECT 'payouts', status, COUNT(*), EXTRACT(EPOCH FROM now() - MIN(created_at))
          FROM payment.coach_payouts WHERE status IN ('HOLD', 'FAILED_PERMANENT', 'REVERSAL_FAILED')
          GROUP BY status
        UNION ALL
        SELECT 'payouts', CASE WHEN dispute_frozen OR chargeback_frozen THEN 'FROZEN' ELSE 'OVERDUE' END,
               COUNT(*), EXTRACT(EPOCH FROM now() - MIN(release_after))
          FROM payment.coach_payouts
         WHERE status = 'PENDING_RELEASE' AND release_after < now() - interval '1 hour'
         GROUP BY 2
        UNION ALL
        SELECT 'cash_outs', status, COUNT(*), EXTRACT(EPOCH FROM now() - MIN(created_at))
          FROM payment.cash_out_reconciliations WHERE status IN ('PENDING', 'UNKNOWN') GROUP BY status
        """;

    private static final int DEFAULT_QUERY_TIMEOUT_SECONDS = 10;

    private final JdbcTemplate jdbcTemplate;
    private final Clock clock;
    private final MultiGauge counts;
    private final MultiGauge oldestAges;
    private final AtomicReference<Instant> lastSuccess;

    @Autowired
    public OperationalBacklogMetrics(
            JdbcTemplate jdbcTemplate, MeterRegistry meterRegistry,
            @Value("${platform.backlog-metrics.query-timeout-seconds:" + DEFAULT_QUERY_TIMEOUT_SECONDS + "}")
            int queryTimeoutSeconds) {
        this(boundedCopy(jdbcTemplate, queryTimeoutSeconds), meterRegistry, Clock.systemUTC());
    }

    /** Tests: the template is used as given, so a short timeout or a broken one can be supplied. */
    OperationalBacklogMetrics(JdbcTemplate boundedJdbcTemplate, MeterRegistry meterRegistry, Clock clock) {
        this.jdbcTemplate = boundedJdbcTemplate;
        this.clock = clock;
        this.lastSuccess = new AtomicReference<>(clock.instant());
        this.counts = MultiGauge.builder("skillars.backlog.count")
            .description("Items currently waiting in an operational backlog")
            .register(meterRegistry);
        this.oldestAges = MultiGauge.builder("skillars.backlog.oldest.age")
            .baseUnit("seconds")
            .description("Age of the oldest item in an operational backlog")
            .register(meterRegistry);
        Gauge.builder("skillars.backlog.refresh.age", this,
                self -> Duration.between(self.lastSuccess.get(), self.clock.instant()).toMillis() / 1000.0)
            .baseUnit("seconds")
            .description("Seconds since the backlog gauges were last refreshed successfully")
            .register(meterRegistry);
    }

    /** Own template over the same pool: the shared bean's settings stay untouched. */
    private static JdbcTemplate boundedCopy(JdbcTemplate shared, int timeoutSeconds) {
        JdbcTemplate bounded = new JdbcTemplate(shared.getDataSource());
        bounded.setQueryTimeout(timeoutSeconds);
        return bounded;
    }

    @Scheduled(initialDelayString = "${platform.backlog-metrics.initial-delay-ms:20000}",
               fixedDelayString = "${platform.backlog-metrics.interval-ms:60000}")
    public void refresh() {
        List<MultiGauge.Row<?>> countRows = new ArrayList<>();
        List<MultiGauge.Row<?>> ageRows = new ArrayList<>();
        jdbcTemplate.query(BACKLOGS_SQL, rs -> {
            Tags tags = Tags.of("backlog", rs.getString("backlog"), "detail", rs.getString("detail"));
            countRows.add(MultiGauge.Row.of(tags, rs.getLong("items")));
            ageRows.add(MultiGauge.Row.of(tags, rs.getDouble("oldest_age")));
        });
        // overwrite=true drops series whose backlog emptied, so an alert on one clears instead of
        // sticking at its last value.
        counts.register(countRows, true);
        oldestAges.register(ageRows, true);
        lastSuccess.set(clock.instant());
    }
}
