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
 * development defaults from {@code application-dev.yaml}.
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

    /** Every value application-dev.yaml falls back to for these properties, plus the test fixtures'. */
    private static final Map<String, List<String>> KNOWN_DEFAULTS = Map.of(
        PIN_SECRET, List.of("S3CR3TW0RD"),
        PLAYBACK_SECRET, List.of("dGVzdC1wbGF5YmFjay1zaWduaW5nLXNlY3JldC0zMi1ieXRlcyEh"),
        REGISTRATION_SECRET, List.of("dev-registration-verification-secret-32b!"),
        BUNNY_WEBHOOK_SECRET, List.of("dev-webhook-signing-secret"));

    private static final int MIN_HMAC_KEY_BYTES = 32;

    private final Environment env;

    public DeployedSecretsValidator(Environment env) {
        this.env = env;
    }

    @PostConstruct
    void validate() {
        KNOWN_DEFAULTS.forEach(this::requireRealValue);
        requireBase64Key(PLAYBACK_SECRET);
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

    private void requireBase64Key(String property) {
        byte[] decoded;
        try {
            decoded = Base64.getDecoder().decode(env.getProperty(property));
        } catch (IllegalArgumentException e) {
            throw new AppSetupException(property + " must be Base64-encoded");
        }
        if (decoded.length < MIN_HMAC_KEY_BYTES) {
            throw new AppSetupException(property + " must decode to at least " + MIN_HMAC_KEY_BYTES + " bytes");
        }
    }
}
