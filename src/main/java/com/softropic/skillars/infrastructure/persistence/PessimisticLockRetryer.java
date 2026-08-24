package com.softropic.skillars.infrastructure.persistence;

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
 */
@Slf4j
@Component
public class PessimisticLockRetryer {

    @PersistenceContext
    private EntityManager entityManager;

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
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            entityManager.flush();
            Savepoint[] savepointHolder = new Savepoint[1];
            session.doWork(connection -> savepointHolder[0] = connection.setSavepoint());
            try {
                T result = lockedOperation.get();
                session.doWork(connection -> connection.releaseSavepoint(savepointHolder[0]));
                return result;
            } catch (PessimisticLockingFailureException e) {
                if (attempt == maxAttempts) {
                    log.warn("Giving up on a pessimistic lock after {} attempts; surfacing contention", attempt);
                    throw e;
                }
                session.doWork(connection -> connection.rollback(savepointHolder[0]));
                sleep(jitter(backoffMillis));
                backoffMillis = Math.min((long) (backoffMillis * backoffMultiplier), maxBackoffMs);
            }
        }
        throw new IllegalStateException("unreachable: loop above always returns or throws");
    }

    /**
     * Randomizes the actual sleep duration within [50%, 100%] of the computed backoff so that two
     * threads contending for the same row, which start retrying at nearly the same moment, don't
     * keep landing back-to-back on the same deterministic schedule and re-colliding every round.
     */
    private long jitter(long backoffMillis) {
        double factor = 0.5 + ThreadLocalRandom.current().nextDouble() * 0.5;
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
