package com.softropic.skillars.platform.development.service;

import com.softropic.skillars.platform.development.repo.PlayerSkillStat;
import com.softropic.skillars.platform.development.repo.SnapshotBatchWriter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Recover;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Split into its own bean so {@code @Retryable} goes through the Spring AOP proxy — a call from
 * SluCalculationService.onBookingCompleted via {@code this.writeAllWithRetry(...)} would bypass the
 * proxy entirely and the retry would silently never fire (same self-invocation pitfall documented on
 * SluPersistenceRetrier, BookingService.acceptAndInitiatePayment and TimelineEventListener's
 * {@code @Lazy @Autowired} self).
 *
 * <p>The wrapped {@link SnapshotBatchWriter#writeAll} is {@code @Transactional} and only issues
 * additive upserts, so a whole-method retry is safe: an uncaught exception mid-loop rolls the
 * transaction back entirely (nothing partially commits) and the retry re-runs against a clean slate.
 *
 * <p>Uses its own {@code app.slu.snapshot-retry.*} property namespace (distinct from
 * SluPersistenceRetrier's {@code app.slu.retry.*}) so the two retriers can be tuned independently.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SnapshotPersistenceRetrier {

    private final SnapshotBatchWriter snapshotBatchWriter;

    @Retryable(
        retryFor = DataAccessException.class,
        maxAttemptsExpression = "${app.slu.snapshot-retry.max-attempts:3}",
        backoff = @Backoff(
            delayExpression = "${app.slu.snapshot-retry.backoff-initial-ms:100}",
            multiplierExpression = "${app.slu.snapshot-retry.backoff-multiplier:2.0}"
        )
    )
    public void writeAllWithRetry(List<PlayerSkillStat> stats, short isoYear, short isoWeek) {
        snapshotBatchWriter.writeAll(stats, isoYear, isoWeek);
    }

    @Recover
    public void recoverSnapshotWriteFailure(DataAccessException ex, List<PlayerSkillStat> stats,
                                            short isoYear, short isoWeek) {
        log.error("Failed to write SLU weekly snapshot after retries — {} rows lost for {}-W{}, manual recovery needed",
            stats.size(), isoYear, isoWeek, ex);
    }
}
