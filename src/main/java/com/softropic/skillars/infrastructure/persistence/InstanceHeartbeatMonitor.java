package com.softropic.skillars.infrastructure.persistence;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.support.TransactionTemplate;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static net.logstash.logback.argument.StructuredArguments.kv;

/**
 * Detects a second application instance running against the same database and says so loudly.
 *
 * <p>The application is deployed as a single instance until server-sent-event fan-out exists:
 * the SSE emitter registries (messaging, booking and video events) and a few caches live in one
 * JVM's memory, so on a second instance users connected to the "other" node silently miss
 * real-time events. Scaling out needs a cross-instance channel first (Postgres LISTEN/NOTIFY is
 * the planned one) — see docs/dev-docs/decisions ("Single instance until SSE fan-out exists").
 *
 * <p>Mechanism: every instance upserts its own row in {@code main.app_instance_heartbeat} on a
 * fixed delay and counts the OTHER rows seen within {@link #liveWindowSeconds}; any such row logs
 * a WARN (and the {@code skillars.app.instances.live} gauge goes above 1). All timestamps are the
 * database's {@code now()}, so clock skew between hosts cannot fake or hide a peer. ShedLock's
 * {@code locked_by} was considered and rejected as the signal: it only records whichever host last
 * ran a locked job, so a second instance that never wins a lock is invisible and a long-gone host
 * lingers until its row is next overwritten.
 *
 * <p>No {@code @SchedulerLock}, deliberately: each instance must write its OWN heartbeat — a
 * cluster-wide lock would let one instance's beat suppress the other's, the very signal this job
 * exists to produce. Per-node work like this is the documented exception (cookbook recipe 5).
 * A graceful shutdown deletes this instance's row (and stops further beats, so an in-flight beat
 * cannot re-create it) so a normal rolling deploy only warns for the overlap itself. After a crash
 * (OOM kill, SIGKILL) a restarted container keeps its hostname: the first beat deletes that host's
 * earlier incarnations so they are not reported as a live peer. (Two real instances sharing one
 * hostname, e.g. host networking, still warn — one beat later, once the other re-upserts.) Other
 * rows from crashed instances age out of the live window and are pruned after a day.
 */
@Slf4j
@Component
public class InstanceHeartbeatMonitor {

    private static final String UPSERT_SQL =
        "INSERT INTO main.app_instance_heartbeat (instance_id, hostname, started_at, last_seen_at) "
            + "VALUES (?, ?, now(), now()) "
            + "ON CONFLICT (instance_id) DO UPDATE SET last_seen_at = now()";
    private static final String FORGET_EARLIER_INCARNATIONS_SQL =
        "DELETE FROM main.app_instance_heartbeat WHERE hostname = ? AND instance_id <> ?";
    /** Rows older than a day only accumulate after crashes; pruning once per this many beats is plenty. */
    private static final long PRUNE_EVERY_N_BEATS = 60;
    private static final String PRUNE_SQL =
        "DELETE FROM main.app_instance_heartbeat WHERE last_seen_at < now() - interval '1 day'";
    private static final String LIVE_PEERS_SQL =
        "SELECT hostname FROM main.app_instance_heartbeat "
            + "WHERE instance_id <> ? AND last_seen_at > now() - make_interval(secs => ?) "
            + "ORDER BY hostname";

    private final JdbcTemplate jdbcTemplate;
    private final TransactionTemplate transactionTemplate;
    private final String instanceId = UUID.randomUUID().toString();
    private final String hostname = resolveHostname();
    private final long liveWindowSeconds;
    private final AtomicInteger liveInstances = new AtomicInteger(1);
    // Guarded by `this`: checkForPeers and deregister are synchronized, so deregister waits for an
    // in-flight beat and no beat runs after it.
    private long beats;
    private boolean stopped;

    public InstanceHeartbeatMonitor(JdbcTemplate jdbcTemplate,
                                    TransactionTemplate transactionTemplate,
                                    MeterRegistry meterRegistry,
                                    @Value("${platform.instance-heartbeat.interval-ms:60000}") long intervalMs) {
        this.jdbcTemplate = jdbcTemplate;
        this.transactionTemplate = transactionTemplate;
        // Three missed beats before a peer stops counting as live: one late beat (GC pause, slow
        // pool checkout) must not make a real second instance flicker out of the warning.
        this.liveWindowSeconds = Math.max(1L, (intervalMs * 3) / 1000);
        Gauge.builder("skillars.app.instances.live", liveInstances, AtomicInteger::get)
            .description("Application instances with a fresh heartbeat row (this one included); "
                + "anything above 1 is an unsupported scale-out")
            .register(meterRegistry);
    }

    /** Runs on every instance: first beat shortly after startup, then on a fixed delay. */
    @Scheduled(initialDelayString = "${platform.instance-heartbeat.initial-delay-ms:15000}",
               fixedDelayString = "${platform.instance-heartbeat.interval-ms:60000}")
    public void beat() {
        try {
            checkForPeers();
        } catch (DataAccessException | TransactionException e) {
            // A transient DB problem must not kill the scheduler thread's next run; the next beat retries.
            log.warn("Instance heartbeat failed; will retry on the next beat",
                kv("operation", "instance_heartbeat"), kv("status", "FAILED"), e);
        }
    }

    /**
     * Records this instance's heartbeat, prunes long-dead rows, and returns the hostnames of the other
     * instances seen within the live window — logging a WARN when there is any.
     */
    public synchronized List<String> checkForPeers() {
        if (stopped) {
            return List.of();
        }
        final long beat = beats++;
        List<String> peers = transactionTemplate.execute(status -> {
            jdbcTemplate.update(UPSERT_SQL, instanceId, hostname);
            if (beat == 0) {
                jdbcTemplate.update(FORGET_EARLIER_INCARNATIONS_SQL, hostname, instanceId);
            }
            if (beat % PRUNE_EVERY_N_BEATS == 0) {
                jdbcTemplate.update(PRUNE_SQL);
            }
            return jdbcTemplate.queryForList(LIVE_PEERS_SQL, String.class, instanceId, liveWindowSeconds);
        });
        List<String> livePeers = peers == null ? List.of() : peers;
        liveInstances.set(1 + livePeers.size());
        if (!livePeers.isEmpty()) {
            log.warn("More than one application instance is running against this database ({} live, this one is {} on {}): "
                    + "SSE events (messages, booking and video updates) only reach users connected to the instance that "
                    + "produced them. Run a single instance until SSE fan-out exists (Postgres LISTEN/NOTIFY); expected "
                    + "only briefly during a rolling deploy.",
                1 + livePeers.size(), instanceId, hostname,
                kv("operation", "instance_heartbeat"), kv("status", "MULTIPLE_INSTANCES"),
                kv("peerHosts", livePeers));
        }
        return livePeers;
    }

    /** Graceful shutdown: remove this instance's row so the next instance does not see a phantom peer. */
    @PreDestroy
    public synchronized void deregister() {
        stopped = true;
        try {
            transactionTemplate.executeWithoutResult(status ->
                jdbcTemplate.update("DELETE FROM main.app_instance_heartbeat WHERE instance_id = ?", instanceId));
        } catch (DataAccessException | TransactionException e) {
            // Best effort: the database may already be unreachable at shutdown; the row then ages out.
            log.debug("Could not remove instance heartbeat row at shutdown", e);
        }
    }

    String instanceId() {
        return instanceId;
    }

    String hostname() {
        return hostname;
    }

    private static String resolveHostname() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (UnknownHostException e) {
            return "unknown";
        }
    }
}
