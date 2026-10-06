package com.softropic.skillars.platform.security.service;

import com.softropic.skillars.infrastructure.security.SecurityConstants;
import com.softropic.skillars.platform.config.service.ConfigService;
import com.softropic.skillars.platform.security.repo.LoginAttemptRepository;
import com.softropic.skillars.platform.security.repo.RefreshToken;
import com.softropic.skillars.platform.security.repo.RefreshTokenRepository;
import com.softropic.skillars.platform.security.repo.UserRepository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Instant;
import java.util.Optional;

import jakarta.servlet.http.Cookie;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * skillars-deferred-144 AC6.2: {@code AuthService.refresh()}'s two reuse-detection branches
 * (token-reuse-within-grace-window-but-no-live-successor, and reuse-outside-the-grace-window)
 * must call {@code securityUtil.clearAuthCookies(res)} instead of
 * {@code securityUtil.terminateSession(req, res)} — {@code refreshTokenRepository
 * .markAllUsedByUserId} has already revoked every token for the user immediately before either
 * call, so {@code terminateSession}'s own per-token DB write would be redundant. First test class
 * for {@link AuthService}, scoped narrowly to this one behavior change rather than the whole
 * class.
 */
class AuthServiceTest {

    private static final long USER_ID = 42L;
    private static final String RAW_REFRESH_TOKEN = "reused-raw-refresh-token-value";

    @Mock private UserRepository userRepository;
    @Mock private PasswordEncoder passwordEncoder;
    @Mock private RefreshTokenRepository refreshTokenRepository;
    @Mock private LoginAttemptRepository loginAttemptRepository;
    @Mock private ConfigService configService;
    @Mock private LoginTokenManager loginTokenManager;
    @Mock private SecurityUtil securityUtil;

    private AuthService authService;
    private MockHttpServletRequest request;
    private MockHttpServletResponse response;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        authService = new AuthService(userRepository, passwordEncoder, refreshTokenRepository,
            loginAttemptRepository, configService, loginTokenManager, securityUtil);
        request = new MockHttpServletRequest();
        request.setCookies(new Cookie(SecurityConstants.REFRESH_TOKEN_COOKIE, RAW_REFRESH_TOKEN));
        response = new MockHttpServletResponse();
    }

    @Test
    void refresh_reuseWithinGraceWindowButNoLiveSuccessor_clearsCookiesOnlyNotTerminateSession() {
        RefreshToken usedToken = new RefreshToken();
        usedToken.setUserId(USER_ID);
        usedToken.setUsed(true);
        usedToken.setRotatedAt(Instant.now());
        usedToken.setExpiresAt(Instant.now().plusSeconds(3600));
        when(refreshTokenRepository.findByTokenHash(any())).thenReturn(Optional.of(usedToken));
        when(refreshTokenRepository
                .findFirstByUserIdAndUsedFalseAndExpiresAtAfterOrderByCreatedAtDesc(eq(USER_ID), any()))
            .thenReturn(Optional.empty());

        assertThatThrownBy(() -> authService.refresh(request, response))
            .isInstanceOf(BadCredentialsException.class);

        verify(refreshTokenRepository).markAllUsedByUserId(USER_ID);
        verify(securityUtil).clearAuthCookies(response);
        verify(securityUtil, never()).terminateSession(any(), any());
        // Code review 2026-10-06: without this, the test holds identically if the grace-window
        // branch itself were deleted entirely — it must prove THIS branch, specifically, ran.
        verify(refreshTokenRepository)
            .findFirstByUserIdAndUsedFalseAndExpiresAtAfterOrderByCreatedAtDesc(eq(USER_ID), any());
    }

    @Test
    void refresh_reuseNeverRotated_clearsCookiesOnlyNotTerminateSession() {
        RefreshToken usedToken = new RefreshToken();
        usedToken.setUserId(USER_ID);
        usedToken.setUsed(true);
        usedToken.setRotatedAt(null); // never rotated — outside the grace-window branch
        usedToken.setExpiresAt(Instant.now().plusSeconds(3600));
        when(refreshTokenRepository.findByTokenHash(any())).thenReturn(Optional.of(usedToken));

        assertThatThrownBy(() -> authService.refresh(request, response))
            .isInstanceOf(BadCredentialsException.class);

        verify(refreshTokenRepository).markAllUsedByUserId(USER_ID);
        verify(securityUtil).clearAuthCookies(response);
        verify(securityUtil, never()).terminateSession(any(), any());
        verify(refreshTokenRepository, never())
            .findFirstByUserIdAndUsedFalseAndExpiresAtAfterOrderByCreatedAtDesc(anyLong(), any());
    }

    @Test
    void refresh_reuseRotatedLongAgo_clearsCookiesOnlyNotTerminateSession() {
        // Code review 2026-10-06: the sibling test above only covers the degenerate rotatedAt =
        // null case. A real past Instant well outside REFRESH_GRACE_WINDOW (30 s) must take the
        // same else branch — this is the one test that actually exercises the
        // `isBefore(rotatedAt.plus(REFRESH_GRACE_WINDOW))` comparison against a real value.
        RefreshToken usedToken = new RefreshToken();
        usedToken.setUserId(USER_ID);
        usedToken.setUsed(true);
        usedToken.setRotatedAt(Instant.now().minusSeconds(3600));
        usedToken.setExpiresAt(Instant.now().plusSeconds(3600));
        when(refreshTokenRepository.findByTokenHash(any())).thenReturn(Optional.of(usedToken));

        assertThatThrownBy(() -> authService.refresh(request, response))
            .isInstanceOf(BadCredentialsException.class);

        verify(refreshTokenRepository).markAllUsedByUserId(USER_ID);
        verify(securityUtil).clearAuthCookies(response);
        verify(securityUtil, never()).terminateSession(any(), any());
        verify(refreshTokenRepository, never())
            .findFirstByUserIdAndUsedFalseAndExpiresAtAfterOrderByCreatedAtDesc(anyLong(), any());
    }
}
