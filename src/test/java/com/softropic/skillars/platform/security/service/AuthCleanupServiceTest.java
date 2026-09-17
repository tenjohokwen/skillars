package com.softropic.skillars.platform.security.service;

import com.softropic.skillars.platform.security.repo.LoginAttemptRepository;
import com.softropic.skillars.platform.security.repo.RefreshTokenRepository;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Method;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;

/**
 * skillars-deferred-120 AC3 Finding 2 — {@code AuthCleanupService} had no test coverage at all before
 * this story ({@code grep -rl AuthCleanupService src/test} was empty). Both methods are
 * single-statement bulk deletes, naturally idempotent under a second concurrent run; the
 * {@code @SchedulerLock} added here is a consistency fix (mirroring {@code MessageRetentionScheduler}'s
 * identical reasoning), not a live-defect fix, so this coverage is deliberately minimal:
 * behavior + lock-presence, not concurrency scenarios.
 */
@ExtendWith(MockitoExtension.class)
class AuthCleanupServiceTest {

    @Mock RefreshTokenRepository refreshTokenRepository;
    @Mock LoginAttemptRepository loginAttemptRepository;

    private AuthCleanupService service;

    @BeforeEach
    void setUp() {
        service = new AuthCleanupService(refreshTokenRepository, loginAttemptRepository);
    }

    @Test
    void purgeExpiredRefreshTokens_delegatesToRepository() {
        service.purgeExpiredRefreshTokens();

        verify(refreshTokenRepository).deleteExpiredTokens();
    }

    @Test
    void purgeOldLoginAttempts_delegatesToRepository() {
        Instant before = Instant.now().minus(24, ChronoUnit.HOURS);

        service.purgeOldLoginAttempts();

        // Pinned to ~24h, not any() (code review 2026-09-17, Patch #13) — the production comment
        // makes the 24-hour retention a correctness property of the 15-minute rate-limit window;
        // any() would also pass for a regression that truncates the whole table (e.g. Instant.MAX).
        ArgumentCaptor<Instant> cutoffCaptor = ArgumentCaptor.forClass(Instant.class);
        verify(loginAttemptRepository).deleteByAttemptedAtBefore(cutoffCaptor.capture());
        Instant after = Instant.now().minus(24, ChronoUnit.HOURS);
        assertThat(cutoffCaptor.getValue()).isBetween(before, after);
    }

    @Test
    void purgeExpiredRefreshTokens_carriesSchedulerLock() throws NoSuchMethodException {
        Method method = AuthCleanupService.class.getMethod("purgeExpiredRefreshTokens");
        SchedulerLock lock = method.getAnnotation(SchedulerLock.class);

        assertThat(lock).as("purgeExpiredRefreshTokens() must carry @SchedulerLock").isNotNull();
        assertThat(lock.name()).isEqualTo("AuthCleanupService_purgeExpiredRefreshTokens");
        // Pinned to the actual sizing values (code review 2026-09-17, Patch #6) — asserting only
        // isPositive() would leave the sizing arithmetic in the method's Javadoc unguarded.
        assertThat(Duration.parse(lock.lockAtMostFor())).isEqualTo(Duration.ofMinutes(10));
        assertThat(Duration.parse(lock.lockAtLeastFor())).isEqualTo(Duration.ofMinutes(1));
    }

    @Test
    void purgeOldLoginAttempts_carriesSchedulerLock() throws NoSuchMethodException {
        Method method = AuthCleanupService.class.getMethod("purgeOldLoginAttempts");
        SchedulerLock lock = method.getAnnotation(SchedulerLock.class);

        assertThat(lock).as("purgeOldLoginAttempts() must carry @SchedulerLock").isNotNull();
        assertThat(lock.name()).isEqualTo("AuthCleanupService_purgeOldLoginAttempts");
        assertThat(Duration.parse(lock.lockAtMostFor())).isEqualTo(Duration.ofMinutes(5));
        assertThat(Duration.parse(lock.lockAtLeastFor())).isEqualTo(Duration.ofMinutes(1));
    }
}
