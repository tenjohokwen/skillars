package com.softropic.skillars.platform.security.infrastructure.jwt;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.softropic.skillars.infrastructure.exception.AppSetupException;
import com.softropic.skillars.platform.security.config.SimpleGrantedAuthorityMixin;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.util.Arrays;
import java.util.Base64;

/**
 * Holds the JWT HMAC signing key, read once from {@code app.security.jwt.signing-key}
 * (env {@code JWT_SIGNING_KEY}, Base64, at least {@value #MIN_KEY_BYTES} bytes decoded, which HS512 needs).
 *
 * <p>The key used to live in the {@code sec} table, encrypted with a password derived from constants in
 * the source, so reading that row was enough to forge any token. It now never touches the database. The
 * key is parsed once at construction, so a missing or malformed value fails startup in every profile.
 * Error messages never include the key.
 */
@Component
public class JwtConfiguration {
    /** HS512 requires a key of at least 512 bits. */
    public static final int MIN_KEY_BYTES = 64;

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final SecretKey secretKey;

    static {
        MAPPER.setVisibility(MAPPER.getSerializationConfig()
                .getDefaultVisibilityChecker()
                .withFieldVisibility(JsonAutoDetect.Visibility.ANY)
                .withGetterVisibility(JsonAutoDetect.Visibility.NONE)
                .withSetterVisibility(JsonAutoDetect.Visibility.NONE)
                .withCreatorVisibility(JsonAutoDetect.Visibility.NONE));
        MAPPER.addMixIn(SimpleGrantedAuthority.class, SimpleGrantedAuthorityMixin.class);
    }

    public JwtConfiguration(@Value("${app.security.jwt.signing-key:}") String base64SigningKey) {
        this.secretKey = parseSigningKey(base64SigningKey);
    }

    /**
     * Decodes and validates a Base64 signing key.
     *
     * @throws AppSetupException if the value is blank, not Base64, or decodes to fewer than
     *                           {@link #MIN_KEY_BYTES} bytes
     */
    public static SecretKey parseSigningKey(String base64SigningKey) {
        if (base64SigningKey == null || base64SigningKey.isBlank()) {
            throw new AppSetupException("app.security.jwt.signing-key (env JWT_SIGNING_KEY) must be set");
        }
        final byte[] decoded;
        try {
            decoded = Base64.getDecoder().decode(base64SigningKey.trim());
        } catch (IllegalArgumentException e) {
            throw new AppSetupException("app.security.jwt.signing-key (env JWT_SIGNING_KEY) must be Base64-encoded");
        }
        try {
            if (decoded.length < MIN_KEY_BYTES) {
                throw new AppSetupException("app.security.jwt.signing-key (env JWT_SIGNING_KEY) must decode to at least "
                                            + MIN_KEY_BYTES + " bytes");
            }
            return Keys.hmacShaKeyFor(decoded);
        } finally {
            Arrays.fill(decoded, (byte) 0);
        }
    }

    public ObjectMapper getMapper() {
        return MAPPER;
    }

    public SecretKey getSecretKey() {
        return secretKey;
    }
}
