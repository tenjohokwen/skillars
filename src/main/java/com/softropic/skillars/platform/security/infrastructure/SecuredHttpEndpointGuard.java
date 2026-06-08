package com.softropic.skillars.platform.security.infrastructure;

import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.util.StringUtils;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import jakarta.servlet.http.HttpServletRequest;


public class SecuredHttpEndpointGuard {

    private final Map<String, List<SimpleGrantedAuthority>> endpointToSimpleGrantedAuthorities;
    private final List<PathPatternRequestMatcher>               requestMatchers;
    private final List<PathPatternRequestMatcher> unrestrictedEndpointMatchers;

    public SecuredHttpEndpointGuard(final Map<String, String[]> securedMappings,
                                    final List<String> allUnrestricted) {
        endpointToSimpleGrantedAuthorities = toSimpleGrantedAuthoritiesMap(securedMappings);
        requestMatchers = securedMappings.keySet()
                                         .stream()
                                         .map(pattern -> PathPatternRequestMatcher.withDefaults().matcher(pattern))
                                         .toList();
        unrestrictedEndpointMatchers = allUnrestricted.stream()
                                                      .map(pattern -> PathPatternRequestMatcher.withDefaults().matcher(pattern))
                                                      .toList();
    }

    public boolean isSecured(final HttpServletRequest request) {
        return requestMatchers.stream().anyMatch(requestMatcher -> requestMatcher.matches(request));
    }

    public boolean isUnrestricted(final HttpServletRequest request) {
        return unrestrictedEndpointMatchers.stream().anyMatch(requestMatcher -> requestMatcher.matches(request));
    }

    public List<SimpleGrantedAuthority> requiredAuthorities(final HttpServletRequest request) {
        return endpointToSimpleGrantedAuthorities.get(getRequestPath(request));
    }

    private Map<String, List<SimpleGrantedAuthority>> toSimpleGrantedAuthoritiesMap(final Map<String, String[]> endpointToAuthorities) {
        return endpointToAuthorities.keySet()
                                    .stream()
                                    .collect(Collectors.toMap(s->s, s -> toSimpleGrantedAuthorities(endpointToAuthorities.get(s))));
    }

    private List<SimpleGrantedAuthority> toSimpleGrantedAuthorities(final String...authorities) {
        return Arrays.stream(authorities).map(SimpleGrantedAuthority::new).toList();
    }


    private String getRequestPath(final HttpServletRequest request) {
        String url = request.getServletPath();
        final String pathInfo = request.getPathInfo();
        if (pathInfo != null) {
            url = StringUtils.hasLength(url) ? url + pathInfo : pathInfo;
        }
        return url;
    }
}
