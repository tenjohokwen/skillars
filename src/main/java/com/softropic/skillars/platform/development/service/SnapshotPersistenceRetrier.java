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
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.TransactionSystemException;

import java.util.List;

/**
 * Split into its own bean so {@code @Retryable} goes through the Spring AOP proxy — a call from
 * SluCalculationService.onBookingCompleted via {@code this.writeAllWithRetry(...)} would bypass the
 * proxy entirely and the retry would silently never fire (same self-invocation pitfall documented on
 * SluPersistenceRetrier, BookingService.acceptAndInitiatePayment and TimelineEventListener's
 * {@code @Lazy @Autowired} self).
 *
 * <p>A whole-method retry of {@link SnapshotBatchWriter#writeAll} is safe <strong>because
 * {@code writeAll} is idempotent per {@code (session_id, weekly-bucket)} marker</strong>
 * (skillars-deferred-86 AC1), not merely because a rolled-back transaction leaves a clean slate.
 * That distinction matters for {@link TransactionSystemException}: it is raised on a commit-phase
 * system error, which <em>includes</em> the ambiguous case where PostgreSQL committed server-side
 * but the client lost the ack — the transaction did <em>not</em> roll back, and before AC1 the
 * retry's additive upserts double-counted. Now the first attempt's marker rows are present, so
 * {@code upsertAddIdempotent}'s {@code ON CONFLICT DO NOTHING} makes every re-applied delta a no-op.
 * {@link CannotCreateTransactionException} (begin failed → nothing ran) is trivially safe to retry.
 * All three are retried and structured-recovered alongside {@link DataAccessException};
 * transaction-<em>usage</em> programming errors are deliberately excluded from {@code retryFor}.
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
        retryFor = {DataAccessException.class, TransactionSystemException.class, CannotCreateTransactionException.class},
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

    // Reached only after a retryFor-matched TransactionSystemException / CannotCreateTransactionException
    // exhausts its attempts. Kept separate from the DataAccessException overload (unambiguous siblings).
    @Recover
    public void recoverSnapshotWriteFailure(TransactionException ex, List<PlayerSkillStat> stats,
                                            short isoYear, short isoWeek) {
        log.error("Failed to write SLU weekly snapshot after retries — {} rows lost for {}-W{}, manual recovery needed",
            stats.size(), isoYear, isoWeek, ex);
    }
}
