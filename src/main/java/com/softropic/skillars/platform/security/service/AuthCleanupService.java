package com.softropic.skillars.platform.security.service;

import com.softropic.skillars.platform.security.repo.LoginAttemptRepository;
import com.softropic.skillars.platform.security.repo.RefreshTokenRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

@Slf4j
@Component
@RequiredArgsConstructor
public class AuthCleanupService {

    private final RefreshTokenRepository refreshTokenRepository;
    private final LoginAttemptRepository loginAttemptRepository;

    /**
     * skillars-deferred-120 AC3 Finding 2: consistency, not a live bug — a single-statement bulk
     * delete is naturally idempotent (a second concurrent run just deletes zero rows), the same
     * reasoning {@code skillars-deferred-118} AC3 already applied to {@code
     * MessageRetentionScheduler}. {@code lockAtMostFor="PT10M"} is sized independently for this
     * job's own bulk {@code DELETE} against the refresh-token table — <strong>not</strong> a copy of
     * {@code MessageRetentionScheduler}'s {@code PT30M} (code review 2026-09-17, Patch #3: an earlier
     * draft of this Javadoc falsely claimed the two were "identical bulk-delete sizing"); {@code
     * PT10M} gives real margin above a single bulk statement's realistic runtime without borrowing a
     * sibling's larger, differently-justified value. {@code lockAtLeastFor} is re-derived from this
     * job's own hourly cron rather than copying the {@code PT2M} used by 5-minute-{@code fixedDelay}
     * siblings — {@code PT1M} is a defensive floor against a pathological fast-fail-refire, well
     * clear of the hourly cadence.
     *
     * <p><strong>skillars-deferred-167 (AC9): deliberately NOT {@code @Transactional}, and the catch is
     * why.</strong> This method used to carry a plain {@code @Transactional}, which made a lost deadlock impossible to
     * contain here: {@code deleteExpiredTokens} is itself {@code @Modifying @Transactional}, so it joined
     * this method's transaction, and a {@code DELETE} killed by PostgreSQL (SQLSTATE 40P01) marked that
     * shared transaction rollback-only. A {@code try/catch} in this body would then still have failed at
     * the outer commit with {@code UnexpectedRollbackException} — it would have changed the exception, not
     * contained it — and PostgreSQL has in any case already aborted the transaction, so nothing further
     * could have run inside it. With no transaction here, the repository method is its own transaction
     * boundary: it rolls itself back and throws, and this catch runs with no transaction to poison.
     *
     * <p>{@code @SchedulerLock} must stay outermost, which it does — ShedLock's advisor is ordered ahead of
     * the transaction advisor application-wide ({@code AsyncConfig}), and
     * {@code SchedulerLockTransactionOrderingIT} asserts the lock survives on this method even though the
     * transaction no longer does. Dropping the lock would let a second node sweep concurrently.
     *
     * <p>A lost deadlock costs this run and nothing more: the next hourly tick deletes the same rows, and
     * the sweep is a single idempotent statement. Logged at {@code warn} rather than swallowed, because a
     * recurring one means the ordering below stopped holding.
     */
    @Scheduled(cron = "0 0 * * * *")
    @SchedulerLock(name = "AuthCleanupService_purgeExpiredRefreshTokens",
                   lockAtMostFor = "PT10M", lockAtLeastFor = "PT1M")
    public void purgeExpiredRefreshTokens() {
        try {
            refreshTokenRepository.deleteExpiredTokens();
            log.debug("Purged expired refresh tokens");
        } catch (PessimisticLockingFailureException e) {
            // 40P01 (deadlock victim) and 55P03 (lock not available) both land here. The writer that can
            // still cycle with this sweep is a bulk revoke-all over one user's rows (markAllUsedByUserId /
            // markAllRevokedByUserId), which is unordered by nature. The chain walks are ordered the same
            // way this sweep now is, so they should not — but see deleteExpiredTokens' javadoc for the one
            // residual (the ON DELETE SET NULL action can touch an unexpired predecessor outside the
            // ordered set), which is unreachable in production rather than impossible by construction.
            log.warn("Expired-refresh-token sweep lost a lock contest and skipped this run; "
                + "the next hourly run retries the same rows", e);
        }
    }

    // Retain 24 hours of attempts so rate-limit windows (15 min) have margin;
    // keeps the table small rather than growing unboundedly.
    /**
     * skillars-deferred-120 AC3 Finding 2: same consistency reasoning as {@link
     * #purgeExpiredRefreshTokens} — a genuine single-statement bulk delete, naturally idempotent.
     * {@code LoginAttemptRepository.deleteByAttemptedAtBefore} was a <em>derived</em> delete method
     * until code review 2026-09-17 (Decision 2) converted it to a bulk {@code @Query DELETE} —
     * before that fix, Spring Data JPA would have executed it as a {@code SELECT} of every matching
     * row into the persistence context followed by one {@code DELETE} per entity, and this Javadoc's
     * "single-statement bulk delete" premise (and the idempotency reasoning built on it) would have
     * been false. {@code lockAtMostFor="PT5M"} is smaller than its hourly sibling's: the
     * login-attempts table is bounded to a rolling 24-hour retention window, so its bulk delete is
     * cheaper. {@code lockAtLeastFor="PT1M"} leaves real margin below this job's own 30-minute
     * cadence.
     */
    @Scheduled(cron = "0 */30 * * * *")
    @Transactional
    @SchedulerLock(name = "AuthCleanupService_purgeOldLoginAttempts",
                   lockAtMostFor = "PT5M", lockAtLeastFor = "PT1M")
    public void purgeOldLoginAttempts() {
        loginAttemptRepository.deleteByAttemptedAtBefore(Instant.now().minus(24, ChronoUnit.HOURS));
        log.debug("Purged stale login attempts");
    }
}
