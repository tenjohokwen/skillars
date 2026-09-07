package com.softropic.skillars.platform.video.service;

import com.softropic.skillars.infrastructure.util.ClockProvider;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;

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
 *
 * <p><strong>Race window and acceptable risk (skillars-deferred-93 AC5):</strong> The reset
 * processes rows in 500-row chunks, releasing row locks between chunks. A concurrent
 * {@code QuotaService.incrementBandwidthUsedBytes()} (called on every video playback) has no row
 * lock or period check, so within the same monthly period, a race window exists: increment happens
 * while reset runs, or reset happens while increment is in flight. Result: a benign sub-minute skew
 * in the {@code bandwidth_used_bytes} counter. This is accepted because: (1) {@code bandwidth_used_bytes}
 * is display-only (gates no rate-limit or quota enforcement), and (2) the next monthly reset
 * corrects the skew. Once per month, within acceptable tolerance.
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
     * <p>skillars-deferred-99 AC12 (was {@code Def8}): {@code bandwidth_period_start} is now stamped
     * with the <strong>calendar first-of-month at 00:00 UTC</strong>
     * ({@code YearMonth.now(clock).atDay(1)...}), not {@code NOW()} on the actual run date, so a
     * job that fires late does not permanently shift the monthly boundary forward. Both the SET
     * value and the predicate's "current month" comparison are bound parameters derived from one
     * captured {@link ClockProvider} instant, which also makes the self-exclusion deterministic
     * under a pinned test clock (with {@code NOW()} the predicate compared against real wall time).
     *
     * <p>{@code ORDER BY user_id} makes the chunking deterministic rather than dependent on scan
     * order, so a crash and re-run cannot interleave oddly.
     */
    private static final String CHUNK_SQL = """
        UPDATE main.video_quotas
           SET bandwidth_used_bytes = 0,
               bandwidth_period_start = ?
         WHERE user_id IN (
               SELECT user_id
                 FROM main.video_quotas
                WHERE DATE_TRUNC('month', bandwidth_period_start) < DATE_TRUNC('month', CAST(? AS timestamptz))
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
        Instant now = Instant.now(ClockProvider.getClock());
        Instant periodStart = YearMonth.from(now.atZone(ZoneOffset.UTC))
            .atDay(1).atStartOfDay(ZoneOffset.UTC).toInstant();
        return jdbcTemplate.update(CHUNK_SQL, Timestamp.from(periodStart), Timestamp.from(now));
    }
}
