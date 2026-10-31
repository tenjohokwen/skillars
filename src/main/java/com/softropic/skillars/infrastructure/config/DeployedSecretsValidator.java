package com.softropic.skillars.infrastructure.config;

import com.softropic.skillars.infrastructure.exception.AppSetupException;
import jakarta.annotation.PostConstruct;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.Base64;
import java.util.List;
import java.util.Map;

/**
 * Refuses to boot a deployed ({@code prod}/{@code uat}) instance whose secrets are blank or still the
 * development defaults from {@code application-dev.yaml}. The JWT signing key ({@code JWT_SIGNING_KEY}) is
 * held to the same rule and must be Base64 of at least 64 bytes. The S3 endpoint must be {@code https}:
 * presigned URLs carry the access key in their query string and are fetched by browsers (S-25). Values are
 * never put in a message.
 *
 * <p>Blank {@code PLATFORM_PIN_ENCRYPTION_SECRET} already aborts boot in {@code Cryptopher}; the video
 * playback signing secret, by contrast, only failed the first time a token was issued. Both are checked
 * here so every deployed secret fails the same way, at startup, naming the property. The dev defaults are
 * public in the repository, so accepting one in a deployed environment would be as good as no secret.
 *
 * <p>Plain {@code Environment} lookups rather than the properties beans: the check must not depend on
 * which beans a profile happens to wire.
 */
@Component
@Profile({"prod", "uat"})
public class DeployedSecretsValidator {

    static final String PIN_SECRET = "skillars.platform.pin-encryption-secret";
    static final String PLAYBACK_SECRET = "app.video.playback.signing-secret";
    static final String REGISTRATION_SECRET = "skillars.platform.registration-verification-secret";
    static final String BUNNY_WEBHOOK_SECRET = "app.video.bunny.webhook-signing-secret";
    static final String JWT_SIGNING_KEY = "app.security.jwt.signing-key";
    /** Fed by SPRING_DATA_REDIS_PASSWORD (compose maps it from REDIS_PASSWORD); redis itself runs with the same value. */
    static final String REDIS_PASSWORD = "spring.data.redis.password";
    static final String STORAGE_PROVIDER = "app.storage.provider";
    static final String STORAGE_ENDPOINT_URL = "app.storage.endpoint-url";
    static final String STORAGE_BACKUP_ENDPOINT_URL = "app.storage.replication.backup.endpoint-url";

    /** Every value application-dev.yaml falls back to for these properties, plus the test fixtures'. */
    private static final Map<String, List<String>> KNOWN_DEFAULTS = Map.of(
        PIN_SECRET, List.of("S3CR3TW0RD"),
        PLAYBACK_SECRET, List.of("dGVzdC1wbGF5YmFjay1zaWduaW5nLXNlY3JldC0zMi1ieXRlcyEh"),
        REGISTRATION_SECRET, List.of("dev-registration-verification-secret-32b!"),
        BUNNY_WEBHOOK_SECRET, List.of("dev-webhook-signing-secret"),
        REDIS_PASSWORD, List.of("dev-redis-password-NOT-for-prod"),
        JWT_SIGNING_KEY, List.of(
            "ZGV2LW9ubHktand0LXNpZ25pbmcta2V5LS1OT1QtZm9yLXByb2Qtb3ItdWF0LS1sb2NhbC1kZXZlbG9wbWVudC1vbmx5ISE=",
            "dGVzdC1vbmx5LWp3dC1zaWduaW5nLWtleS0tdW5pdC1hbmQtaW50ZWdyYXRpb24tdGVzdHMtb25seS0wMTIzNDU2Nzg5"));

    private static final int MIN_HMAC_KEY_BYTES = 32;
    /** HS512 (JWT access tokens) needs 512 bits. */
    private static final int MIN_JWT_KEY_BYTES = 64;

    private final Environment env;

    public DeployedSecretsValidator(Environment env) {
        this.env = env;
    }

    @PostConstruct
    void validate() {
        KNOWN_DEFAULTS.forEach(this::requireRealValue);
        requireBase64Key(PLAYBACK_SECRET, MIN_HMAC_KEY_BYTES);
        requireBase64Key(JWT_SIGNING_KEY, MIN_JWT_KEY_BYTES);
        requireHttpsStorageEndpoints();
    }

    /**
     * Only the {@code s3} provider (the default) builds URLs from the endpoint; the {@code local} provider has
     * none. The backup endpoint is optional (blank means no replication target), so it is checked only when set.
     */
    private void requireHttpsStorageEndpoints() {
        if (!"s3".equalsIgnoreCase(env.getProperty(STORAGE_PROVIDER, "s3"))) {
            return;
        }
        String endpoint = env.getProperty(STORAGE_ENDPOINT_URL);
        if (endpoint == null || endpoint.isBlank()) {
            throw new AppSetupException(STORAGE_ENDPOINT_URL + " must be set in a deployed environment");
        }
        requireHttps(STORAGE_ENDPOINT_URL, endpoint);
        String backup = env.getProperty(STORAGE_BACKUP_ENDPOINT_URL);
        if (backup != null && !backup.isBlank()) {
            requireHttps(STORAGE_BACKUP_ENDPOINT_URL, backup);
        }
    }

    private void requireHttps(String property, String url) {
        if (!url.trim().regionMatches(true, 0, "https://", 0, "https://".length())) {
            throw new AppSetupException(property + " must be an https:// URL in a deployed environment");
        }
    }

    private void requireRealValue(String property, List<String> knownDefaults) {
        String value = env.getProperty(property);
        if (value == null || value.isBlank()) {
            throw new AppSetupException(property + " must be set in a deployed environment");
        }
        if (knownDefaults.contains(value)) {
            throw new AppSetupException(property + " is still the development default; set a real secret");
        }
    }

    private void requireBase64Key(String property, int minBytes) {
        byte[] decoded;
        try {
            decoded = Base64.getDecoder().decode(env.getProperty(property));
        } catch (IllegalArgumentException e) {
            throw new AppSetupException(property + " must be Base64-encoded");
        }
        if (decoded.length < minBytes) {
            throw new AppSetupException(property + " must decode to at least " + minBytes + " bytes");
        }
    }
}
