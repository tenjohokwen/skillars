package com.softropic.skillars.platform.outbox.service;

import com.softropic.skillars.platform.outbox.contract.OutboxMessageHandler;
import com.softropic.skillars.platform.outbox.repo.OutboxMessage;
import com.softropic.skillars.platform.outbox.repo.OutboxMessageRepository;

import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Claims and processes <em>one</em> outbox row per transaction, and records a failure in a
 * <em>separate</em> transaction.
 *
 * <p>skillars-deferred-91 code review (2026-09-03), decision D2. The original shape claimed a chunk
 * of 25 rows and dispatched all of them inside one {@code REQUIRES_NEW} transaction, recording
 * {@code attempts++} / {@code last_error} through that same transaction. That was safe for
 * skillars-deferred-90's {@code PendingBlobDeletionChunkProcessor}, whose work unit was a pure S3
 * call touching no JDBC connection — but two of this outbox's three handlers call a plain
 * {@code @Transactional} (REQUIRED) collaborator ({@code CreditWalletService.writeLedgerEntry},
 * {@code SnapshotBatchWriter.writeAllDeltas}) which <em>joins</em> the chunk transaction. Any
 * {@code DataIntegrityViolationException} — including the {@code uq_pcl_booking_refund} violation
 * that {@code CreditWalletRefundOutboxHandler} documents as its concurrency backstop — aborts the
 * PostgreSQL transaction, so the bookkeeping write could not execute: {@code attempts} never
 * incremented, {@code [OUTBOX_STUCK]} was unreachable, and up to 24 unrelated rows rolled back.
 *
 * <p>Now the transaction boundary is one row wide and the bookkeeping is a second, clean
 * transaction, so a poisoned row can never take its siblings — or its own attempt counter — with it.
 * Row-per-transaction also releases the DB connection between rows rather than between chunks of 25,
 * which matters because the handlers perform blocking network I/O.
 */
@Slf4j
@Component
public class OutboxRowProcessor {

    private static final int MAX_LAST_ERROR_LEN = 1000;

    /** First retry delay; doubles per attempt up to {@link #MAX_BACKOFF}. */
    private static final Duration BASE_BACKOFF = Duration.ofSeconds(30);
    private static final Duration MAX_BACKOFF = Duration.ofHours(1);

    private final OutboxMessageRepository repository;
    private final Map<String, OutboxMessageHandler> handlersByAggregateType;

    public OutboxRowProcessor(OutboxMessageRepository repository, List<OutboxMessageHandler> handlers) {
        this.repository = repository;
        this.handlersByAggregateType = handlers.stream()
            .collect(Collectors.toMap(OutboxMessageHandler::aggregateType, Function.identity()));
        log.info("[OUTBOX] registered {} handler(s): {}",
            handlersByAggregateType.size(), handlersByAggregateType.keySet());
    }

    /** What one claim attempt did. */
    public enum Outcome { NONE, PROCESSED }

    /**
     * Carries the claimed row's id out through the rollback, so the caller can record the failure in
     * a clean transaction. Thrown deliberately: the throw is what rolls back whatever partial work
     * the handler did before it failed.
     */
    public static class OutboxRowFailure extends RuntimeException {
        private final transient Long rowId;
        private final transient String aggregateType;

        OutboxRowFailure(Long rowId, String aggregateType, Throwable cause) {
            super(cause);
            this.rowId = rowId;
            this.aggregateType = aggregateType;
        }

        public Long getRowId() {
            return rowId;
        }

        public String getAggregateType() {
            return aggregateType;
        }
    }

    /**
     * Claims the single next due row with {@code FOR UPDATE SKIP LOCKED}, dispatches it to its
     * handler and deletes it on success.
     *
     * <p>{@code REQUIRES_NEW} genuinely applies — this is a cross-bean call from the
     * non-transactional {@link OutboxChunkProcessor}, so it goes through the Spring proxy.
     *
     * @return {@link Outcome#NONE} if nothing was due, {@link Outcome#PROCESSED} on success
     * @throws OutboxRowFailure if the handler failed; the transaction rolls back and the caller
     *                          records {@code attempts++} separately
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Outcome claimAndHandle() {
        final List<OutboxMessage> claimed = repository.claimNextDue(Instant.now(), PageRequest.of(0, 1));
        if (claimed.isEmpty()) {
            return Outcome.NONE;
        }
        final OutboxMessage row = claimed.get(0);
        try {
            final OutboxMessageHandler handler = handlersByAggregateType.get(row.getAggregateType());
            if (handler == null) {
                // Never dropped: it keeps its data safe until a deploy that carries the handler picks
                // it up. Common during a rolling deploy, which is why the backoff below matters.
                throw new IllegalStateException(
                    "no OutboxMessageHandler registered for aggregate_type=" + row.getAggregateType());
            }
            handler.handle(row.getPayload());
            repository.delete(row);
            return Outcome.PROCESSED;
        } catch (RuntimeException e) {
            throw new OutboxRowFailure(row.getId(), row.getAggregateType(), e);
        }
    }

    /**
     * Records {@code attempts++} / {@code last_error} / {@code next_attempt_at} for a row whose
     * handler failed, in its own clean transaction.
     *
     * <p>Separate from {@link #claimAndHandle()} on purpose: by the time we get here the claiming
     * transaction has rolled back, which is exactly what makes this write possible when the failure
     * was a DB-level one that aborted the PostgreSQL transaction.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordFailure(Long rowId, Throwable cause) {
        repository.findById(rowId).ifPresent(row -> {
            final int attempts = row.getAttempts() + 1;
            row.setAttempts(attempts);
            row.setLastError(truncate(String.valueOf(cause)));
            row.setNextAttemptAt(Instant.now().plus(backoffFor(attempts)));
            repository.save(row);
            log.warn("[OUTBOX_RETRY] aggregateType={} id={} attempts={} nextAttemptAt={}",
                row.getAggregateType(), rowId, attempts, row.getNextAttemptAt(), cause);
        });
    }

    /** 30s, 1m, 2m, 4m … capped at 1h. Deterministic — no jitter needed, SKIP LOCKED handles pods. */
    static Duration backoffFor(int attempts) {
        if (attempts <= 1) {
            return BASE_BACKOFF;
        }
        final int shift = Math.min(attempts - 1, 32);
        final long seconds = BASE_BACKOFF.getSeconds() << shift;
        if (seconds <= 0 || seconds > MAX_BACKOFF.getSeconds()) {
            return MAX_BACKOFF;
        }
        return Duration.ofSeconds(seconds);
    }

    private static String truncate(String s) {
        if (s == null) {
            return null;
        }
        return s.length() <= MAX_LAST_ERROR_LEN ? s : s.substring(0, MAX_LAST_ERROR_LEN);
    }
}
