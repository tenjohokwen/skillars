package com.softropic.skillars.platform.security.service;

import com.softropic.skillars.platform.config.service.ConfigService;
import com.softropic.skillars.platform.security.repo.LoginAttempt;
import com.softropic.skillars.platform.security.repo.LoginAttemptRepository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
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
        when(repository.countByIdentifierAndAttemptedAtAfter(anyString(), any())).thenReturn(4L);

        assertThat(limiter.checkLimited("a@b.com", "1.2.3.4")).isEmpty();
    }

    @Test
    void retryAfter_isMeasuredFromTheOldestAttemptInsideTheWindow() {
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
    void accountLimited_countsEveryIp() {
        when(repository.countBySubjectHashAndAttemptedAtAfter(anyString(), any())).thenReturn(5L);

        assertThat(limiter.isAccountLimited("a@b.com")).isTrue();
    }
}
