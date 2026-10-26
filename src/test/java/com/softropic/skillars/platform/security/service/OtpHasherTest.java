package com.softropic.skillars.platform.security.service;

import com.softropic.skillars.platform.admin.config.SkillarsPlatformProperties;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** skillars-deferred-162 AC2.6. */
class OtpHasherTest {

    static final String SECRET = "0123456789abcdef0123456789abcdef";

    /** A hasher over a fixed test secret, shared by the registration-service unit tests. */
    static OtpHasher hasher() {
        return hasher(SECRET);
    }

    static OtpHasher hasher(String secret) {
        SkillarsPlatformProperties props = new SkillarsPlatformProperties();
        props.setRegistrationVerificationSecret(secret);
        return new OtpHasher(props);
    }

    @Test
    void hash_isDeterministicPerUserAndOtp() {
        assertThat(hasher().hash(42L, "123456")).isEqualTo(hasher().hash(42L, "123456"));
    }

    @Test
    void hash_differsByUserIdOtpAndSecret() {
        String base = hasher().hash(42L, "123456");

        assertThat(hasher().hash(43L, "123456")).isNotEqualTo(base);
        assertThat(hasher().hash(42L, "123457")).isNotEqualTo(base);
        assertThat(hasher(SECRET.replace('0', 'z')).hash(42L, "123456")).isNotEqualTo(base);
    }

    @Test
    void hash_is64LowercaseHexChars() {
        assertThat(hasher().hash(42L, "123456")).matches("[0-9a-f]{64}");
    }

    @Test
    void hash_isNotTheOldUnkeyedFormula() throws Exception {
        String oldFormula = HexFormat.of().formatHex(
            MessageDigest.getInstance("SHA-256").digest("12345642".getBytes(StandardCharsets.UTF_8)));

        assertThat(hasher().hash(42L, "123456")).isNotEqualTo(oldFormula);
    }

    @Test
    void matches_acceptsTheRightOtpAndRejectsOthers() {
        OtpHasher hasher = hasher();
        String stored = hasher.hash(42L, "123456");

        assertThat(hasher.matches(stored, 42L, "123456")).isTrue();
        assertThat(hasher.matches(stored, 42L, "654321")).isFalse();
        assertThat(hasher.matches(stored, 43L, "123456")).isFalse();
        assertThat(hasher.matches(null, 42L, "123456")).isFalse();
        assertThat(hasher.matches(stored, 42L, null)).isFalse();
    }

    @Test
    void blankSecret_failsAtConstruction() {
        assertThatThrownBy(() -> hasher(" ")).isInstanceOf(IllegalArgumentException.class);
    }
}
