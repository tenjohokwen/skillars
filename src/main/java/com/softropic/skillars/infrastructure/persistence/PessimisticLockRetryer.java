package com.softropic.skillars.infrastructure.persistence;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.annotation.PostConstruct;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.extern.slf4j.Slf4j;
import org.hibernate.Session;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.stereotype.Component;

import java.sql.Savepoint;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;

/**
 * Retries a pessimistic-lock read/refresh sequence that failed immediately (NO_WAIT) because
 * another transaction holds the row, so that a brief, legitimate overlap between two requests
 * still succeeds instead of surfacing a 409 on the first collision.
 *
 * <p>A caught {@link PessimisticLockingFailureException} leaves the enclosing PostgreSQL
 * transaction aborted — every later statement fails with "current transaction is aborted" until
 * either the whole transaction rolls back or execution resumes from a savepoint taken before the
 * failed statement. Spring's declarative {@code Propagation.NESTED} cannot provide that savepoint
 * here ({@code DefaultJpaDialect} reports no savepoint support), so this class manages one
 * directly against the JDBC {@link java.sql.Connection} via {@link Session#doWork}, letting the
 * caller's own transaction retry in place rather than needing a transaction of its own.
 *
 * <p>Each attempt flushes the persistence context before taking its savepoint. Without this,
 * a pending write made earlier in the same transaction (still unflushed) could be auto-flushed by
 * the locked query itself, then silently discarded by a rollback-to-savepoint on that attempt's
 * failure — Hibernate would never learn the flush was undone and would not re-flush it at commit.
 * Flushing first keeps the savepoint boundary aligned with what it actually protects: this
 * attempt's own locked read, not unrelated prior writes.
 *
 * <h2>Cost model — a documented, sized tradeoff (skillars-deferred-99 AC10)</h2>
 *
 * <p><strong>The retry loop sleeps while still holding the caller's transaction's pooled JDBC
 * connection.</strong> The savepoint mechanism above is what lets the caller retry <em>in place</em>
 * rather than in a transaction of its own, and the price of "in place" is that the connection is
 * not released between attempts — under contention a request can occupy a HikariCP connection for
 * up to the worst-case jittered backoff budget (~3.2s with the defaults: 8 attempts,
 * 100ms→800ms×1.6). This is accepted, not overlooked: {@code DefaultJpaDialect} exposes no
 * savepoint support so declarative {@code Propagation.NESTED} is unavailable, and all current call
 * sites are short read-then-maybe-refresh operations ({@code findByIdForUpdate} + optional
 * {@code refresh}). If a future call site is long-running, or the pool is small relative to the
 * contended row's traffic, revisit this — a connection-releasing mechanism would be a larger change
 * (its own transaction per attempt) and is deliberately out of scope here.
 *
 * <p>The wait is observable: a {@code persistence.lock_retry} {@link Timer} (tag {@code outcome} =
 * {@code success} / {@code exhausted} / {@code error}) records the wall-clock time spent in
 * {@link #withBoundedRetry}, and {@code persistence.lock_retry.retries} /
 * {@code persistence.lock_retry.exhausted} counters track how hard the loop is working. Watch the
 * timer's p99 against the HikariCP pool size.
 */
@Slf4j
@Component
public class PessimisticLockRetryer {

    @PersistenceContext
    private EntityManager entityManager;

    private final MeterRegistry meterRegistry;

    public PessimisticLockRetryer(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    @Value("${app.locking.retry.max-attempts:8}")
    private int maxAttempts;

    @Value("${app.locking.retry.initial-backoff-ms:100}")
    private long initialBackoffMs;

    @Value("${app.locking.retry.max-backoff-ms:800}")
    private long maxBackoffMs;

    @Value("${app.locking.retry.backoff-multiplier:1.6}")
    private double backoffMultiplier;

    @PostConstruct
    void validateConfig() {
        if (maxAttempts < 1) {
            throw new IllegalStateException(
                "app.locking.retry.max-attempts must be >= 1, was " + maxAttempts);
        }
        if (initialBackoffMs <= 0) {
            throw new IllegalStateException(
                "app.locking.retry.initial-backoff-ms must be > 0, was " + initialBackoffMs);
        }
        if (maxBackoffMs < initialBackoffMs) {
            throw new IllegalStateException(
                "app.locking.retry.max-backoff-ms (" + maxBackoffMs
                    + ") must be >= initial-backoff-ms (" + initialBackoffMs + ")");
        }
        if (backoffMultiplier < 1.0) {
            throw new IllegalStateException(
                "app.locking.retry.backoff-multiplier must be >= 1.0, was " + backoffMultiplier);
        }

        // Estimate worst-case total backoff budget (using jitter floor of 75%)
        long worstCaseMs = 0;
        long currentBackoff = initialBackoffMs;
        for (int i = 0; i < maxAttempts - 1; i++) {
            worstCaseMs += (long) (currentBackoff * 0.75);
            currentBackoff = Math.min((long) (currentBackoff * backoffMultiplier), maxBackoffMs);
        }
        if (worstCaseMs > 30000) {  // 30 second limit
            log.warn("PessimisticLockRetryer configured with worst-case backoff of ~{}ms ({}s). " +
                    "Verify this aligns with application request timeout constraints. " +
                    "Config: maxAttempts={}, initialBackoff={}ms, maxBackoff={}ms, multiplier={}",
                worstCaseMs, worstCaseMs / 1000, maxAttempts, initialBackoffMs, maxBackoffMs, backoffMultiplier);
        }
    }

    /**
     * Runs {@code lockedOperation} (a {@code findByIdForUpdate(...).orElseThrow(...)}, optionally
     * followed by an {@code entityManager.refresh(entity, PESSIMISTIC_WRITE)}) inside the caller's
     * current transaction, retrying it from a fresh savepoint each time it fails with a
     * {@link PessimisticLockingFailureException}. Any other exception — including a genuine
     * not-found — propagates immediately, unretried.
     */
    public <T> T withBoundedRetry(Supplier<T> lockedOperation) {
        Session session = entityManager.unwrap(Session.class);
        long backoffMillis = initialBackoffMs;
        Timer.Sample sample = Timer.start(meterRegistry);
        int retries = 0;
        try {
            for (int attempt = 1; attempt <= maxAttempts; attempt++) {
                entityManager.flush();
                Savepoint[] savepointHolder = new Savepoint[1];
                session.doWork(connection -> savepointHolder[0] = connection.setSavepoint());
                try {
                    T result = lockedOperation.get();
                    session.doWork(connection -> connection.releaseSavepoint(savepointHolder[0]));
                    recordRetries(retries);
                    sample.stop(timer("success"));
                    return result;
                } catch (PessimisticLockingFailureException e) {
                    if (attempt == maxAttempts) {
                        log.warn("Giving up on a pessimistic lock after {} attempts; surfacing contention", attempt);
                        recordRetries(retries);
                        meterRegistry.counter("persistence.lock_retry.exhausted").increment();
                        sample.stop(timer("exhausted"));
                        throw e;
                    }
                    retries++;
                    session.doWork(connection -> connection.rollback(savepointHolder[0]));
                    sleep(jitter(backoffMillis));
                    backoffMillis = Math.min((long) (backoffMillis * backoffMultiplier), maxBackoffMs);
                }
            }
            throw new IllegalStateException("unreachable: loop above always returns or throws");
        } catch (RuntimeException e) {
            // A non-lock failure (the operation's own orElseThrow not-found, or a sleep interrupt)
            // still exits here; record it so the timer's count matches the call count. The
            // exhausted-lock path already stopped the sample above, so skip it to avoid a double stop.
            if (!(e instanceof PessimisticLockingFailureException)) {
                recordRetries(retries);
                sample.stop(timer("error"));
            }
            throw e;
        }
    }

    private void recordRetries(int retries) {
        if (retries > 0) {
            meterRegistry.counter("persistence.lock_retry.retries").increment(retries);
        }
    }

    private Timer timer(String outcome) {
        return Timer.builder("persistence.lock_retry")
            .description("Wall-clock time a caller spent in PessimisticLockRetryer.withBoundedRetry, "
                + "holding its pooled JDBC connection while sleeping between attempts")
            .tag("outcome", outcome)
            .register(meterRegistry);
    }

    /**
     * Randomizes the actual sleep duration within [75%, 100%] of the computed backoff so that two
     * threads contending for the same row, which start retrying at nearly the same moment, don't
     * keep landing back-to-back on the same deterministic schedule and re-colliding every round.
     * Floor kept relatively high (not the more usual 50%): with the default 8-attempt budget, a
     * lower floor lets the worst-case jittered total wait dip below ~2s, which
     * BookingServiceConcurrencyIT's fixed 2s "should succeed" contention holds could then
     * occasionally outlast, surfacing a flaky give-up instead of the retry resolving.
     */
    private long jitter(long backoffMillis) {
        double factor = 0.75 + ThreadLocalRandom.current().nextDouble() * 0.25;
        return Math.max(1, (long) (backoffMillis * factor));
    }

    private void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while retrying a pessimistic lock acquisition", e);
        }
    }
}
