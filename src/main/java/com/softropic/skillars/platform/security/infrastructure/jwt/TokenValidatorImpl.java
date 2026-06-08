package com.softropic.skillars.platform.security.infrastructure.jwt;

import com.softropic.skillars.infrastructure.util.ClockProvider;
import com.softropic.skillars.infrastructure.security.CookieUtil;
import com.softropic.skillars.infrastructure.security.AuthorizationException;
import com.softropic.skillars.platform.security.contract.exception.InvalidJWTDataException;
import com.softropic.skillars.platform.security.contract.exception.MissingClientIdException;
import com.softropic.skillars.infrastructure.security.RequestMetadata;
import com.softropic.skillars.infrastructure.security.RequestMetadataProvider;
import io.jsonwebtoken.Claims;
import jakarta.servlet.http.HttpServletRequest;
import org.apache.commons.lang3.StringUtils;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Objects;

import static com.softropic.skillars.infrastructure.security.SecurityConstants.*;
import static com.softropic.skillars.infrastructure.security.SecurityError.MISSING_JWT_DB_REFRESH_TOKEN;

@Service
public class TokenValidatorImpl implements TokenValidator {
    private final ClaimsExtractor claimsExtractor;

    public TokenValidatorImpl(ClaimsExtractor claimsExtractor) {
        this.claimsExtractor = claimsExtractor;
    }

    @Override
    public boolean isTokenFixed(HttpServletRequest request) {
        final String token = CookieUtil.getCookieValue(request, JWT_COOKIE_NAME);
        if (StringUtils.isNotBlank(token)) {
            final Claims claims = claimsExtractor.extractClaims(token);
            final String clientId = claims.get(CLIENT_ID, String.class);
            String claimsUA = claims.get(USER_AGENT_HASH, String.class);
            RequestMetadata clientInfo = RequestMetadataProvider.getClientInfo();
            final String currentUA = clientInfo.getUserAgent() == null ? null : String.valueOf(clientInfo.getUserAgent().hashCode());
            final String sessionId = claims.get(SESSION_ID, String.class);
            return !StringUtils.equals(claimsUA, currentUA) ||
                    !Objects.equals(clientId, clientInfo.getClientIdentifier()) ||
                    !Objects.equals(clientInfo.getSessionId(), sessionId);
        }
        return false;
    }

    @Override
    public boolean hasDbRefreshTokenExpired(HttpServletRequest request) {
        final Long dbRefreshToken = claimsExtractor.extractDbRefreshToken(request);
        if (dbRefreshToken != null) {
            return Instant.ofEpochMilli(dbRefreshToken).isBefore(Instant.now(ClockProvider.getClock()));
        }
        throw new InvalidJWTDataException("Cannot find dbRefreshToken in JWT.", MISSING_JWT_DB_REFRESH_TOKEN);
    }

    @Override
    public void ensureClientHasPreLoginId() {
        final RequestMetadata requestMetadata = RequestMetadataProvider.getClientInfo();
        if (!requestMetadata.isMachineClient() && StringUtils.isBlank(requestMetadata.getFingerprintCookie())) {
            throw new MissingClientIdException("Missing pre-login client identifier. Expected a 'fcookie' or 'apikey'");
        }
    }

    @Override
    public void ensureClientHasPostLoginId() {
        final RequestMetadata requestMetadata = RequestMetadataProvider.getClientInfo();
        if (StringUtils.isBlank(requestMetadata.getBrowserCookie()) && StringUtils.isBlank(requestMetadata.getApiKey())) {
            throw new MissingClientIdException("Missing post-login client identifier. Expected 'bcookie or 'apikey'");
        }
    }

    @Override
    public void ensureAuthTokenPresent(HttpServletRequest request) throws AuthorizationException {
        final String token = CookieUtil.getCookieValue(request, JWT_COOKIE_NAME);
        if (StringUtils.isBlank(token)) {
            throw new AccessDeniedException("Missing auth token. Expected 'jwt' cookie.");
        }
    }
}
