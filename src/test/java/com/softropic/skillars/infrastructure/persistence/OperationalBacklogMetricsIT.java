package com.softropic.skillars.infrastructure.persistence;

import com.softropic.skillars.config.AbstractIntegrationTest;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
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

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        metrics = new OperationalBacklogMetrics(jdbcTemplate, registry);
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
}
