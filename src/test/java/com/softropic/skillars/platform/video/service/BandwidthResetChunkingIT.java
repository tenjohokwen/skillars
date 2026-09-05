package com.softropic.skillars.platform.video.service;

import com.softropic.skillars.config.AbstractIntegrationTest;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * skillars-deferred-92 AC9.2 ({@code Def10}) — the monthly bandwidth reset really is chunked, really
 * is idempotent, and really is resumable.
 *
 * <p>The old implementation was one unpartitioned {@code UPDATE} over every row of
 * {@code main.video_quotas}, which takes a row lock on all of them and holds it for the duration —
 * so at 00:00 UTC on the 1st, no {@code QuotaService.reserve()} anywhere could proceed until it
 * finished.
 *
 * <p>The load-bearing assertion is {@link #rowCountAboveChunkSize_takesMoreThanOneChunk()}: "it is
 * chunked now" is otherwise a claim about the code rather than a property of its behaviour, and a
 * single-chunk run would satisfy every other assertion here just as well.
 */
class BandwidthResetChunkingIT extends AbstractIntegrationTest {

    /** One more than a chunk, so a correct implementation needs exactly two chunks. */
    private static final int ROWS = BandwidthResetChunkProcessor.CHUNK_SIZE + 1;
    private static final String USER_PREFIX = "ac9-bandwidth-";

    @Autowired private BandwidthResetService bandwidthResetService;
    @Autowired private BandwidthResetChunkProcessor chunkProcessor;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private TransactionTemplate transactionTemplate;

    @BeforeEach
    void seed() {
        transactionTemplate.executeWithoutResult(s -> {
            // Scoped to this test's own rows. main.video_quotas is shared with the video ITs in this
            // context, and the reset is a table-wide operation — deleting anything else would break them.
            jdbcTemplate.update("DELETE FROM main.video_quotas WHERE user_id LIKE ?", USER_PREFIX + "%");

            List<Object[]> batch = new ArrayList<>(ROWS);
            for (int i = 0; i < ROWS; i++) {
                batch.add(new Object[] {USER_PREFIX + String.format("%05d", i)});
            }
            // bandwidth_period_start two months back, so every row matches the reset predicate
            // regardless of where in the current month the test happens to run.
            jdbcTemplate.batchUpdate(
                "INSERT INTO main.video_quotas (user_id, storage_used_bytes, bandwidth_used_bytes, "
                    + "bandwidth_period_start) VALUES (?, 0, 4096, NOW() - INTERVAL '2 months')",
                batch);
        });
    }

    private long myRowsStillNeedingReset() {
        return jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM main.video_quotas WHERE user_id LIKE ? AND bandwidth_used_bytes <> 0",
            Long.class, USER_PREFIX + "%");
    }

    @Test
    @DisplayName("a row count above the chunk size takes more than one chunk, and resets every row")
    void rowCountAboveChunkSize_takesMoreThanOneChunk() {
        int chunks = bandwidthResetService.drainReset();

        assertThat(chunks)
            .as("""
                %d rows at a chunk size of %d must take at least two chunks. One chunk would mean the \
                loop is not really bounded — i.e. the single table-wide UPDATE that locked every quota \
                row for the whole statement is still there under a new name.""",
                ROWS, BandwidthResetChunkProcessor.CHUNK_SIZE)
            .isGreaterThanOrEqualTo(2);

        assertThat(myRowsStillNeedingReset())
            .as("chunking must not leave rows behind — every seeded row was due a reset")
            .isZero();
    }

    @Test
    @DisplayName("a second run is a no-op: the predicate excludes rows it has already reset")
    void secondRun_isANoOp() {
        bandwidthResetService.drainReset();

        assertThat(bandwidthResetService.drainReset())
            .as("""
                The WHERE clause is self-excluding — a reset row is stamped bandwidth_period_start = \
                NOW(), so DATE_TRUNC('month', NOW()) < DATE_TRUNC('month', NOW()) is false. That is \
                what terminates the loop and what makes a crashed run safely resumable rather than \
                double-resetting. If this is non-zero the loop's termination condition is broken.""")
            .isZero();
    }

    /**
     * The resumability half of AC9.2, exercised rather than argued: interrupt the run after one chunk
     * and confirm a later run finishes the rest without touching what the first one already did.
     */
    @Test
    @DisplayName("a run interrupted after one chunk is completed by the next run, with no double reset")
    void interruptedRun_isResumedNotRestarted() {
        long before = myRowsStillNeedingReset();
        assertThat(before).isEqualTo(ROWS);

        // Simulate a crash after exactly one committed chunk. Calling the processor bean directly is
        // the point: it proves the chunk commits on its own rather than as part of a larger unit.
        int firstChunk = chunkProcessor.resetChunk();
        assertThat(firstChunk)
            .as("one chunk must reset exactly CHUNK_SIZE rows when more than that are due")
            .isEqualTo(BandwidthResetChunkProcessor.CHUNK_SIZE);
        assertThat(myRowsStillNeedingReset())
            .as("the committed chunk survives the 'crash' — that is what makes this resumable")
            .isEqualTo(ROWS - BandwidthResetChunkProcessor.CHUNK_SIZE);

        bandwidthResetService.drainReset();

        assertThat(myRowsStillNeedingReset())
            .as("the follow-up run finishes the remainder")
            .isZero();
    }
}
