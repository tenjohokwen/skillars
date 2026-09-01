package com.softropic.skillars.platform.development.service;

import com.softropic.skillars.platform.development.repo.PlayerSkillStat;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Moves the two SLU persistence retriers off the shared {@code @Async} listener pool
 * ({@code taskExecutor}) and onto the dedicated bounded {@code sluRetryExecutor}
 * (skillars-deferred-86 AC3), so a DB hiccup's {@code @Backoff} sleeps can no longer park the
 * listener threads that serve every other player's SLU/snapshot processing.
 *
 * <p><strong>Why a third bean.</strong> {@link SluPersistenceRetrier} and
 * {@link SnapshotPersistenceRetrier} each already exist as standalone {@code @Component}s so their
 * {@code @Retryable} goes through the AOP proxy. {@code @Async} and {@code @Retryable} are kept on
 * <em>separate</em> beans — never stacked on one method, since the advisor nesting order between
 * them is unspecified. {@code SluCalculationService} → this dispatcher → each retrier are all
 * cross-bean calls, so every proxy still fires.
 *
 * <p><strong>One chained method, not two concurrent dispatches.</strong> {@code saveSluWithRetry}
 * and {@code writeAllWithRetry} run sequentially on a single {@code sluRetryExecutor} task. Two
 * separate {@code @Async} methods would run them on two threads simultaneously, both touching the
 * same in-JVM {@code List<PlayerSkillStat>} and the same entity instances ({@code saveAll} mutates
 * each — Hibernate assigns {@code id}, makes it managed — while {@code writeAll} iterates the same
 * list). Chaining keeps today's strict sequential single-threaded access and ordering.
 *
 * <p><strong>The snapshot write is gated on the detail save (skillars-deferred-89 AC2, tightened by
 * its code review).</strong> {@link SluPersistenceRetrier#saveSluWithRetry} returns a
 * {@link SluSaveOutcome} tri-state and this method runs {@code writeAllWithRetry} <em>only</em> on
 * {@link SluSaveOutcome#SAVED}:
 * <ul>
 *   <li>{@link SluSaveOutcome#FAILED} (retries exhausted) — skip the snapshot write and ERROR-log:
 *       a session with zero {@code player_skill_stats} rows must not gain a
 *       {@code player_slu_weekly_snapshot} / V119-marker total the detail queries cannot
 *       reproduce.</li>
 *   <li>{@link SluSaveOutcome#ALREADY_PERSISTED} ({@code existsBySessionId} short-circuit or the V47
 *       concurrent-collision catch) — skip the snapshot write quietly. The delivery that actually
 *       persisted the rows runs the snapshot write for the bucket they belong to; this task's own
 *       {@code isoYear}/{@code isoWeek} is derived from a <em>separate</em> per-invocation
 *       {@code Instant.now()} and can land in a different week bucket, so writing here would add a
 *       delta the winning delivery never marked — the exact over-report AC2 exists to prevent.</li>
 * </ul>
 * This closes the <em>over</em>-report direction only; the reverse (detail rows saved,
 * {@code writeAllWithRetry} exhausts and under-reports) is untouched here —
 * {@code SnapshotPersistenceRetrier} is off-limits for this change and that residual is filed on the
 * ledger, along with the note that {@link SluSaveOutcome#ALREADY_PERSISTED} no longer lets a
 * redelivery opportunistically re-drive a missed snapshot write.
 *
 * <p><strong>Failure path.</strong> Each retrier's {@code @Recover} returns normally on exhausted
 * retries, so this method rarely throws. If it does (a non-{@code retryFor} exception with no
 * matching {@code @Recover}), {@code sluRetryExecutor}'s {@link
 * com.softropic.skillars.infrastructure.threadpool.MdcDecorator} wraps the task's {@code run()} in a
 * {@code try/catch(Exception)} that logs {@code "Exception thrown from detached thread…"} and
 * swallows it — Spring's {@code AsyncUncaughtExceptionHandler} never sees it (pre-existing behaviour
 * for {@code taskExecutor} too, not a regression).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SluPersistenceDispatcher {

    private final SluPersistenceRetrier sluPersistenceRetrier;
    private final SnapshotPersistenceRetrier snapshotPersistenceRetrier;

    @Async("sluRetryExecutor")
    public void dispatchSluPersistence(List<PlayerSkillStat> stats, short isoYear, short isoWeek) {
        SluSaveOutcome outcome = sluPersistenceRetrier.saveSluWithRetry(stats);
        if (outcome == SluSaveOutcome.FAILED) {
            log.error("SLU detail save exhausted its retries for session {} player {} — skipping the "
                    + "weekly-snapshot write so player_slu_weekly_snapshot cannot gain a total the detail "
                    + "rows do not back. Manual recovery of both is needed.",
                stats.isEmpty() ? null : stats.get(0).getSessionId(),
                stats.isEmpty() ? null : stats.get(0).getPlayerId());
            return;
        }
        if (outcome == SluSaveOutcome.ALREADY_PERSISTED) {
            log.info("SLU detail rows for session {} were already persisted by a concurrent delivery — "
                    + "skipping this task's weekly-snapshot write; the delivery that persisted the rows "
                    + "runs it for the bucket they belong to.",
                stats.isEmpty() ? null : stats.get(0).getSessionId());
            return;
        }
        snapshotPersistenceRetrier.writeAllWithRetry(stats, isoYear, isoWeek);
        // Positive end-of-chain signal on the isolated pool. Neither retrier logs on its happy path
        // and the caller's "…dispatched" line fires before any DB work, so without this a clean run
        // and a run whose task threw (and was swallowed by MdcDecorator) look identical in the logs.
        // Emitted only on the SAVED branch — a run that skipped the snapshot write (FAILED or
        // ALREADY_PERSISTED) is not a "finished chain" and says so via the ERROR / INFO above. A
        // preceding @Recover ERROR ("… rows lost … manual recovery needed") from
        // SnapshotPersistenceRetrier still means that leg was lost despite this line.
        log.info("SLU persistence chain finished for session {} player {} ({} skill entries, week {}/{})",
            stats.isEmpty() ? null : stats.get(0).getSessionId(),
            stats.isEmpty() ? null : stats.get(0).getPlayerId(),
            stats.size(), isoYear, isoWeek);
    }
}
