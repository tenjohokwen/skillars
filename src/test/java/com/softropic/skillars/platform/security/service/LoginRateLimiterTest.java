package com.softropic.skillars.platform.security.service;

import com.softropic.skillars.platform.config.service.ConfigService;
import com.softropic.skillars.platform.security.repo.LoginAttempt;
import com.softropic.skillars.platform.security.repo.LoginAttemptRepository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class LoginRateLimiterTest {

    private LoginAttemptRepository repository;
    private LoginRateLimiter limiter;

    @BeforeEach
    void setUp() {
        repository = mock(LoginAttemptRepository.class);
        ConfigService configService = mock(ConfigService.class);
        when(configService.find(anyString())).thenReturn(Optional.empty()); // defaults: 5 attempts / 15 min
        limiter = new LoginRateLimiter(repository, configService);
    }

    @Test
    void underBudget_isNotLimited() {
        when(repository.tryAcquireIdentifierLock(anyString())).thenReturn(true);
        when(repository.countByIdentifierAndAttemptedAtAfter(anyString(), any())).thenReturn(4L);

        assertThat(limiter.checkLimited("a@b.com", "1.2.3.4")).isEmpty();
    }

    @Test
    void retryAfter_isMeasuredFromTheOldestAttemptInsideTheWindow() {
        when(repository.tryAcquireIdentifierLock(anyString())).thenReturn(true);
        // Rows live 24h; an older row outside the window must not shrink Retry-After to 1s.
        when(repository.countByIdentifierAndAttemptedAtAfter(anyString(), any())).thenReturn(5L);
        LoginAttempt inWindow = new LoginAttempt("id", Instant.now().minus(5, ChronoUnit.MINUTES));
        when(repository.findFirstByIdentifierAndAttemptedAtAfterOrderByAttemptedAtAsc(anyString(), any()))
            .thenReturn(Optional.of(inWindow));

        LoginRateLimiter.Limited limited = limiter.checkLimited("a@b.com", "1.2.3.4").orElseThrow();

        assertThat(limited.recentAttempts()).isEqualTo(5L);
        assertThat(limited.retryAfterSeconds()).isBetween(590L, 600L);
    }

    @Test
    void checkLimited_takesTheIdentifierLockBeforeCounting() {
        when(repository.tryAcquireIdentifierLock(anyString())).thenReturn(true);
        when(repository.countByIdentifierAndAttemptedAtAfter(anyString(), any())).thenReturn(0L);

        limiter.checkLimited("A@B.com", "::ffff:1.2.3.4");

        // The lock precedes the count it protects, on the same key.
        InOrder inOrder = inOrder(repository);
        ArgumentCaptor<String> key = ArgumentCaptor.forClass(String.class);
        inOrder.verify(repository).tryAcquireIdentifierLock(key.capture());
        inOrder.verify(repository).countByIdentifierAndAttemptedAtAfter(eq(key.getValue()), any());

        // Same canonical (login, ip) -> same lock key.
        limiter.checkLimited("a@b.com", "1.2.3.4");
        verify(repository, times(2)).tryAcquireIdentifierLock(key.getValue());
    }

    @Test
    void anotherAttemptInFlightForTheSameKey_isLimitedWithoutWaiting() {
        when(repository.tryAcquireIdentifierLock(anyString())).thenReturn(false);
        when(repository.countByIdentifierAndAttemptedAtAfter(anyString(), any())).thenReturn(1L);

        LoginRateLimiter.Limited limited = limiter.checkLimited("a@b.com", "1.2.3.4").orElseThrow();

        assertThat(limited.recentAttempts()).isEqualTo(1L);
        assertThat(limited.retryAfterSeconds()).isEqualTo(LoginRateLimiter.CONCURRENT_ATTEMPT_RETRY_AFTER_SECONDS);
    }

    @Test
    void recentFailures_countEveryIp() {
        when(repository.countBySubjectHashAndAttemptedAtAfter(anyString(), any())).thenReturn(5L);

        assertThat(limiter.countRecentFailures("a@b.com")).isEqualTo(5L);
    }
}
