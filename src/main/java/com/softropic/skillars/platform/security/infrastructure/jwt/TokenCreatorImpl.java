package com.softropic.skillars.platform.security.infrastructure.jwt;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.softropic.skillars.infrastructure.util.ClockProvider;
import com.softropic.skillars.platform.security.contract.Principal;
import com.softropic.skillars.platform.security.contract.exception.InvalidJWTDataException;
import com.softropic.skillars.infrastructure.security.RequestMetadataProvider;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;

import static com.softropic.skillars.infrastructure.security.SecurityConstants.*;
import static com.softropic.skillars.infrastructure.security.SecurityError.FAULTY_JWT_ROLES_CLAIMS;

@Service
public class TokenCreatorImpl implements TokenCreator {
    private final JwtConfiguration jwtConfiguration;

    public TokenCreatorImpl(JwtConfiguration jwtConfiguration) {
        this.jwtConfiguration = jwtConfiguration;
    }

    @Override
    public String generateToken(Principal principal, Long dbRefreshToken, boolean isLoggedIn, String seed) {
        final Map<String, Object> claims = toClaims(principal, dbRefreshToken, isLoggedIn, seed);
        return generateTokenFromClaims(claims);
    }

    @Override
    public String generateTokenFromClaims(Map<String, Object> claims) {
        final Date exp = new Date(ClockProvider.getClock().millis() + JWT_TTL.toMillis());
        return Jwts.builder()
                .claims(claims)
                .expiration(exp)
                .signWith(jwtConfiguration.getSecretKey())
                .compact();
    }

    @Override
    public Map<String, Object> toClaims(Principal principal, Long dbRefreshToken, boolean isLoggedIn, String seed) throws InvalidJWTDataException {
        final Map<String, Object> claims = new HashMap<>();
        claims.put(Claims.SUBJECT, principal.getUsername());
        claims.put(ROLES, toJson(principal.getAuthorities(), "Attempt to serialize roles"));
        claims.put(Claims.ISSUED_AT, Date.from(Instant.now(ClockProvider.getClock())).getTime() / 1000);
        claims.put(DB_REFRESH_TOKEN, dbRefreshToken);
        if (StringUtils.isNotBlank(RequestMetadataProvider.getClientInfo().getUserAgent())) {
            final String uaHash = String.valueOf(RequestMetadataProvider.getClientInfo().getUserAgent().hashCode());
            claims.put(USER_AGENT_HASH, uaHash);
        }
        claims.put(SESSION_ID, RequestMetadataProvider.getClientInfo().getSessionId());
        claims.put(CLIENT_ID, getClientId(isLoggedIn));
        claims.put(DISPLAY_NAME, principal.getDisplayName());
        claims.put(GENDER, String.valueOf(principal.getGender()));
        claims.put(BUS_ID, principal.getBusinessId());
        claims.put(OTP_ENABLED, principal.isOtpEnabled());
        if (StringUtils.isNotBlank(seed)) {
            claims.put(OPF_SEED, seed);
        }
        return claims;
    }

    private String toJson(Object object, String context) throws InvalidJWTDataException {
        try {
            return jwtConfiguration.getMapper().writeValueAsString(object);
        } catch (JsonProcessingException e) {
            throw new InvalidJWTDataException(context, e, FAULTY_JWT_ROLES_CLAIMS);
        }
    }

    private String getClientId(final boolean isLoggedIn) {
        var requestMetadata = RequestMetadataProvider.getClientInfo();
        if (requestMetadata.isMachineClient()) {
            return requestMetadata.getApiKey();
        }
        if (StringUtils.isNotBlank(requestMetadata.getFingerprintCookie())) {
            return requestMetadata.getFingerprintCookie();
        }
        if (isLoggedIn && StringUtils.isNotBlank(requestMetadata.getBrowserCookie())) {
            return requestMetadata.getBrowserCookie();
        }
        
        String msg = "Missing pre-login client identifier. Expected an '\''fcookie'\'' or '\''apikey'\''";
        if (isLoggedIn) {
            msg = "Missing client identifier. Expected '\''bcookie or '\''apikey'\''";
        }

        throw new com.softropic.skillars.platform.security.contract.exception.MissingClientIdException(msg);
    }
}
