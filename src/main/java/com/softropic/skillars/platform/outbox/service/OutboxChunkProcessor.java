package com.softropic.skillars.platform.outbox.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Processes one small chunk of {@code main.outbox_messages}, one row per transaction.
 *
 * <p>Deliberately <strong>not</strong> {@code @Transactional}: the transaction boundary lives in
 * {@link OutboxRowProcessor#claimAndHandle()} ({@code REQUIRES_NEW}, one row wide) and in
 * {@link OutboxRowProcessor#recordFailure} (a second, clean transaction). See
 * {@link OutboxRowProcessor} for why the previous chunk-wide transaction was unsafe.
 *
 * <p>{@code OutboxService.drain()} must NOT self-invoke a {@code @Transactional(REQUIRES_NEW)}
 * method — the proxy is bypassed, so the whole drain would run inside the AFTER_COMMIT listener's
 * context, pinning one pooled connection across every handler call. That is why the transactional
 * work sits on a separate bean, reached through the Spring proxy.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OutboxChunkProcessor {

    /**
     * How many rows one chunk will attempt before handing control back to {@code drain()}. Each row
     * is its own transaction, so this bounds a chunk's duration, not its connection-hold time.
     */
    static final int CHUNK_SIZE = 25;

    private final OutboxRowProcessor rowProcessor;

    /** Outcome of one chunk, so {@code OutboxService.drain()} can loop until the outbox is empty. */
    public record ChunkResult(int claimed, int processed, int failed) {
        public boolean drainedSomething() {
            return claimed > 0;
        }
    }

    /**
     * Attempts up to {@link #CHUNK_SIZE} rows, each claimed with {@code FOR UPDATE SKIP LOCKED} in
     * its own short transaction. Stops early once nothing is due.
     *
     * <p>A failed row is left in place with {@code attempts++} / {@code last_error} and a
     * {@code next_attempt_at} in the future (skillars-deferred-91 review D6), so it no longer
     * starves fresh work while it waits for a human. A row is <strong>never dropped</strong>.
     */
    public ChunkResult processChunk() {
        int claimed = 0;
        int processed = 0;
        int failed = 0;
        for (int i = 0; i < CHUNK_SIZE; i++) {
            try {
                final OutboxRowProcessor.Outcome outcome = rowProcessor.claimAndHandle();
                if (outcome == OutboxRowProcessor.Outcome.NONE) {
                    break;
                }
                claimed++;
                processed++;
            } catch (OutboxRowProcessor.OutboxRowFailure f) {
                claimed++;
                failed++;
                // Its own transaction — the claiming one has already rolled back, which is precisely
                // what makes this write possible after a DB-level failure aborted that transaction.
                rowProcessor.recordFailure(f.getRowId(), f.getCause());
            }
        }
        return new ChunkResult(claimed, processed, failed);
    }
}
