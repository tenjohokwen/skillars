package com.softropic.skillars.platform.security.infrastructure.filter;

import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;


/**
 * Terminal handler for the <b>{@code /refresh} keep-session-alive endpoint</b>.
 * The {@code PathPatternRequestMatcher} binds no HTTP method, so <i>any</i> verb
 * ({@code GET}, {@code POST}, &hellip;) to {@code /refresh} short-circuits here identically;
 * the frontend happens to call it with {@code GET}.
 *
 * <h2>Purpose</h2>
 * A secured no-op endpoint whose only job is to trigger the JWT TTL extension that
 * {@link com.softropic.skillars.platform.security.infrastructure.jwt.filter.JWTAuthorizationFilter}
 * performs on every authenticated request. This filter itself does <b>not</b> refresh anything &mdash;
 * by the time a {@code /refresh} request reaches it, the JWT / {@code user} / {@code rint} cookies
 * have already been re-issued upstream. It simply short-circuits the request with a plain
 * {@code 200 OK} (empty body) and does not chain it any further down the filter chain. All other
 * requests pass straight through untouched.
 *
 * <h2>When to use it</h2>
 * Call it (frontend: {@code sessionApi.refresh()} &rarr; {@code GET /refresh}, though the verb is not enforced) from an active session
 * when you want to keep the session alive without doing other work &mdash; e.g. when the user clicks
 * "Continue session" in {@code SessionWarningDialog.vue}. For login flows and explicit token rotation
 * (new access + refresh token pair) use {@code POST /api/auth/refresh}
 * ({@link com.softropic.skillars.platform.security.api.AuthResource}) instead.
 *
 * <h2>Filter ordering</h2>
 * Must sit immediately after {@code JWTAuthorizationFilter} in the chain so the token is already
 * extended when the request arrives here. Wired in
 * {@code SecurityConfiguration#filterChain} via
 * {@code addFilterAfter(new SessionRefreshFilter(AppEndpoints.REFRESH), JWTAuthorizationFilter.class)}.
 * {@code /refresh} is registered as a secured endpoint (any authenticated role) in
 * {@code AppEndpoints.SECURED_MAPPINGS}, which is what makes it flow through the authorization filter.
 */
public class SessionRefreshFilter extends OncePerRequestFilter {

    private final RequestMatcher sessionRefreshEndpointMatcher;

    public SessionRefreshFilter(final String endpoint) {
        super();
        sessionRefreshEndpointMatcher = PathPatternRequestMatcher.withDefaults().matcher(endpoint);
    }

    @Override
    protected void doFilterInternal(final HttpServletRequest request,
                                    final HttpServletResponse response,
                                    final FilterChain filterChain) {
        // see the overridden shouldNotFilter method below.
        response.setStatus(200);
    }

    @Override
    protected boolean shouldNotFilter(final HttpServletRequest request) {
        return !sessionRefreshEndpointMatcher.matches(request);
    }
}
