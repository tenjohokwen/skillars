package com.softropic.skillars.platform.security.service;

import com.softropic.skillars.platform.admin.config.SkillarsPlatformProperties;
import com.softropic.skillars.platform.security.contract.exception.RegistrationVerificationTokenException;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * skillars-deferred-93 AC8 — {@link RegistrationVerificationTokenService}: an issued handle round
 * trips to its {@code userId} for the role it was minted for; every corruption (bad signature, wrong
 * key, tampered payload, expiry, malformed shape, role mismatch) is rejected with
 * {@link RegistrationVerificationTokenException} and never silently accepted.
 */
class RegistrationVerificationTokenServiceTest {

    private static final String SECRET = "unit-test-registration-verification-secret-key";
    private static final String ROLE = "COACH";

    private RegistrationVerificationTokenService service;

    private static RegistrationVerificationTokenService serviceWithSecret(String secret) {
        SkillarsPlatformProperties props = new SkillarsPlatformProperties();
        props.setRegistrationVerificationSecret(secret);
        return new RegistrationVerificationTokenService(props);
    }

    @BeforeEach
    void setUp() {
        service = serviceWithSecret(SECRET);
    }

    @Test
    void issuedHandle_resolvesBackToTheSameUserId() {
        long userId = 918273645L;
        assertThat(service.resolveUserId(service.issuePhoneVerificationToken(userId, ROLE), ROLE))
            .isEqualTo(userId);
    }

    @Test
    void handleIssuedForAnotherRole_isRejected() {
        String coachHandle = service.issuePhoneVerificationToken(42L, "COACH");
        assertThatThrownBy(() -> service.resolveUserId(coachHandle, "PARENT"))
            .isInstanceOf(RegistrationVerificationTokenException.class);
    }

    @Test
    void blankConfiguredSecret_failsFastAtConstruction() {
        assertThatThrownBy(() -> serviceWithSecret("  "))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("registration-verification-secret");
    }

    @Test
    void shortConfiguredSecret_failsFastAtConstruction() {
        assertThatThrownBy(() -> serviceWithSecret("too-short-secret"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("registration-verification-secret");
    }

    @Test
    void handleSignedByADifferentSecret_isRejected() {
        String foreign = serviceWithSecret("a-totally-different-secret-of-ample-length")
            .issuePhoneVerificationToken(1L, ROLE);
        assertThatThrownBy(() -> service.resolveUserId(foreign, ROLE))
            .isInstanceOf(RegistrationVerificationTokenException.class);
    }

    @Test
    void tamperedSignature_isRejected() {
        // Keep the payload, swap in the (validly-encoded) signature of a different handle — a
        // guaranteed HMAC mismatch, unlike flipping the last base64 char whose low bits are padding.
        String token = service.issuePhoneVerificationToken(42L, ROLE);
        String otherToken = service.issuePhoneVerificationToken(43L, ROLE);
        String otherSig = otherToken.substring(otherToken.indexOf('.') + 1);
        String tampered = token.substring(0, token.indexOf('.') + 1) + otherSig;
        assertThatThrownBy(() -> service.resolveUserId(tampered, ROLE))
            .isInstanceOf(RegistrationVerificationTokenException.class);
    }

    @Test
    void tamperedPayload_isRejected() {
        // Re-encode the payload for a different userId but keep the original signature.
        String token = service.issuePhoneVerificationToken(42L, ROLE);
        String originalSig = token.substring(token.indexOf('.') + 1);
        long forgedExpiry = Instant.now().plusSeconds(3600).getEpochSecond();
        String forgedPayload = Base64.getUrlEncoder().withoutPadding()
            .encodeToString(("1:" + ROLE + ":999999:" + forgedExpiry).getBytes(StandardCharsets.UTF_8));

        assertThatThrownBy(() -> service.resolveUserId(forgedPayload + "." + originalSig, ROLE))
            .isInstanceOf(RegistrationVerificationTokenException.class);
    }

    @Test
    void expiredHandle_isRejected() {
        // Hand-craft a correctly-signed payload whose expiry is in the past.
        long pastExpiry = Instant.now().minusSeconds(60).getEpochSecond();
        String payload = "1:" + ROLE + ":77:" + pastExpiry;
        String encodedPayload = Base64.getUrlEncoder().withoutPadding()
            .encodeToString(payload.getBytes(StandardCharsets.UTF_8));
        String sig = hmacB64(encodedPayload);

        assertThatThrownBy(() -> service.resolveUserId(encodedPayload + "." + sig, ROLE))
            .isInstanceOf(RegistrationVerificationTokenException.class);
    }

    @Test
    void unknownPayloadVersion_isRejected() {
        long expiry = Instant.now().plusSeconds(3600).getEpochSecond();
        String encodedPayload = Base64.getUrlEncoder().withoutPadding()
            .encodeToString(("9:" + ROLE + ":77:" + expiry).getBytes(StandardCharsets.UTF_8));
        assertThatThrownBy(() -> service.resolveUserId(encodedPayload + "." + hmacB64(encodedPayload), ROLE))
            .isInstanceOf(RegistrationVerificationTokenException.class);
    }

    @Test
    void malformedShapes_areRejected() {
        for (String bad : new String[] {
            null, "", "   ", "nodot", "too.many.dots", ".onlysig", "onlypayload.",
            "!!!not-base64!!!.also!!!not"
        }) {
            assertThatThrownBy(() -> service.resolveUserId(bad, ROLE))
                .as("input %s", bad)
                .isInstanceOf(RegistrationVerificationTokenException.class);
        }
    }

    @Test
    void resolveUserId_doesNotThrowForAFreshHandle() {
        assertThatCode(() -> service.resolveUserId(service.issuePhoneVerificationToken(5L, ROLE), ROLE))
            .doesNotThrowAnyException();
    }

    /** Mirrors the service's own HMAC so hand-crafted payloads can be signed with the test secret. */
    private static String hmacB64(String data) {
        try {
            javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
            mac.init(new javax.crypto.spec.SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(data.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
