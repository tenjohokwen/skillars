package com.softropic.skillars.platform.security.service;

import com.softropic.skillars.platform.security.repo.LoginAttemptRepository;
import com.softropic.skillars.platform.security.repo.RefreshTokenRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
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
     */
    @Scheduled(cron = "0 0 * * * *")
    @Transactional
    @SchedulerLock(name = "AuthCleanupService_purgeExpiredRefreshTokens",
                   lockAtMostFor = "PT10M", lockAtLeastFor = "PT1M")
    public void purgeExpiredRefreshTokens() {
        refreshTokenRepository.deleteExpiredTokens();
        log.debug("Purged expired refresh tokens");
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
