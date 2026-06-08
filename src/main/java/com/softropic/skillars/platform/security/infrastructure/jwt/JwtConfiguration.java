package com.softropic.skillars.platform.security.infrastructure.jwt;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.softropic.skillars.infrastructure.exception.AppSetupException;
import com.softropic.skillars.platform.security.config.SimpleGrantedAuthorityMixin;
import com.softropic.skillars.platform.security.contract.exception.InvalidJWTDataException;
import com.softropic.skillars.platform.security.service.SecretService;
import com.softropic.skillars.platform.security.repo.Secret;
import io.jsonwebtoken.security.Keys;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.util.Arrays;
import java.util.Optional;

import static com.softropic.skillars.infrastructure.security.SecurityConstants.JWT_BUS_NAME;
import static com.softropic.skillars.infrastructure.security.SecurityConstants.JWT_VERSION;
import static com.softropic.skillars.infrastructure.security.SecurityError.MISSING_JWT_SECRET;

@Component
public class JwtConfiguration {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private volatile Secret secret;
    private final SecretService secretService;

    static {
        MAPPER.setVisibility(MAPPER.getSerializationConfig()
                .getDefaultVisibilityChecker()
                .withFieldVisibility(JsonAutoDetect.Visibility.ANY)
                .withGetterVisibility(JsonAutoDetect.Visibility.NONE)
                .withSetterVisibility(JsonAutoDetect.Visibility.NONE)
                .withCreatorVisibility(JsonAutoDetect.Visibility.NONE));
        MAPPER.addMixIn(SimpleGrantedAuthority.class, SimpleGrantedAuthorityMixin.class);
    }

    public JwtConfiguration(SecretService secretService) {
        this.secretService = secretService;
    }

    public ObjectMapper getMapper() {
        return MAPPER;
    }

    public SecretKey getSecretKey() {
        final byte[] secretBytes = getSecret().getSecretBytes();
        if (secretBytes == null) {
            throw new InvalidJWTDataException("Secret for jwt not provided", MISSING_JWT_SECRET);
        }
        try {
            return Keys.hmacShaKeyFor(secretBytes.clone());
        } finally {
            Arrays.fill(secretBytes, (byte) 0);
        }
    }

    private Secret getSecret() {
        if (secret == null) {
            synchronized (this) {
                if (secret == null) {
                    secret = Optional.ofNullable(secretService.fetchSecret(JWT_VERSION, JWT_BUS_NAME))
                            .orElseThrow(() -> new AppSetupException("JWT secret key has not been set in DB"));
                }
            }
        }
        return secret;
    }
}
