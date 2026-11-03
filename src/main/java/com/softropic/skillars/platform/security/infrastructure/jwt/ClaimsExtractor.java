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
    String extractUserNameSilently(HttpServletRequest request);
    Optional<String> extractSessionIdSilently(HttpServletRequest request);
    java.util.Set<org.springframework.security.core.authority.SimpleGrantedAuthority> getRoles(io.jsonwebtoken.Claims claims);
}
