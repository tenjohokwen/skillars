package com.softropic.skillars.infrastructure.persistence;

import com.softropic.skillars.config.AbstractIntegrationTest;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * DECISIONS item 4: a second application instance against the same database must be detected (WARN +
 * {@code skillars.app.instances.live} gauge). Scheduling is off under the test profile, so the beat is
 * driven directly. Each test builds its own monitor (a fresh "JVM start": new instance id, beat counter
 * at zero) on the context's real JdbcTemplate/TransactionTemplate, so the shared context bean's state
 * never leaks between tests.
 */
class InstanceHeartbeatMonitorIT extends AbstractIntegrationTest {

    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private TransactionTemplate transactionTemplate;

    private SimpleMeterRegistry meterRegistry;
    private InstanceHeartbeatMonitor monitor;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        monitor = newMonitor(meterRegistry);
    }

    @Test
    void singleInstance_recordsItsOwnHeartbeat_andSeesNoPeers() {
        assertThat(monitor.checkForPeers()).isEmpty();

        assertThat(jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM main.app_instance_heartbeat WHERE instance_id = ?", Integer.class,
            monitor.instanceId())).isEqualTo(1);
        assertThat(liveGauge()).isEqualTo(1.0);
    }

    @Test
    void aSecondInstanceWithAFreshHeartbeat_isReported() {
        commit("INSERT INTO main.app_instance_heartbeat (instance_id, hostname, started_at, last_seen_at) "
            + "VALUES ('peer-1', 'other-host', now(), now())");

        assertThat(monitor.checkForPeers()).containsExactly("other-host");
        assertThat(liveGauge()).isEqualTo(2.0);
    }

    @Test
    void aCrashedEarlierIncarnationOnThisHost_isForgottenOnTheFirstBeat_notReportedAsAPeer() {
        // OOM kill / SIGKILL: @PreDestroy never ran, the row is still fresh, and the restarted
        // container keeps its hostname.
        transactionTemplate.executeWithoutResult(status -> jdbcTemplate.update(
            "INSERT INTO main.app_instance_heartbeat (instance_id, hostname, started_at, last_seen_at) "
                + "VALUES ('previous-run', ?, now() - interval '1 hour', now() - interval '30 seconds')",
            monitor.hostname()));

        assertThat(monitor.checkForPeers()).isEmpty();
        assertThat(jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM main.app_instance_heartbeat WHERE instance_id = 'previous-run'", Integer.class))
            .isZero();
    }

    @Test
    void stalePeers_areIgnored_andRowsOlderThanADayArePruned() {
        commit("INSERT INTO main.app_instance_heartbeat (instance_id, hostname, started_at, last_seen_at) "
            + "VALUES ('crashed-recently', 'old-host', now() - interval '2 hours', now() - interval '1 hour')");
        commit("INSERT INTO main.app_instance_heartbeat (instance_id, hostname, started_at, last_seen_at) "
            + "VALUES ('crashed-long-ago', 'older-host', now() - interval '3 days', now() - interval '2 days')");

        assertThat(monitor.checkForPeers()).isEmpty();

        List<String> remaining = jdbcTemplate.queryForList(
            "SELECT instance_id FROM main.app_instance_heartbeat WHERE instance_id <> ? ORDER BY instance_id",
            String.class, monitor.instanceId());
        assertThat(remaining).containsExactly("crashed-recently");
    }

    @Test
    void deregister_removesOnlyThisInstancesRow_andNoLaterBeatRecreatesIt() {
        monitor.checkForPeers();
        commit("INSERT INTO main.app_instance_heartbeat (instance_id, hostname, started_at, last_seen_at) "
            + "VALUES ('peer-3', 'other-host', now(), now())");

        monitor.deregister();
        monitor.checkForPeers();

        assertThat(jdbcTemplate.queryForList("SELECT instance_id FROM main.app_instance_heartbeat", String.class))
            .containsExactly("peer-3");
    }

    private InstanceHeartbeatMonitor newMonitor(SimpleMeterRegistry registry) {
        return new InstanceHeartbeatMonitor(jdbcTemplate, transactionTemplate, registry, 60_000L);
    }

    private double liveGauge() {
        return meterRegistry.get("skillars.app.instances.live").gauge().value();
    }

    private void commit(String sql) {
        transactionTemplate.executeWithoutResult(status -> jdbcTemplate.update(sql));
    }
}
