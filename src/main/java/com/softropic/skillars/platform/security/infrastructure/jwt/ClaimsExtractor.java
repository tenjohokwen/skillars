package com.softropic.skillars.platform.security.infrastructure.jwt;

import com.softropic.skillars.platform.security.contract.Principal;
import io.jsonwebtoken.Claims;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Optional;
import java.util.UUID;

public interface ClaimsExtractor {
    Claims extractClaims(String token);
    Optional<Claims> extractClaimsFromTokenSilently(String token);
    Optional<Claims> extractClaimsSilently(HttpServletRequest request);
    Principal extractPrincipal(HttpServletRequest request);
    Long extractDbRefreshToken(HttpServletRequest request);
    Long extractSessionIssuedAt(HttpServletRequest request);
    UUID extractRefreshSessionId(HttpServletRequest request);

    /*
     * skillars-deferred-179: the same four readers over claims already parsed, so a caller that needs several of them
     * (JwtManagerImpl.extendTtlOfToken) verifies the JWT once instead of once per claim. The request-based readers above
     * parse and delegate to these.
     */
    Principal principalFrom(io.jsonwebtoken.Claims claims);
    Long dbRefreshTokenFrom(io.jsonwebtoken.Claims claims);
    Long sessionIssuedAtFrom(io.jsonwebtoken.Claims claims);
    UUID refreshSessionIdFrom(io.jsonwebtoken.Claims claims);
    String extractUserNameSilently(HttpServletRequest request);
    Optional<String> extractSessionIdSilently(HttpServletRequest request);
    java.util.Set<org.springframework.security.core.authority.SimpleGrantedAuthority> getRoles(io.jsonwebtoken.Claims claims);
}
