package com.softropic.skillars.infrastructure.persistence;

import com.softropic.skillars.config.AbstractIntegrationTest;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

/**
 * Runs the real backlog query against the migrated schema, so a renamed column or status fails here
 * rather than as a scheduler error in production. Scheduling is off under the test profile, so the
 * refresh is driven directly on a private registry.
 */
class OperationalBacklogMetricsIT extends AbstractIntegrationTest {

    private static final String AGGREGATE = "BACKLOG_METRICS_IT";

    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private TransactionTemplate transactionTemplate;

    private SimpleMeterRegistry registry;
    private OperationalBacklogMetrics metrics;
    private MutableClock clock;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        clock = new MutableClock();
        metrics = new OperationalBacklogMetrics(bounded(1), registry, clock);
    }

    private JdbcTemplate bounded(int timeoutSeconds) {
        JdbcTemplate bounded = new JdbcTemplate(jdbcTemplate.getDataSource());
        bounded.setQueryTimeout(timeoutSeconds);
        return bounded;
    }

    private double refreshAge() {
        return registry.get("skillars.backlog.refresh.age").gauge().value();
    }

    /** A clock the test advances by hand, so staleness is asserted without sleeping. */
    private static final class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-01-01T00:00:00Z");

        void advance(Duration d) {
            now = now.plus(d);
        }

        @Override public ZoneOffset getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }

    @AfterEach
    void cleanUp() {
        commit("DELETE FROM main.outbox_messages WHERE aggregate_type = ?", AGGREGATE);
    }

    private void commit(String sql, Object... args) {
        transactionTemplate.executeWithoutResult(status -> jdbcTemplate.update(sql, args));
    }

    private void enqueue(int attempts, String age) {
        commit("INSERT INTO main.outbox_messages (aggregate_type, payload, attempts, created_at) "
            + "VALUES (?, '{}'::jsonb, ?, now() - ?::interval)", AGGREGATE, attempts, age);
    }

    private Gauge gauge(String name, String backlog) {
        return registry.find(name).tags("backlog", backlog, "detail", AGGREGATE).gauge();
    }

    @Test
    void reportsCountAndOldestAge_andSeparatesRowsThatStoppedRetrying() {
        enqueue(0, "10 minutes");
        enqueue(12, "2 hours");

        metrics.refresh();

        assertThat(gauge("skillars.backlog.count", "outbox_pending").value()).isEqualTo(2);
        assertThat(gauge("skillars.backlog.count", "outbox_stuck").value()).isEqualTo(1);
        assertThat(gauge("skillars.backlog.oldest.age", "outbox_pending").value())
            .isCloseTo(7200, within(60.0));
    }

    @Test
    void aDeadVideoDeletion_staysInTheBacklog() {
        commit("INSERT INTO main.video_deletion_outbox (video_id, status) VALUES (gen_random_uuid(), 'DEAD')");
        try {
            metrics.refresh();
            assertThat(registry.find("skillars.backlog.count").tags("backlog", "video_deletion_outbox", "detail", "DEAD").gauge())
                .isNotNull();
        } finally {
            commit("DELETE FROM main.video_deletion_outbox WHERE status = 'DEAD'");
        }
    }

    @Test
    void aBacklogThatEmptied_dropsItsSeries_soAnAlertOnItClears() {
        enqueue(12, "1 hour");
        metrics.refresh();
        assertThat(gauge("skillars.backlog.count", "outbox_stuck")).isNotNull();

        cleanUp();
        metrics.refresh();

        assertThat(gauge("skillars.backlog.count", "outbox_stuck")).isNull();
    }

    @Test
    void aTimedOutRefresh_keepsTheLastValues_andLetsTheRefreshAgeGrow() throws Exception {
        enqueue(0, "10 minutes");
        metrics.refresh();
        assertThat(refreshAge()).isZero();
        assertThat(gauge("skillars.backlog.count", "outbox_pending").value()).isEqualTo(1);

        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService blocker = Executors.newSingleThreadExecutor();
        // A migration-style ACCESS EXCLUSIVE lock: the poll's read of outbox_messages waits behind it.
        Future<?> holding = blocker.submit(() -> transactionTemplate.executeWithoutResult(status -> {
            jdbcTemplate.execute("LOCK TABLE main.outbox_messages IN ACCESS EXCLUSIVE MODE");
            locked.countDown();
            try {
                release.await(30, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }));
        try {
            assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();
            clock.advance(Duration.ofMinutes(7));

            long started = System.nanoTime();
            assertThatThrownBy(metrics::refresh).isInstanceOf(DataAccessException.class);

            assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(10));
            assertThat(refreshAge()).isEqualTo(420.0);
            assertThat(gauge("skillars.backlog.count", "outbox_pending").value()).isEqualTo(1);
        } finally {
            release.countDown();
            holding.get(30, TimeUnit.SECONDS);
            blocker.shutdownNow();
        }

        metrics.refresh();
        assertThat(refreshAge()).isZero();
    }

    @Test
    void theRefreshAge_runsFromStartupUntilTheFirstSuccess() {
        clock.advance(Duration.ofMinutes(3));
        assertThat(refreshAge()).isEqualTo(180.0);

        metrics.refresh();

        assertThat(refreshAge()).isZero();
    }
}
