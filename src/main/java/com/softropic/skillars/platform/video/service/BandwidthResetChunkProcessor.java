package com.softropic.skillars.platform.video.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Resets one bounded chunk of {@code main.video_quotas} in its own short transaction
 * (skillars-deferred-92 AC9.2).
 *
 * <p><strong>A separate bean, deliberately.</strong> If {@code BandwidthResetService} called a
 * {@code @Transactional(REQUIRES_NEW)} method on itself, the call would not go through the Spring
 * proxy and the propagation would silently have no effect — every chunk would join one long
 * transaction, every row lock would be held until the end, and the result would be strictly
 * <em>worse</em> than the single {@code UPDATE} it replaced. skillars-deferred-90's 3-layer review
 * already forced {@code PendingBlobDeletionService} off exactly that shape; see
 * {@link com.softropic.skillars.platform.filestorage.service.PendingBlobDeletionChunkProcessor},
 * which this mirrors.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BandwidthResetChunkProcessor {

    /**
     * Rows per transaction. Small enough that the row locks a chunk holds are released promptly —
     * which is the entire point, since a concurrent {@code QuotaService.reserve()} takes a row lock on
     * the same table — and large enough that the loop is a few hundred statements, not tens of
     * thousands, at any plausible user count.
     */
    static final int CHUNK_SIZE = 500;

    /**
     * The predicate. It is deliberately identical to the one the single-statement version used, and
     * it is <strong>self-excluding</strong>: a reset row is stamped {@code bandwidth_period_start =
     * NOW()}, after which {@code DATE_TRUNC('month', NOW()) < DATE_TRUNC('month', NOW())} is false, so
     * the row cannot be selected again. That is what makes the loop terminate, makes it idempotent,
     * and makes it safely resumable after a crash mid-run — the next run simply finishes the rows the
     * previous one did not reach, with no double-reset.
     *
     * <p>AC9.2 asks that this be <em>verified</em> rather than assumed, because it leans on the same
     * column {@code Def8} records as drifting: {@code bandwidth_period_start} is stamped with the
     * actual run date rather than the period boundary. The two are compatible — a row stamped
     * mid-January still has {@code DATE_TRUNC('month', ...)} of January, so in February it is still
     * selected, and once reset in February it is not. Def8's drift changes <em>when in the month</em>
     * the period nominally starts; it does not change which month the predicate sees. Def8 is
     * explicitly out of scope and is not touched here.
     *
     * <p>{@code ORDER BY user_id} makes the chunking deterministic rather than dependent on scan
     * order, so a crash and re-run cannot interleave oddly.
     */
    private static final String CHUNK_SQL = """
        UPDATE main.video_quotas
           SET bandwidth_used_bytes = 0,
               bandwidth_period_start = NOW()
         WHERE user_id IN (
               SELECT user_id
                 FROM main.video_quotas
                WHERE DATE_TRUNC('month', bandwidth_period_start) < DATE_TRUNC('month', NOW())
                ORDER BY user_id
                LIMIT %d
         )
        """.formatted(CHUNK_SIZE);

    private final JdbcTemplate jdbcTemplate;

    /**
     * @return rows reset in this chunk; {@code 0} means the reset is complete
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int resetChunk() {
        return jdbcTemplate.update(CHUNK_SQL);
    }
}
