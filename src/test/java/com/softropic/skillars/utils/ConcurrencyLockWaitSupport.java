package com.softropic.skillars.utils;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.awaitility.Awaitility;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Duration;

/**
 * skillars-deferred-131 AC3. Deterministic "contention established" signals, replacing a fixed
 * {@code Thread.sleep(300)} across {@code ReviewFlagServiceConcurrencyIT},
 * {@code CoachProfileServiceConcurrencyIT} and {@code SubscriptionServiceConcurrencyIT}. A fixed sleep
 * proves nothing — on a cold JVM or a constrained CI runner the contending thread might not have
 * reached the lock yet (a false pass), and on a loaded system the sleep can outlast what the test
 * actually needs (a slow, still-correct run). Two different signals are needed because this codebase
 * has two different lock disciplines:
 *
 * <ul>
 *   <li>{@code CoachReviewRepository.findByIdForUpdate} carries no {@code @QueryHints} — a genuine
 *       blocking {@code FOR UPDATE}. The contending backend truly sits in a wait state, visible LIVE
 *       via {@code pg_locks}/{@code pg_stat_activity}. Use {@link #awaitBlockingLockWaiter} as a
 *       pre-release gate.</li>
 *   <li>{@code CoachProfileRepository.findByIdForUpdate} carries
 *       {@code jakarta.persistence.lock.timeout = "0"} (NOWAIT). The contending backend never enters a
 *       wait state — it fails instantly and {@link com.softropic.skillars.infrastructure.persistence
 *       .PessimisticLockRetryer} retries in Java. A {@code pg_locks} poll here would spin until timeout
 *       and fail even on a correct system.
 *
 *       <p><strong>Implementation-time correction:</strong> {@code PessimisticLockRetryer}'s own
 *       {@code persistence.lock_retry.retries} counter cannot gate a PRE-release wait the way
 *       {@code awaitBlockingLockWaiter} does — {@code recordRetries(...)} only fires once the whole
 *       retry loop CONCLUDES (on eventual success, or on exhaustion), not per individual failed
 *       attempt, so polling it to become {@code > baseline} before releasing the holder's lock would
 *       either return immediately (a stale JVM-wide value from an unrelated earlier call) or block
 *       until the contender has already given up — never a live "still retrying" signal. Use
 *       {@link #FIRST_LOCK_ATTEMPT_DELAY} as a short, bounded pre-release delay instead (long enough
 *       for the NOWAIT failure and its first backoff to have genuinely happened), paired with
 *       {@link #assertGenuineLockRetryOccurred} AFTER both threads join — a real, deterministic
 *       post-hoc proof that contention was actually hit and retried, not merely assumed from
 *       timing.</li>
 * </ul>
 */
public final class ConcurrencyLockWaitSupport {

    /**
     * A short, bounded delay giving a NOWAIT contender time to make its first failed attempt (an
     * instant {@code 55P03}, no network round trip to wait out) and begin its jittered backoff
     * (75-100ms floor at the default {@code initial-backoff-ms=100}) before the holder releases its
     * lock. See the class javadoc for why this cannot be replaced with a live poll.
     *
     * <p>Code review 2026-09-23: kept at the same 300ms this replaced (the fixed
     * {@code Thread.sleep(300)} this class's javadoc describes), not halved to 150ms. This delay is now
     * paired with {@link #assertGenuineLockRetryOccurred}'s hard post-hoc assertion — a contender that
     * has not yet made its first attempt by the time the holder releases is a genuine test failure, not
     * a silently-tolerated pass the way it was pre-AC3. Trimming the delay below the value already
     * proven reliable would trade a real determinism improvement for a new timing-flake surface on a
     * loaded CI runner, for no benefit this class needs.
     */
    private static final Duration FIRST_LOCK_ATTEMPT_DELAY = Duration.ofMillis(300);

    private ConcurrencyLockWaitSupport() {
    }

    /** Sleeps for {@link #FIRST_LOCK_ATTEMPT_DELAY}. See the class javadoc's NOWAIT discussion. */
    public static void awaitFirstLockAttempt() throws InterruptedException {
        Thread.sleep(FIRST_LOCK_ATTEMPT_DELAY.toMillis());
    }

    /**
     * Polls until at least one Postgres backend is genuinely waiting (not granted) on a lock, with its
     * current query matching {@code queryLikePattern} — deterministic proof a blocking {@code FOR
     * UPDATE} contender has actually reached and is sitting on the row lock, not merely dispatched.
     * Requires a DB role that can read other backends' {@code pg_stat_activity.query} (superuser or
     * {@code pg_read_all_stats} — this codebase's Testcontainers Postgres role is superuser).
     *
     * @param queryLikePattern a SQL {@code LIKE} pattern matched against {@code pg_stat_activity.query},
     *                         e.g. {@code "%coach_reviews%"}
     */
    public static void awaitBlockingLockWaiter(JdbcTemplate jdbcTemplate, String queryLikePattern) {
        // Code review 2026-09-23: added a. datname = current_database() so this can never count a
        // waiter on a different database sharing the same Postgres instance/cluster. Deliberately NOT
        // filtered by l.relation/l.mode: a session blocked on a row already locked by
        // findByIdForUpdate's SELECT ... FOR UPDATE waits on the LOCKING TRANSACTION'S id
        // (locktype = 'transactionid'), not on a lock keyed to reviews.coach_reviews itself — Postgres's
        // row-lock wait protocol has no relation-scoped lock entry to filter on here, which is exactly
        // why this method matches on the waiter's query text instead.
        Awaitility.await()
            .atMost(Duration.ofSeconds(10))
            .pollInterval(Duration.ofMillis(25))
            .until(() -> {
                Integer count = jdbcTemplate.queryForObject(
                    "SELECT count(*) FROM pg_locks l JOIN pg_stat_activity a ON l.pid = a.pid "
                        + "WHERE NOT l.granted AND a.datname = current_database() AND a.query LIKE ?",
                    Integer.class, queryLikePattern);
                return count != null && count > 0;
            });
    }

    /**
     * The current {@code persistence.lock_retry.retries} counter value, to capture as a baseline
     * before starting a contended call — the counter is a single JVM-wide cumulative total across
     * every {@link com.softropic.skillars.infrastructure.persistence.PessimisticLockRetryer} call site
     * and every test in the run, so a poll must assert growth past a captured baseline, not merely
     * {@code > 0}.
     */
    public static double currentLockRetryCount(MeterRegistry meterRegistry) {
        Counter counter = meterRegistry.find("persistence.lock_retry.retries").counter();
        return counter == null ? 0.0 : counter.count();
    }

    /**
     * Asserts (via a short bounded poll, to absorb the meter's own recording happening on whichever
     * thread finishes last) that {@code persistence.lock_retry.retries} has grown past
     * {@code baseline} — direct, deterministic, POST-HOC proof a NOWAIT contender genuinely hit the
     * held lock and retried at least once, rather than racing past it uncontended. Call this AFTER
     * both the holder and the contender threads have been joined.
     */
    public static void assertGenuineLockRetryOccurred(MeterRegistry meterRegistry, double baseline) {
        Awaitility.await()
            .atMost(Duration.ofSeconds(5))
            .pollInterval(Duration.ofMillis(25))
            .until(() -> currentLockRetryCount(meterRegistry) > baseline);
    }
}
