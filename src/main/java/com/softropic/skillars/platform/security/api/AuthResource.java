package com.softropic.skillars.platform.security.api;

import com.softropic.skillars.platform.security.contract.LoginRequest;
import com.softropic.skillars.platform.security.contract.LoginResponse;
import com.softropic.skillars.platform.security.service.AuthService;
import com.softropic.skillars.platform.security.service.LoginTokenManager;

import io.micrometer.observation.annotation.Observed;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Session/login lifecycle endpoints.
 *
 * <p>Note the distinction between the two "refresh" paths in this codebase:
 * <ul>
 *   <li><b>{@code POST /api/auth/refresh}</b> (this class) &mdash; full token rotation. The client
 *       presents its refresh-token cookie, the backend validates it and issues a <i>new</i> access
 *       token + refresh token pair. The response body is a {@link LoginResponse} carrying the new
 *       session state. Use this for login flows and explicit re-authentication.</li>
 *   <li><b>{@code /refresh}</b> (verb-agnostic; frontend uses {@code GET})
 *       ({@link com.softropic.skillars.platform.security.infrastructure.filter.SessionRefreshFilter})
 *       &mdash; a secured no-op that only keeps an <i>already active</i> session alive. The JWT TTL
 *       extension happens in {@code JWTAuthorizationFilter} before the request reaches that filter,
 *       which then returns a plain {@code 200} with no body. Use this (via {@code sessionApi.refresh()})
 *       to keep a session alive without doing other work.</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/auth")
@Observed(name = "security.auth")
@RequiredArgsConstructor
public class AuthResource {

    private final AuthService authService;
    private final LoginTokenManager loginTokenManager;

    @PostMapping("/login")
    @PreAuthorize("permitAll()")
    public ResponseEntity<LoginResponse> login(@RequestBody @Valid LoginRequest req,
                                               HttpServletRequest httpReq,
                                               HttpServletResponse res) {
        loginTokenManager.ensureClientHasPreLoginId();
        return ResponseEntity.ok(authService.login(req.email(), req.password(), httpReq.getRemoteAddr(), res));
    }

    /**
     * Full token rotation: validates the presented refresh-token cookie and issues a brand-new
     * access token + refresh token pair, returning the new session state as a {@link LoginResponse}.
     * <p>
     * This is <b>not</b> the same as {@code /refresh} (see class JavaDoc): that endpoint only
     * extends the TTL of an already-valid session and returns an empty {@code 200}.
     */
    @PostMapping("/refresh")
    @PreAuthorize("permitAll()")
    public ResponseEntity<LoginResponse> refresh(HttpServletRequest req, HttpServletResponse res) {
        return ResponseEntity.ok(authService.refresh(req, res));
    }

    @PostMapping("/logout")
    @PreAuthorize("permitAll()")
    public ResponseEntity<Void> logout(HttpServletRequest req, HttpServletResponse res) {
        authService.logout(req, res);
        return ResponseEntity.noContent().build();
    }
}
