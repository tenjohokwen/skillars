package com.softropic.skillars.platform.security.service;

import com.google.common.testing.FakeTicker;

import com.softropic.skillars.infrastructure.security.RequestMetadataProvider;
import com.softropic.skillars.infrastructure.security.TestRequestMetadataProvider;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The legacy {@code /authenticate} login decision now delegates failed-attempt counting to the
 * DB-backed {@link LoginRateLimiter} (shared with {@code /api/auth/login}) instead of per-instance
 * Guava caches. Only the in-memory client blacklist stays local.
 */
class LoginAttemptsServiceTest {

    private static final String USERNAME  = "defaultUser@example.com";
    private static final String CLIENT_ID = "defaultClient";
    private static final String IP        = "127.0.0.1";

    private LoginRateLimiter loginRateLimiter;
    private FakeTicker ticker;
    private LoginAttemptsService service;

    @BeforeEach
    void setUp() {
        loginRateLimiter = mock(LoginRateLimiter.class);
        when(loginRateLimiter.checkLimited(anyString(), any())).thenReturn(Optional.empty());
        ticker = new FakeTicker();
        service = new LoginAttemptsService(new ClientIdAccessDecisionManager(List.of()), loginRateLimiter, ticker);
        reset();
    }

    @AfterEach
    void tearDown() {
        RequestMetadataProvider.cleanup();
    }

    private static void reset() {
        // Drop the whole ThreadLocal first: a stale apiKey leaked by an earlier test on the same
        // Surefire thread would otherwise displace CLIENT_ID as the client identifier.
        RequestMetadataProvider.cleanup();
        TestRequestMetadataProvider.setUserName(USERNAME);
        TestRequestMetadataProvider.setBrowserCookie(CLIENT_ID);
        TestRequestMetadataProvider.setIpAddress(IP);
    }

    @Test
    void loginFailed_recordsTheFailureInTheSharedDbLimiter() {
        service.loginFailed(TestRequestMetadataProvider.getClientInfo());

        verify(loginRateLimiter).recordFailure(USERNAME, IP);
    }

    @Test
    void isAllowed_falseWhenTheSharedLimiterReportsTheLoginIpPairLimited() {
        when(loginRateLimiter.checkLimited(USERNAME, IP))
            .thenReturn(Optional.of(new LoginRateLimiter.Limited(5, 600)));

        assertThat(service.isAllowed(TestRequestMetadataProvider.getClientInfo())).isFalse();
    }

    @Test
    void isAllowed_falseWhenTheAccountIsLimitedAcrossAllIps() {
        // The old per-user cache capped an account whatever the source IP; IP rotation must not
        // reopen the legacy path.
        when(loginRateLimiter.isAccountLimited(USERNAME)).thenReturn(true);

        assertThat(service.isAllowed(TestRequestMetadataProvider.getClientInfo())).isFalse();
    }

    @Test
    void isAllowed_trueWhenTheSharedLimiterAllows() {
        assertThat(service.isAllowed(TestRequestMetadataProvider.getClientInfo())).isTrue();
        verify(loginRateLimiter).checkLimited(USERNAME, IP);
    }

    @Test
    void loginSucceeded_doesNotClearRecordedFailures() {
        // Matches /api/auth/login: a success must not reset the shared (login, ip) counter.
        service.loginSucceeded(TestRequestMetadataProvider.getClientInfo());

        verifyNoInteractions(loginRateLimiter);
    }

    @Test
    void blankUsername_isNeitherRecordedNorChecked() {
        TestRequestMetadataProvider.setUserName("");

        service.loginFailed(TestRequestMetadataProvider.getClientInfo());

        assertThat(service.isAllowed(TestRequestMetadataProvider.getClientInfo())).isTrue();
        verify(loginRateLimiter, never()).recordFailure(any(), any());
        verify(loginRateLimiter, never()).checkLimited(any(), any());
    }

    @Test
    void blacklistedClient_isBlockedUntilUnblacklisted() {
        service.blacklistClient(TestRequestMetadataProvider.getClientInfo());
        assertThat(service.isAllowed(TestRequestMetadataProvider.getClientInfo())).isFalse();

        service.unblacklistClient(CLIENT_ID);
        assertThat(service.isAllowed(TestRequestMetadataProvider.getClientInfo())).isTrue();
    }

    @Test
    void blacklist_expiresAfterThirtyDays() {
        service.blacklistClient(TestRequestMetadataProvider.getClientInfo());

        ticker.advance(30, TimeUnit.DAYS);
        ticker.advance(1, TimeUnit.SECONDS);

        assertThat(service.isAllowed(TestRequestMetadataProvider.getClientInfo())).isTrue();
    }

    @Test
    void resetLoginRecording_clearsTheBlacklist() {
        service.blacklistClient(TestRequestMetadataProvider.getClientInfo());

        service.resetLoginRecording();

        assertThat(service.isAllowed(TestRequestMetadataProvider.getClientInfo())).isTrue();
    }
}
