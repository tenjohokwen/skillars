package com.softropic.skillars.platform.security.infrastructure.jwt;

import com.fasterxml.jackson.core.type.TypeReference;
import com.softropic.skillars.platform.security.contract.Gender;
import com.softropic.skillars.infrastructure.security.CookieUtil;
import com.softropic.skillars.platform.security.contract.Principal;
import com.softropic.skillars.platform.security.contract.exception.InvalidJWTDataException;
import com.softropic.skillars.platform.security.contract.exception.JWTExpiredException;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.Jws;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.MalformedJwtException;
import io.jsonwebtoken.security.SignatureException;
import jakarta.servlet.http.HttpServletRequest;
import org.apache.commons.lang3.StringUtils;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import static com.softropic.skillars.infrastructure.security.SecurityConstants.*;
import static com.softropic.skillars.infrastructure.security.SecurityError.*;
import static com.softropic.skillars.platform.security.contract.util.AuthoritiesConstants.ANONYMOUS;
import static java.lang.String.format;

@Service
public class ClaimsExtractorImpl implements ClaimsExtractor {
    private final JwtConfiguration jwtConfiguration;

    public ClaimsExtractorImpl(JwtConfiguration jwtConfiguration) {
        this.jwtConfiguration = jwtConfiguration;
    }

    @Override
    public Claims extractClaims(String token) {
        try {
            return Jwts.parser().verifyWith(jwtConfiguration.getSecretKey()).build().parse(token).accept(Jws.CLAIMS).getPayload();
        } catch (SignatureException | MalformedJwtException | SecurityException | IllegalArgumentException e) {
            throw new InvalidJWTDataException("The Jwt token could not be parsed.", e, JWT_PARSE_ERROR);
        } catch (ExpiredJwtException e) {
            throw new JWTExpiredException("The JWT has expired", e, JWT_EXPIRED);
        }
    }

    @Override
    public Optional<Claims> extractClaimsFromTokenSilently(String token) {
        if (StringUtils.isNotBlank(token)) {
            try {
                return Optional.ofNullable(extractClaims(token));
            } catch (Exception e) {
                return Optional.empty();
            }
        }
        return Optional.empty();
    }

    @Override
    public Optional<Claims> extractClaimsSilently(HttpServletRequest request) {
        final String token = CookieUtil.getCookieValue(request, JWT_COOKIE_NAME);
        return extractClaimsFromTokenSilently(token);
    }

    @Override
    public Principal extractPrincipal(HttpServletRequest request) {
        final String token = CookieUtil.getCookieValue(request, JWT_COOKIE_NAME);
        if (StringUtils.isNotBlank(token)) {
            final Claims claims = extractClaims(token);
            final Set<SimpleGrantedAuthority> authorities = getRoles(claims);
            final boolean otpEnabled = Objects.nonNull(claims.get(OTP_ENABLED)) && (boolean) claims.get(OTP_ENABLED);
            return new Principal.Builder().username(claims.getSubject())
                    .password("protectedPassword")
                    .enabled(true)
                    .otpEnabled(otpEnabled)
                    .authorities(authorities)
                    .gender(Gender.fromString(claims.get(GENDER, String.class)))
                    .businessId(claims.get(BUS_ID, String.class))
                    .phone(" ")
                    .displayName(claims.get(DISPLAY_NAME, String.class))
                    .build();
        }
        return new Principal.Builder().username(ANONYMOUS)
                .password("N/A")
                .enabled(false)
                .otpEnabled(false)
                .authorities(List.of())
                .businessId("anon1")
                .phone(" ")
                .displayName("")
                .build();
    }

    @Override
    public Long extractDbRefreshToken(HttpServletRequest request) throws InvalidJWTDataException {
        final String token = CookieUtil.getCookieValue(request, JWT_COOKIE_NAME);
        if (StringUtils.isNotBlank(token)) {
            final Claims claims = extractClaims(token);
            Object refreshToken = claims.get(DB_REFRESH_TOKEN);
            if (refreshToken == null) {
                return null;
            }
            return Instant.ofEpochMilli(((Number) refreshToken).longValue()).toEpochMilli();
        }
        return null;
    }

    @Override
    public String extractUserNameSilently(HttpServletRequest request) {
        return extractClaimsSilently(request).map(Claims::getSubject).orElse(null);
    }

    @Override
    public Optional<String> extractSessionIdSilently(HttpServletRequest request) {
        return extractClaimsSilently(request).map(claims -> claims.get(SESSION_ID, String.class));
    }

    @Override
    public Set<SimpleGrantedAuthority> getRoles(final Claims claims) throws InvalidJWTDataException {
        final String roles = (String) claims.get(ROLES);
        try {
            return jwtConfiguration.getMapper().readValue(roles, new TypeReference<>() {});
        } catch (IOException e) {
            throw new InvalidJWTDataException(format("Attempt to read roles from claims failed. Got %s", roles),
                    e, FAULTY_JWT_ROLES_CLAIMS);
        }
    }
}
