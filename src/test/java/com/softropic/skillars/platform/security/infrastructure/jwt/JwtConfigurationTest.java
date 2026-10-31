package com.softropic.skillars.platform.security.infrastructure.jwt;

import com.softropic.skillars.infrastructure.exception.AppSetupException;
import org.junit.jupiter.api.Test;

import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JwtConfigurationTest {

    private static String b64(int bytes) {
        final byte[] b = new byte[bytes];
        java.util.Arrays.fill(b, (byte) 7);
        return Base64.getEncoder().encodeToString(b);
    }

    @Test
    void validKey_isAccepted_andUsableForHs512() {
        final JwtConfiguration cfg = new JwtConfiguration(b64(64));
        assertThat(cfg.getSecretKey().getEncoded()).hasSize(64);
        assertThat(cfg.getSecretKey().getAlgorithm()).isEqualTo("HmacSHA512");
    }

    @Test
    void longerKey_isAccepted() {
        assertThat(new JwtConfiguration(b64(100)).getSecretKey().getEncoded()).hasSize(100);
    }

    @Test
    void blankOrNull_failsStartup() {
        assertThatThrownBy(() -> new JwtConfiguration("")).isInstanceOf(AppSetupException.class)
            .hasMessageContaining("JWT_SIGNING_KEY");
        assertThatThrownBy(() -> new JwtConfiguration("   ")).isInstanceOf(AppSetupException.class);
        assertThatThrownBy(() -> new JwtConfiguration(null)).isInstanceOf(AppSetupException.class);
    }

    @Test
    void notBase64_failsStartup_withoutEchoingTheValue() {
        final String bad = "this is *not* base64 !!";
        assertThatThrownBy(() -> new JwtConfiguration(bad)).isInstanceOf(AppSetupException.class)
            .hasMessageContaining("Base64").hasMessageNotContaining(bad);
    }

    @Test
    void tooShort_failsStartup_withoutEchoingTheValue() {
        final String shortKey = b64(63);
        assertThatThrownBy(() -> new JwtConfiguration(shortKey)).isInstanceOf(AppSetupException.class)
            .hasMessageContaining("64 bytes").hasMessageNotContaining(shortKey);
    }
}
