package com.softropic.skillars.infrastructure.email;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Story ses-1.1 AC3 — {@link EmailTransportPropertyValidator} allows an absent value (falls through
 * to the base default), and accepts {@code ses}/{@code smtp}/{@code log} case-insensitively.
 *
 * <p>Story ses-1.2 AC6: {@code smtp} became a fully working transport in this story, so the
 * Phase-1-only rejection of a present {@code smtp} value no longer applies — it is now allowed
 * exactly like {@code ses}/{@code log}. Anything else still aborts, naming the offending value.
 */
class EmailTransportPropertyValidatorTest {

    private final EmailTransportPropertyValidator validator = new EmailTransportPropertyValidator();

    private void run(String value) {
        MockEnvironment env = new MockEnvironment();
        if (value != null) {
            env.setProperty(EmailTransportPropertyValidator.PROPERTY, value);
        }
        validator.postProcessEnvironment(env, null);
    }

    @Test
    void unset_isAllowed() {
        assertThatCode(() -> run(null)).doesNotThrowAnyException();
    }

    @Test
    void ses_lowercase_isAllowed() {
        assertThatCode(() -> run("ses")).doesNotThrowAnyException();
    }

    @Test
    void ses_mixedCase_isAllowed() {
        assertThatCode(() -> run("SES")).doesNotThrowAnyException();
        assertThatCode(() -> run("Ses")).doesNotThrowAnyException();
    }

    @Test
    void log_lowercase_isAllowed() {
        assertThatCode(() -> run("log")).doesNotThrowAnyException();
    }

    @Test
    void log_mixedCase_isAllowed() {
        assertThatCode(() -> run("LOG")).doesNotThrowAnyException();
    }

    @Test
    void smtp_lowercase_isAllowed() {
        assertThatCode(() -> run("smtp")).doesNotThrowAnyException();
    }

    @Test
    void smtp_mixedCase_isAllowed() {
        assertThatCode(() -> run("SMTP")).doesNotThrowAnyException();
        assertThatCode(() -> run("Smtp")).doesNotThrowAnyException();
    }

    @Test
    void anythingElse_isRejectedWithValueInMessage() {
        assertThatThrownBy(() -> run("bogus"))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("app.email.transport")
            .hasMessageContaining("bogus");
    }

    @Test
    void trailingWhitespace_isRejected() {
        assertThatThrownBy(() -> run("ses "))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("app.email.transport");
    }

    @Test
    void emptyString_isRejected() {
        assertThatThrownBy(() -> run(""))
            .isInstanceOf(IllegalStateException.class);
    }
}
