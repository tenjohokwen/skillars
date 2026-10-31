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
    void aBriefOverlapOnTheSameKey_waitsForTheLockInsteadOfRefusing() {
        when(repository.tryAcquireIdentifierLock(anyString())).thenReturn(false, false, true);
        when(repository.countByIdentifierAndAttemptedAtAfter(anyString(), any())).thenReturn(0L);

        assertThat(limiter.checkLimited("a@b.com", "1.2.3.4")).isEmpty();
        verify(repository, times(3)).tryAcquireIdentifierLock(anyString());
    }

    @Test
    void aKeyBusyForTheWholeBoundedWait_isLimited() {
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

    @Test
    void perAccountBudgetSpentAcrossIps_isLimitedEvenWhenThisIpIsUnderItsOwnBudget() {
        when(repository.tryAcquireIdentifierLock(anyString())).thenReturn(true);
        when(repository.countByIdentifierAndAttemptedAtAfter(anyString(), any())).thenReturn(1L);
        when(repository.countBySubjectHashAndAttemptedAtAfter(anyString(), any())).thenReturn(20L);
        LoginAttempt inWindow = new LoginAttempt("id", "subject", Instant.now().minus(5, ChronoUnit.MINUTES));
        when(repository.findFirstBySubjectHashAndAttemptedAtAfterOrderByAttemptedAtAsc(anyString(), any()))
            .thenReturn(Optional.of(inWindow));

        LoginRateLimiter.Limited limited = limiter.checkLimited("a@b.com", "1.2.3.4").orElseThrow();

        assertThat(limited.recentAttempts()).isEqualTo(20L);
        assertThat(limited.retryAfterSeconds()).isBetween(590L, 600L);
    }

    @Test
    void perAccountBudgetNotSpent_isNotLimited() {
        when(repository.tryAcquireIdentifierLock(anyString())).thenReturn(true);
        when(repository.countByIdentifierAndAttemptedAtAfter(anyString(), any())).thenReturn(1L);
        when(repository.countBySubjectHashAndAttemptedAtAfter(anyString(), any())).thenReturn(19L);

        assertThat(limiter.checkLimited("a@b.com", "1.2.3.4")).isEmpty();
    }

    @Test
    void ipv6AddressesInTheSame64_shareOnePerIpKey_andOtherPrefixesDoNot() {
        when(repository.tryAcquireIdentifierLock(anyString())).thenReturn(true);
        ArgumentCaptor<String> id = ArgumentCaptor.forClass(String.class);

        limiter.checkLimited("a@b.com", "2001:db8:1:2::1");
        limiter.checkLimited("a@b.com", "2001:db8:1:2:ffff:ffff:ffff:ffff");
        limiter.checkLimited("a@b.com", "2001:db8:1:3::1");

        verify(repository, times(3)).countByIdentifierAndAttemptedAtAfter(id.capture(), any());
        assertThat(id.getAllValues().get(0)).isEqualTo(id.getAllValues().get(1));
        assertThat(id.getAllValues().get(0)).isNotEqualTo(id.getAllValues().get(2));
    }
}
