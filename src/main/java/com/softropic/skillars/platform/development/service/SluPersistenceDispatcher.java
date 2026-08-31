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
 * ({@code saveSluWithRetry}'s {@code @Recover} returns {@code void} on exhausted retries, so
 * {@code writeAllWithRetry} still runs afterward — identical to the two sequential calls this
 * replaced in {@code onBookingCompleted}.)
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
        sluPersistenceRetrier.saveSluWithRetry(stats);
        snapshotPersistenceRetrier.writeAllWithRetry(stats, isoYear, isoWeek);
        // Positive end-of-chain signal on the isolated pool. Neither retrier logs on its happy path
        // and the caller's "…dispatched" line fires before any DB work, so without this a clean run
        // and a run whose task threw (and was swallowed by MdcDecorator) look identical in the logs.
        // This says "both legs returned" — a preceding @Recover ERROR ("… rows lost … manual
        // recovery needed") from either retrier still means that leg was lost despite this line.
        log.info("SLU persistence chain finished for session {} player {} ({} skill entries, week {}/{})",
            stats.isEmpty() ? null : stats.get(0).getSessionId(),
            stats.isEmpty() ? null : stats.get(0).getPlayerId(),
            stats.size(), isoYear, isoWeek);
    }
}
