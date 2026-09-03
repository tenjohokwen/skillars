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
    private final SluSnapshotOutboxSupport sluSnapshotOutboxSupport;

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
        recover(ex, stats, isoYear, isoWeek);
    }

    // Reached only after a retryFor-matched TransactionSystemException / CannotCreateTransactionException
    // exhausts its attempts. Kept separate from the DataAccessException overload (unambiguous siblings).
    @Recover
    public void recoverSnapshotWriteFailure(TransactionException ex, List<PlayerSkillStat> stats,
                                            short isoYear, short isoWeek) {
        recover(ex, stats, isoYear, isoWeek);
    }

    /**
     * skillars-deferred-91 AC4: the retries are exhausted, so {@code player_slu_weekly_snapshot}
     * under-reports for this {@code (session, iso-week)} bucket. Enqueue the write onto the durable
     * outbox for an off-request-path re-drive (idempotent through the {@code V119} marker), and keep
     * the ERROR so the failure is still visible until the re-drive clears it.
     */
    private void recover(Exception ex, List<PlayerSkillStat> stats, short isoYear, short isoWeek) {
        log.error("SLU weekly snapshot write exhausted its retries — {} delta(s) not yet applied for "
            + "{}-W{}; attempting to enqueue on the outbox for re-drive", stats.size(), isoYear, isoWeek, ex);
        // skillars-deferred-91 code review: this @Recover is reached precisely when the database is
        // unreachable or refusing writes (its retryFor set is DataAccessException /
        // TransactionSystemException / CannotCreateTransactionException). enqueueFailedSnapshotWrite
        // is @Transactional(REQUIRES_NEW) and needs a connection from the same pool against the same
        // database, so it can fail for the very reason we got here — and it catches only
        // JsonProcessingException. Previously the log claimed a re-drive had been scheduled BEFORE
        // attempting it, and any DB failure escaped @Recover into the sluRetryExecutor's
        // AsyncUncaughtExceptionHandler, changing a terminal-log contract into a throwing one.
        try {
            sluSnapshotOutboxSupport.enqueueFailedSnapshotWrite(stats, isoYear, isoWeek);
        } catch (RuntimeException enqueueFailure) {
            log.error("[SLU_SNAPSHOT_OUTBOX_ENQUEUE_FAILED] could not enqueue {} delta(s) for {}-W{} — "
                    + "the snapshot under-reports for this bucket and there is no pending re-drive",
                stats.size(), isoYear, isoWeek, enqueueFailure);
        }
    }
}
