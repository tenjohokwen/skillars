package com.softropic.skillars.infrastructure.config;

import com.softropic.skillars.infrastructure.exception.AppSetupException;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DeployedSecretsValidatorTest {

    private static final String GOOD_KEY = Base64.getEncoder().encodeToString(new byte[40]);

    private MockEnvironment valid() {
        return new MockEnvironment()
            .withProperty(DeployedSecretsValidator.PIN_SECRET, "a-real-pin-secret")
            .withProperty(DeployedSecretsValidator.PLAYBACK_SECRET, GOOD_KEY)
            .withProperty(DeployedSecretsValidator.REGISTRATION_SECRET, "a-real-registration-secret-of-32-chars")
            .withProperty(DeployedSecretsValidator.BUNNY_WEBHOOK_SECRET, "a-real-bunny-key");
    }

    private void validate(MockEnvironment env) {
        new DeployedSecretsValidator(env).validate();
    }

    @Test
    void realSecrets_pass() {
        assertThatCode(() -> validate(valid())).doesNotThrowAnyException();
    }

    @Test
    void blankPinSecret_failsNamingTheProperty() {
        assertThatThrownBy(() -> validate(valid().withProperty(DeployedSecretsValidator.PIN_SECRET, " ")))
            .isInstanceOf(AppSetupException.class).hasMessageContaining(DeployedSecretsValidator.PIN_SECRET);
    }

    @Test
    void missingPlaybackSecret_fails() {
        MockEnvironment env = valid().withProperty(DeployedSecretsValidator.PLAYBACK_SECRET, "");
        assertThatThrownBy(() -> validate(env))
            .isInstanceOf(AppSetupException.class).hasMessageContaining(DeployedSecretsValidator.PLAYBACK_SECRET);
    }

    @Test
    void devDefaults_areRejected() {
        assertThatThrownBy(() -> validate(valid().withProperty(DeployedSecretsValidator.PIN_SECRET, "S3CR3TW0RD")))
            .hasMessageContaining("development default");
        assertThatThrownBy(() -> validate(valid().withProperty(DeployedSecretsValidator.PLAYBACK_SECRET,
            "dGVzdC1wbGF5YmFjay1zaWduaW5nLXNlY3JldC0zMi1ieXRlcyEh")))
            .hasMessageContaining("development default");
        assertThatThrownBy(() -> validate(valid().withProperty(DeployedSecretsValidator.BUNNY_WEBHOOK_SECRET,
            "dev-webhook-signing-secret")))
            .hasMessageContaining("development default");
    }

    @Test
    void playbackSecret_mustBeBase64OfAtLeast32Bytes() {
        assertThatThrownBy(() -> validate(valid().withProperty(DeployedSecretsValidator.PLAYBACK_SECRET, "not base64!!")))
            .hasMessageContaining("Base64");
        assertThatThrownBy(() -> validate(valid().withProperty(DeployedSecretsValidator.PLAYBACK_SECRET,
            Base64.getEncoder().encodeToString(new byte[16]))))
            .hasMessageContaining("32 bytes");
    }
}
