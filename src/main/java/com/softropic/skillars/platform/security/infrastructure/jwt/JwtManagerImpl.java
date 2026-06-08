package com.softropic.skillars.platform.security.infrastructure.jwt;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.softropic.skillars.infrastructure.util.ClockProvider;
import com.softropic.skillars.platform.security.contract.Gender;
import com.softropic.skillars.platform.security.service.LoginTokenManager;
import com.softropic.skillars.infrastructure.security.CookieUtil;
import com.softropic.skillars.platform.security.contract.Principal;
import com.softropic.skillars.infrastructure.security.AuthorizationException;
import com.softropic.skillars.platform.security.contract.exception.InvalidJWTDataException;
import com.softropic.skillars.infrastructure.security.RequestMetadataProvider;
import io.jsonwebtoken.Claims;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;

import static com.softropic.skillars.infrastructure.security.SecurityConstants.*;
import static com.softropic.skillars.infrastructure.security.SecurityError.*;
import static com.softropic.skillars.platform.security.contract.util.AuthoritiesConstants.ANONYMOUS;

@Slf4j
@Component
public class JwtManagerImpl implements LoginTokenManager {
    private final TokenCreator tokenCreator;
    private final TokenValidator tokenValidator;
    private final ClaimsExtractor claimsExtractor;
    private final JwtConfiguration jwtConfiguration;

    public JwtManagerImpl(TokenCreator tokenCreator,
                          TokenValidator tokenValidator,
                          ClaimsExtractor claimsExtractor,
                          JwtConfiguration jwtConfiguration) {
        this.tokenCreator = tokenCreator;
        this.tokenValidator = tokenValidator;
        this.claimsExtractor = claimsExtractor;
        this.jwtConfiguration = jwtConfiguration;
    }

    @Override
    public void createLoginToken(HttpServletResponse res, Principal principal) {
        final long dbRefreshToken = Instant.now(ClockProvider.getClock())
                .plus(DB_REFRESH_TOKEN_INTERVAL.toMillis(), ChronoUnit.MILLIS)
                .toEpochMilli();
        final Map<String, Object> claims = tokenCreator.toClaims(principal, dbRefreshToken, false, null);
        createAndSetJwt(res, claims);
    }

    @Override
    public void renewLoginToken(HttpServletResponse res, Principal principal) {
        final long dbRefreshToken = Instant.now(ClockProvider.getClock())
                .plus(DB_REFRESH_TOKEN_INTERVAL.toMillis(), ChronoUnit.MILLIS)
                .toEpochMilli();
        final Map<String, Object> claims = tokenCreator.toClaims(principal, dbRefreshToken, true, null);
        createAndSetJwt(res, claims);
    }

    @Override
    public Authentication refreshLoginToken(HttpServletResponse res, String token) {
        final Optional<Claims> claimsOpt = claimsExtractor.extractClaimsFromTokenSilently(token);
        if (claimsOpt.isPresent()) {
            final Claims extractedClaims = claimsOpt.get();
            Map<String, Object> claims = new HashMap<>(extractedClaims);
            claims.put(Claims.ISSUED_AT, Date.from(Instant.now(ClockProvider.getClock())).getTime() / 1000);
            final long dbRefreshToken = Instant.now(ClockProvider.getClock())
                    .plus(DB_REFRESH_TOKEN_INTERVAL.toMillis(), ChronoUnit.MILLIS)
                    .toEpochMilli();
            claims.put(DB_REFRESH_TOKEN, dbRefreshToken);

            createAndSetJwt(res, claims);
            return authentication(claims);
        }
        throw new InvalidJWTDataException("Claims not present in token or invalid token.", JWT_PARSE_ERROR);
    }

    private Authentication authentication(Map<String, Object> claims) {
        final boolean otpEnabled = Objects.nonNull(claims.get(OTP_ENABLED)) && (boolean) claims.get(OTP_ENABLED);
        final Principal principal = new Principal.Builder().otpEnabled(otpEnabled).enabled(true).password("N/A")
                .username((String) claims.get(Claims.SUBJECT))
                .businessId((String) claims.get(BUS_ID))
                .displayName((String) claims.get(DISPLAY_NAME))
                .gender(Gender.valueOf((String) claims.get(GENDER)))
                .authorities(getAuthoritiesSilently(claims))
                .build();
        return UsernamePasswordAuthenticationToken.authenticated(principal, "", principal.getAuthorities());
    }

    private Collection<SimpleGrantedAuthority> getAuthoritiesSilently(Map<String, Object> claims) {
        try {
            final String roles = (String) claims.get(ROLES);
            return jwtConfiguration.getMapper().readValue(roles, new TypeReference<List<SimpleGrantedAuthority>>() {});
        } catch (JsonProcessingException e) {
            log.error("Could not parse authorities from claims", e);
        }
        return List.of();
    }

    @Override
    public String generateToken(Principal principal, String seed) {
        final long dbRefreshToken = Instant.now(ClockProvider.getClock())
                .plus(DB_REFRESH_TOKEN_INTERVAL.toMillis(), ChronoUnit.MILLIS)
                .toEpochMilli();
        return tokenCreator.generateToken(principal, dbRefreshToken, false, seed);
    }

    @Override
    public String generateAnonymousSession(HttpServletResponse response) {
        final String sessionId = RequestMetadataProvider.getClientInfo().getSessionId();
        CookieUtil.addCookie(response, ANONYMOUS_SESSION_COOKIE, sessionId, true, -1);
        return sessionId;
    }

    @Override
    public void removeTwoFactorCookie(HttpServletResponse response) {
        CookieUtil.removeCookie(LOGIN_INFO_ID, response, true);
    }

    @Override
    public void extendTtlOfToken(HttpServletRequest req, HttpServletResponse res) {
        final Principal principal = claimsExtractor.extractPrincipal(req);
        if (principal != null && !ANONYMOUS.equals(principal.getUsername())) {
            final Long dbRefreshToken = claimsExtractor.extractDbRefreshToken(req);
            if (dbRefreshToken != null) {
                final Map<String, Object> claims = tokenCreator.toClaims(principal, dbRefreshToken, true, null);
                createAndSetJwt(res, claims);
            } else {
                throw new InvalidJWTDataException("Cannot find dbRefreshToken in JWT.", MISSING_JWT_DB_REFRESH_TOKEN);
            }
        } else {
            throw new InvalidJWTDataException("Cannot find principal in JWT.", MISSING_JWT_PRINCIPAL);
        }
    }

    @Override
    public void deleteLoginToken(HttpServletResponse response) {
        CookieUtil.removeCookie(JWT_COOKIE_NAME, response, true);
        CookieUtil.removeCookie(B_COOKIE, response, true);
        CookieUtil.removeCookie(USER_COOKIE, response, false);
        CookieUtil.removeCookie(ADMIN_COOKIE, response, false);
        CookieUtil.removeCookie(JWT_SESSION_COOKIE, response, true);
        CookieUtil.removeCookie(SESSION_REFRESH_COUNTDOWN, response, false);
    }

    @Override
    public Principal extractPrincipal(HttpServletRequest request) {
        return claimsExtractor.extractPrincipal(request);
    }

    @Override
    public boolean isTokenFixed(HttpServletRequest request) {
        return tokenValidator.isTokenFixed(request);
    }

    @Override
    public boolean hasDbRefreshTokenExpired(HttpServletRequest request) {
        return tokenValidator.hasDbRefreshTokenExpired(request);
    }

    @Override
    public String extractUserNameSilently(HttpServletRequest request) {
        return claimsExtractor.extractUserNameSilently(request);
    }

    @Override
    public void addLoginInfoIdCookie(HttpServletResponse res, String value) {
        CookieUtil.addCookie(res, LOGIN_INFO_ID, value, true, (int) OTP_TTL.toSeconds());
    }

    @Override
    public String extractLoginInfoIdCookie(HttpServletRequest req) {
        return CookieUtil.getCookieValue(req, LOGIN_INFO_ID);
    }

    @Override
    public void ensureClientHasPreLoginId() {
        tokenValidator.ensureClientHasPreLoginId();
    }

    @Override
    public void ensureClientHasPostLoginId() {
        tokenValidator.ensureClientHasPostLoginId();
    }

    @Override
    public void ensureAuthTokenPresent(HttpServletRequest request) throws AuthorizationException {
        tokenValidator.ensureAuthTokenPresent(request);
    }

    @Override
    public Optional<String> extractSessionIdSilently(HttpServletRequest request) {
        return claimsExtractor.extractSessionIdSilently(request);
    }

    private void createAndSetJwt(HttpServletResponse res, Map<String, Object> claims) {
        createLoginCookies(res, claims);
        final String token = tokenCreator.generateTokenFromClaims(claims);
        CookieUtil.addCookie(res, JWT_COOKIE_NAME, token, true, (int) JWT_TTL.toSeconds());
    }

    private void createLoginCookies(HttpServletResponse res, Map<String, Object> claims) {
        final int browserSessionTtl = -1;

        final String clientId = (String) claims.get(CLIENT_ID);
        CookieUtil.addCookie(res, B_COOKIE, clientId, true, browserSessionTtl);

        final String subject = (String) claims.get(DISPLAY_NAME);
        CookieUtil.addCookie(res, USER_COOKIE, subject, false, (int) JWT_TTL.toSeconds());

        final String sessionId = (String) claims.get(SESSION_ID);
        CookieUtil.addCookie(res, JWT_SESSION_COOKIE, sessionId, true, browserSessionTtl);

        final String roles = (String) claims.get(ROLES);
        if (StringUtils.containsIgnoreCase(roles, "ADMIN")) {
            CookieUtil.addCookie(res, ADMIN_COOKIE, "admin", false, (int) JWT_TTL.toSeconds());
        }
        CookieUtil.addCookie(res,
                SESSION_REFRESH_COUNTDOWN,
                String.valueOf(JWT_TTL.minusMinutes(5).toMillis()),
                false,
                browserSessionTtl);
    }
}
