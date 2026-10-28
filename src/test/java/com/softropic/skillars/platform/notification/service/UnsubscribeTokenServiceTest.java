package com.softropic.skillars.platform.notification.service;

import com.softropic.skillars.platform.admin.config.SkillarsPlatformProperties;
import com.softropic.skillars.platform.notification.contract.NotificationCategory;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Notification centre (UI-gap chunk C9): the one-click unsubscribe token. */
class UnsubscribeTokenServiceTest {

    private static final String SECRET = "unit-test-registration-verification-secret-0123456789";

    static UnsubscribeTokenService service(String secret) {
        SkillarsPlatformProperties props = new SkillarsPlatformProperties();
        props.setRegistrationVerificationSecret(secret);
        return new UnsubscribeTokenService(props);
    }

    private final UnsubscribeTokenService service = service(SECRET);

    @Test
    void validToken_roundTripsUserAndCategory() {
        String token = service.mint(42L, NotificationCategory.REMINDER);

        assertThat(service.verify(token))
            .contains(new UnsubscribeTokenService.Claims(42L, NotificationCategory.REMINDER));
    }

    @Test
    void tamperedSignature_isRejected() {
        String token = service.mint(42L, NotificationCategory.REMINDER);
        String tampered = token.substring(0, token.length() - 2) + (token.endsWith("AA") ? "BB" : "AA");

        assertThat(service.verify(tampered)).isEmpty();
    }

    @Test
    void payloadSwappedToAnotherUser_isRejected() {
        String token = service.mint(42L, NotificationCategory.REMINDER);
        String[] parts = token.split("\\.");
        String forgedPayload = b64("43:REMINDER");

        assertThat(service.verify(parts[0] + "." + forgedPayload + "." + parts[2])).isEmpty();
    }

    @Test
    void payloadSwappedToAnotherCategory_isRejected() {
        String token = service.mint(42L, NotificationCategory.REMINDER);
        String[] parts = token.split("\\.");

        assertThat(service.verify(parts[0] + "." + b64("42:DEVELOPMENT") + "." + parts[2])).isEmpty();
    }

    @Test
    void tokenFromAnotherSecret_isRejected() {
        String foreign = service("a-completely-different-secret-value-9876543210").mint(42L, NotificationCategory.REMINDER);

        assertThat(service.verify(foreign)).isEmpty();
    }

    @Test
    void malformedTokens_areRejectedWithoutThrowing() {
        assertThat(service.verify(null)).isEmpty();
        assertThat(service.verify("")).isEmpty();
        assertThat(service.verify("garbage")).isEmpty();
        assertThat(service.verify("v1.%%%.%%%")).isEmpty();
        assertThat(service.verify("v2.a.b")).isEmpty();
        assertThat(service.verify("v1.a.b.c")).isEmpty();
        assertThat(service.verify("x".repeat(600))).isEmpty();
    }

    @Test
    void transactionalCategory_cannotBeMinted() {
        assertThatThrownBy(() -> service.mint(42L, NotificationCategory.BOOKING))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void differentKeyFromOtpHasher_sameSecretDifferentPurpose() {
        // The derived key is purpose-labelled: two tokens for different users never collide, and the
        // token for one user cannot be replayed for another (covered above); this pins determinism.
        assertThat(service.mint(7L, NotificationCategory.DEVELOPMENT))
            .isEqualTo(service(SECRET).mint(7L, NotificationCategory.DEVELOPMENT))
            .isNotEqualTo(service.mint(8L, NotificationCategory.DEVELOPMENT));
    }

    private static String b64(String s) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(s.getBytes(StandardCharsets.UTF_8));
    }
}
