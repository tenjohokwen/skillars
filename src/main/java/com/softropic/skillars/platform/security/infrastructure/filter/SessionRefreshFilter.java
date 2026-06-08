package com.softropic.skillars.platform.security.infrastructure.filter;

import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;


/**
 * This filter simply acts as an endpoint for requests sent solely to keep the user's session alive. (keep session alive endpoint)
 * It should appear in the filter chain immediately after the AuthorizationFilter since the latter refreshes the session
 * Any request to the endpoint mapped by this filter is returned with an HTTP status code of 200 when it hits this filter.
 * This filter does not chain requests meant for it to any filter further down the chain. It does so for other requests.
 * It is expected that ajax calls will be made in the background to keep user's session alive so long as the user is active within a configurable time
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
