package com.softropic.skillars.platform.video.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Monthly bandwidth-quota reset (skillars-deferred-92 AC9.2 — {@code Def10}).
 *
 * <p>This used to be a single unpartitioned {@code UPDATE} over every row of
 * {@code main.video_quotas} at the month boundary. That takes a row lock on <em>every</em> quota row
 * and holds all of them until the statement finishes, so for its whole duration no
 * {@code QuotaService.reserve()} anywhere in the system can proceed — the entire video-upload path
 * stalls at 00:00 UTC on the 1st. It is now a bounded loop of short, independently-committed chunks.
 *
 * <p><strong>This method must NOT be {@code @Transactional}, and that is a correctness requirement,
 * not a style preference.</strong> A chunked loop inside one long transaction holds every row lock it
 * has taken until the very end, which is strictly worse than the single statement it replaced: same
 * total lock footprint, but held for longer. The per-chunk boundary lives in
 * {@link BandwidthResetChunkProcessor}, a separate bean so {@code REQUIRES_NEW} actually goes through
 * the proxy. {@code SchedulerLockTransactionOrderingIT} pins the absence of {@code @Transactional}
 * here so it cannot be re-added by someone tidying up.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BandwidthResetService {

    /**
     * Safety stop, so a predicate that somehow stops excluding reset rows cannot spin forever. At
     * {@link BandwidthResetChunkProcessor#CHUNK_SIZE} this bounds one run at 5,000,000 rows — far
     * above any plausible user count, so reaching it means a bug, and the ERROR below says so.
     */
    private static final int MAX_CHUNKS = 10_000;

    private final BandwidthResetChunkProcessor chunkProcessor;

    /**
     * {@code lockAtMostFor} raised from {@code PT10M} to {@code PT30M} (AC9.2): a chunked loop takes
     * materially longer in wall-clock than one statement, and if the lock expired mid-run a second
     * node would start a concurrent reset. The assumption behind 30 minutes: at
     * {@link BandwidthResetChunkProcessor#CHUNK_SIZE} 500 rows per chunk and a conservative 50 ms per
     * chunk (an indexed range update on a narrow table), 30 minutes covers ~18,000,000 rows — three
     * orders of magnitude above any realistic quota-row count, which is the margin this kind of lock
     * deserves. Revisit if the table ever approaches that.
     */
    @Scheduled(cron = "0 0 0 1 * ?", zone = "UTC")
    @SchedulerLock(name = "BandwidthResetService_reset",
                   lockAtMostFor = "PT30M", lockAtLeastFor = "PT1M")
    public void resetMonthlyBandwidth() {
        int chunks = drainReset();
        log.info("Monthly bandwidth reset complete in {} chunk(s)", chunks);
    }

    /**
     * Runs the reset to completion, one committed chunk at a time.
     *
     * <p>Public and returning the chunk count so a test can assert that a row count above
     * {@link BandwidthResetChunkProcessor#CHUNK_SIZE} really does take more than one chunk — without
     * that assertion "it is chunked now" would be a claim rather than a checked property.
     *
     * @return the number of chunks that reset at least one row
     */
    public int drainReset() {
        int chunks = 0;
        long total = 0;
        for (int i = 0; i < MAX_CHUNKS; i++) {
            int reset = chunkProcessor.resetChunk();
            if (reset == 0) {
                break;
            }
            chunks++;
            total += reset;
            if (i == MAX_CHUNKS - 1) {
                // Only reachable if the predicate stopped excluding already-reset rows, which would
                // mean the loop was never going to terminate. Loud, because the alternative is a job
                // that runs forever holding a scheduler lock.
                log.error("[BANDWIDTH_RESET_CHUNK_LIMIT] stopped after {} chunks ({} rows) without the "
                    + "predicate going empty — the reset is incomplete and the self-excluding WHERE "
                    + "clause in BandwidthResetChunkProcessor needs investigating", MAX_CHUNKS, total);
            }
        }
        log.info("Monthly bandwidth reset applied to {} video quota row(s) across {} chunk(s)", total, chunks);
        return chunks;
    }
}
