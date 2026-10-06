package com.softropic.skillars.platform.security.service;

import com.softropic.skillars.infrastructure.security.SecurityConstants;
import com.softropic.skillars.platform.security.repo.RefreshTokenRepository;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;

import jakarta.servlet.http.Cookie;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * skillars-deferred-143: first test class for {@link SecurityUtil}, scoped to
 * {@link SecurityUtil#terminateSession} — the one method this story adds.
 *
 * <p>Built on {@code MockHttpServletRequest}/{@code MockHttpServletResponse} rather than mocked
 * servlet interfaces so the cookie assertions read the real emitted {@code Set-Cookie} headers
 * instead of merely confirming that a setter was called on a mock.
 */
class SecurityUtilTest {

    private static final String SET_COOKIE = "Set-Cookie";
    private static final String RAW_REFRESH_TOKEN = "a-raw-refresh-token-value";

    @Mock
    private LoginTokenManager loginTokenManager;

    @Mock
    private RefreshTokenRepository refreshTokenRepository;

    private SecurityUtil securityUtil;
    private MockHttpServletRequest request;
    private MockHttpServletResponse response;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        securityUtil = new SecurityUtil(loginTokenManager, refreshTokenRepository);
        request = new MockHttpServletRequest();
        response = new MockHttpServletResponse();
        SecurityContextHolder.clearContext();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("terminateSession revokes the presented refresh token by its SHA-256 hash")
    void terminateSession_revokesPresentedRefreshTokenByHash() {
        request.setCookies(new Cookie(SecurityConstants.REFRESH_TOKEN_COOKIE, RAW_REFRESH_TOKEN));

        securityUtil.terminateSession(request, response);

        // Keyed by hash, never by the raw cookie value — refresh_tokens stores only the hash.
        verify(refreshTokenRepository).markUsedByTokenHash(sha256Hex(RAW_REFRESH_TOKEN));
    }

    @Test
    @DisplayName("terminateSession clears rtkn and skp on top of the cookies deleteLoginToken handles")
    void terminateSession_clearsRefreshTokenAndProfileCookies() {
        request.setCookies(new Cookie(SecurityConstants.REFRESH_TOKEN_COOKIE, RAW_REFRESH_TOKEN));

        securityUtil.terminateSession(request, response);

        // deleteLoginToken owns potc/bcookie/user/admin/ION/rint/skp (skillars-deferred-144 AC2
        // added skp). loginTokenManager is mocked here, so calling the mock's deleteLoginToken
        // emits no real header — rtkn and skp below are asserted against clearAuthCookies' own
        // direct removal calls, not anything the mock would have produced.
        verify(loginTokenManager).deleteLoginToken(response);

        List<String> setCookies = response.getHeaders(SET_COOKIE);
        assertThat(setCookies)
                .as("rtkn must be expired")
                .anyMatch(c -> c.startsWith(SecurityConstants.REFRESH_TOKEN_COOKIE + "=")
                        && c.contains("Max-Age=0"));
        assertThat(setCookies)
                .as("skp must be expired")
                .anyMatch(c -> c.startsWith(SecurityConstants.SKILLARS_PROFILE_COOKIE + "=")
                        && c.contains("Max-Age=0"));
    }

    @Test
    @DisplayName("terminateSession issues no refresh-token write when no rtkn cookie is present")
    void terminateSession_withoutRefreshTokenCookie_issuesNoDatabaseWrite() {
        // The filter's forced-logout path runs on an unauthenticated, unrate-limited URL, so a
        // request carrying no rtkn must not cost a DB write — otherwise this becomes the
        // write-amplifier that skillars-deferred-90 AC5/F22 exists to prevent.
        securityUtil.terminateSession(request, response);

        verify(refreshTokenRepository, never()).markUsedByTokenHash(anyString());
        verify(loginTokenManager).deleteLoginToken(response);
    }

    @Test
    @DisplayName("terminateSession treats a blank rtkn cookie as absent")
    void terminateSession_blankRefreshTokenCookie_issuesNoDatabaseWrite() {
        request.setCookies(new Cookie(SecurityConstants.REFRESH_TOKEN_COOKIE, "   "));

        securityUtil.terminateSession(request, response);

        verify(refreshTokenRepository, never()).markUsedByTokenHash(anyString());
    }

    @Test
    @DisplayName("terminateSession clears the SecurityContext")
    void terminateSession_clearsSecurityContext() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("someone@skillars.com", null, List.of()));
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNotNull();

        securityUtil.terminateSession(request, response);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    @DisplayName("AC3: SecurityUtil.logout(HttpServletResponse) no longer exists")
    void securityUtil_noLongerExposesLogout() {
        // Asserted reflectively rather than left to the compiler: the AC is that no caller can
        // reach the old partial teardown, and a re-added overload later would silently reopen
        // exactly the gap this story closed.
        assertThat(Arrays.stream(SecurityUtil.class.getDeclaredMethods()).map(Method::getName))
                .doesNotContain("logout");
    }

    private static String sha256Hex(String raw) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                                       .digest(raw.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
