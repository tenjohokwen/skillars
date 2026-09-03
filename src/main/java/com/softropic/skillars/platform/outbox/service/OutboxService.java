package com.softropic.skillars.platform.outbox.service;

import com.softropic.skillars.platform.outbox.contract.event.OutboxDrainRequestedEvent;
import com.softropic.skillars.platform.outbox.repo.OutboxMessage;
import com.softropic.skillars.platform.outbox.repo.OutboxMessageRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * skillars-deferred-91 AC1: a generic durable transactional outbox, extracted from
 * skillars-deferred-90's {@code PendingBlobDeletionService} and its 3-layer-reviewed drain shape.
 *
 * <p>Producers call {@link #enqueue} <em>inside</em> their business transaction, then
 * {@link #requestDrainAfterCommit()} (also inside it). The
 * {@code @TransactionalEventListener(AFTER_COMMIT)} listener below drains the table once that
 * transaction has committed, off the request path; the {@link #sweep()} scheduler is the safety net
 * for a drain that never fired (JVM death between commit and drain) or a row that keeps failing.
 * A row is <strong>never dropped</strong> — it may be money or a compliance-relevant notification.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OutboxService {

    /**
     * Attempts after which a row is reported as stuck. It is still retried forever — an outbox row
     * is a refund, an email, or an SLU delta that a committed transaction promised. Crossing this
     * threshold means the operation needs a human, not that it is abandoned.
     */
    private static final int STUCK_ATTEMPTS_THRESHOLD = 10;

    /**
     * Safety stop for the drain loop so a chunk of pure failures (re-claimed each pass) cannot spin
     * forever. Bounds one drain at {@code CHUNK_SIZE * this}.
     */
    private static final int MAX_CHUNKS_PER_DRAIN = 200;

    /** Transaction-scoped marker so repeated {@code requestDrainAfterCommit()} calls fire one drain. */
    private static final String DRAIN_REQUESTED_RESOURCE_KEY = "skillars.outbox.drainRequested";

    private final OutboxMessageRepository repository;
    private final OutboxChunkProcessor chunkProcessor;
    private final ApplicationEventPublisher eventPublisher;

    /** Enqueue one message. MUST be called inside the producing business transaction. */
    public void enqueue(String aggregateType, String payload) {
        repository.save(new OutboxMessage(aggregateType, payload));
    }

    /**
     * Publish inside the business transaction so exactly one drain fires, AFTER it commits.
     *
     * <p>skillars-deferred-91 review: "exactly one" is enforced per <em>transaction</em>, not per
     * call. Producers that enqueue in a loop — {@code BookingEmailListener.onRescheduleAccepted} and
     * {@code onBookingReminder} iterate over recipients — previously published one marker per
     * recipient, so N recipients triggered N full drains. The transaction-scoped resource below
     * collapses them to one, and is unbound on completion so the next transaction gets its own.
     */
    public void requestDrainAfterCommit() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            if (TransactionSynchronizationManager.hasResource(DRAIN_REQUESTED_RESOURCE_KEY)) {
                return;
            }
            TransactionSynchronizationManager.bindResource(DRAIN_REQUESTED_RESOURCE_KEY, Boolean.TRUE);
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCompletion(int status) {
                    TransactionSynchronizationManager.unbindResourceIfPossible(DRAIN_REQUESTED_RESOURCE_KEY);
                }
            });
        }
        eventPublisher.publishEvent(new OutboxDrainRequestedEvent());
    }

    /**
     * Drains the outbox once the producing transaction has committed.
     *
     * <p>Deliberately NOT {@code @Transactional}: the work happens in
     * {@link OutboxRowProcessor#claimAndHandle()}, one short {@code REQUIRES_NEW} transaction per
     * row. Do NOT regress to a self-invoked {@code @Transactional(REQUIRES_NEW) drain()} — see
     * {@link OutboxRowProcessor}.
     *
     * <p>{@code @Async} (skillars-deferred-91 review D3): AFTER_COMMIT synchronizations run on the
     * committing thread, so without it the drain executed inline on the HTTP request thread — up to
     * {@code CHUNK_SIZE * MAX_CHUNKS_PER_DRAIN} rows of blocking SMTP/Stripe I/O before the response
     * was written, meaning one unlucky user's booking absorbed the whole backlog that accumulated
     * during an outage. AC1 requires the drainer to run "off the request path"; this is what makes
     * that true. The {@code @Scheduled} sweep below remains the safety net.
     */
    @Async("outboxDrainPool")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onOutboxDrainRequested(OutboxDrainRequestedEvent event) {
        drain();
    }

    /**
     * Periodic safety net for the AFTER_COMMIT drain. Disabled under the test profile with every
     * other job via {@code app.scheduling.enabled} ({@code infrastructure.config.SchedulingConfig});
     * tests call {@link #drain()} directly.
     */
    @Scheduled(fixedDelayString = "${app.outbox.sweep-ms:300000}")
    @SchedulerLock(name = "OutboxService_sweep", lockAtMostFor = "PT10M", lockAtLeastFor = "PT1M")
    public void sweep() {
        drain();
        final long stuck = repository.countByAttemptsGreaterThanEqual(STUCK_ATTEMPTS_THRESHOLD);
        if (stuck > 0) {
            // Loud on purpose: each stuck row is a committed operation — a refund, an email, an SLU
            // delta — that still has not run.
            log.error("[OUTBOX_STUCK] {} outbox message(s) have failed >= {} times and still hold an "
                    + "operation a committed transaction promised — needs manual investigation",
                stuck, STUCK_ATTEMPTS_THRESHOLD);
        }
    }

    /**
     * Drains chunk by chunk until a chunk yields nothing, so one producer that enqueues more than a
     * chunk's worth is fully drained by its own trigger. Each chunk is a separate short transaction,
     * so the DB connection is released between chunks.
     */
    public void drain() {
        int processed = 0;
        int failed = 0;
        try {
            for (int i = 0; i < MAX_CHUNKS_PER_DRAIN; i++) {
                final OutboxChunkProcessor.ChunkResult result = chunkProcessor.processChunk();
                processed += result.processed();
                failed += result.failed();
                if (!result.drainedSomething()) {
                    break;
                }
                // A chunk of pure failures leaves its rows in place with a future next_attempt_at,
                // so they are no longer immediately re-claimable. Stop this drain anyway and let the
                // next sweep pick them up once their backoff expires.
                if (result.processed() == 0) {
                    break;
                }
            }
        } catch (RuntimeException e) {
            // skillars-deferred-91 review: never let a drain failure escape. On the AFTER_COMMIT path
            // Spring propagates afterCommit exceptions out of commit(), so an unguarded throw here
            // returned HTTP 500 for a business transaction that had already committed durably — the
            // client then retried an operation that in fact succeeded. The drain is best-effort by
            // construction: every row it did not process is still in the table with its backoff.
            log.error("[OUTBOX_DRAIN_FAILED] drain aborted after processed={} failed={} — rows remain "
                + "in the outbox and will be retried by the next sweep", processed, failed, e);
        }
        if (processed > 0 || failed > 0) {
            log.info("[OUTBOX_DRAIN] processed={} failed={} (remaining will retry next drain)", processed, failed);
        }
    }
}
