package com.softropic.skillars.platform.filestorage.service;

import com.softropic.skillars.platform.filestorage.contract.event.BlobDeletionsEnqueuedEvent;
import com.softropic.skillars.platform.filestorage.repo.PendingBlobDeletion;
import com.softropic.skillars.platform.filestorage.repo.PendingBlobDeletionRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.Collection;
import java.util.List;

/**
 * skillars-deferred-90 AC13: durable pending-deletion outbox for storage keys that must not be
 * deleted from S3 inside a request / erasure transaction (which would hold the DB connection across
 * N sequential blocking round-trips).
 *
 * <p>Producers call {@link #enqueue} <em>inside</em> their transaction, then
 * {@link #requestDrainAfterCommit()} (also inside it). The
 * {@code @TransactionalEventListener(AFTER_COMMIT)} below then drains the table once the producer's
 * transaction has committed, off its thread's request path. A key whose S3 delete fails stays in
 * the table with an incremented {@code attempts} for the next drain — so a post-commit failure is
 * re-drivable rather than silently lost.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PendingBlobDeletionService {

    /**
     * Attempts after which a row is reported as stuck. It is still retried — a pending row is a
     * storage key holding PII that a GDPR erasure has already committed to deleting, so it is never
     * dropped. Crossing this threshold means the deletion needs a human, not that it is abandoned.
     */
    private static final int STUCK_ATTEMPTS_THRESHOLD = 10;

    /**
     * Safety stop for the drain loop, so a chunk that keeps failing (and therefore keeps being
     * re-claimed) cannot spin forever. Bounds one drain at CHUNK_SIZE * this many rows.
     */
    private static final int MAX_CHUNKS_PER_DRAIN = 200;

    private final PendingBlobDeletionRepository repository;
    private final PendingBlobDeletionChunkProcessor chunkProcessor;
    private final ApplicationEventPublisher eventPublisher;

    /** Enqueue storage keys for post-commit deletion. MUST be called inside the business transaction. */
    public void enqueue(Collection<String> storageKeys) {
        if (storageKeys == null || storageKeys.isEmpty()) {
            return;
        }
        List<PendingBlobDeletion> rows = storageKeys.stream()
            .filter(k -> k != null && !k.isBlank())
            .distinct()
            .map(PendingBlobDeletion::new)
            .toList();
        if (!rows.isEmpty()) {
            repository.saveAll(rows);
        }
    }

    /** Publish inside the business transaction so the drain runs exactly once, AFTER it commits. */
    public void requestDrainAfterCommit() {
        eventPublisher.publishEvent(new BlobDeletionsEnqueuedEvent());
    }

    /**
     * Drains the outbox once the producing transaction has committed.
     *
     * <p>Deliberately NOT {@code @Transactional}: the work happens in
     * {@link PendingBlobDeletionChunkProcessor#processChunk()}, one short {@code REQUIRES_NEW}
     * transaction per chunk. Code review (3-layer run) found the previous shape — a
     * {@code @Transactional(REQUIRES_NEW)} listener calling a self-invoked {@code drain()} — held a
     * single pooled connection across up to 500 sequential blocking S3 deletes, i.e. the exact
     * anti-pattern V122 exists to remove, relocated to the listener thread rather than eliminated.
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onBlobDeletionsEnqueued(BlobDeletionsEnqueuedEvent event) {
        drain();
    }

    /**
     * Periodic safety net for the AFTER_COMMIT drain (code review of skillars-deferred-90, D1).
     *
     * <p>Without this the only trigger was a <em>subsequent</em> erasure's AFTER_COMMIT, so a key
     * whose S3 delete failed — or a JVM death between commit and drain — left PII in S3 until
     * someone else happened to run an erasure, which on a low-traffic system may be never. That
     * made AC13's "failures are re-drivable on the next sweep" guarantee only half-true: the outbox
     * was durable, but nothing swept it.
     *
     * <p>Disabled under the test profile with every other job via {@code app.scheduling.enabled}
     * ({@code infrastructure.config.SchedulingConfig}); tests call {@link #drain()} directly.
     */
    @Scheduled(fixedDelayString = "${app.storage.pending-deletion-sweep-ms:300000}")
    @SchedulerLock(name = "PendingBlobDeletionService_sweep",
                   lockAtMostFor = "PT10M", lockAtLeastFor = "PT1M")
    public void sweep() {
        drain();
        final long stuck = repository.countByAttemptsGreaterThanEqual(STUCK_ATTEMPTS_THRESHOLD);
        if (stuck > 0) {
            // Loud on purpose: these are storage keys an erasure already committed to deleting.
            log.error("[PENDING_BLOB_DELETION_STUCK] {} key(s) have failed >= {} times and still hold "
                    + "data a GDPR erasure committed to deleting — needs manual investigation",
                stuck, STUCK_ATTEMPTS_THRESHOLD);
        }
    }

    /**
     * Drains the outbox chunk by chunk until it yields nothing, so one erasure that enqueues more
     * than a chunk's worth of keys is fully drained by its own trigger instead of leaving the
     * remainder to wait for the next {@link #sweep()}.
     *
     * <p>Each chunk is a separate short transaction (see {@link PendingBlobDeletionChunkProcessor}),
     * so the DB connection is released between chunks and total hold time no longer scales with the
     * number of blobs.
     */
    public void drain() {
        int deleted = 0;
        int failed = 0;
        for (int i = 0; i < MAX_CHUNKS_PER_DRAIN; i++) {
            final PendingBlobDeletionChunkProcessor.ChunkResult result = chunkProcessor.processChunk();
            deleted += result.deleted();
            failed += result.failed();
            if (!result.drainedSomething()) {
                break;
            }
            // A chunk of pure failures leaves its rows in place; they would be re-claimed forever.
            // Stop this drain and let the next sweep retry them (attempts ordering has now demoted
            // them below any fresh work).
            if (result.deleted() == 0) {
                break;
            }
        }
        if (deleted > 0 || failed > 0) {
            log.info("[PENDING_BLOB_DELETION_DRAIN] deleted={} failed={} (remaining will retry next drain)",
                deleted, failed);
        }
    }
}
