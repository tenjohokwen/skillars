package com.softropic.skillars.infrastructure.email;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Story ses-1.1 AC3 — {@link EmailTransportPropertyValidator} allows an absent value (falls through
 * to the base default), accepts {@code ses}/{@code log} case-insensitively, rejects a present
 * {@code smtp} with the Phase-2-specific message, and rejects anything else naming the offending
 * value.
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
    void smtp_isRejectedWithPhase2Message() {
        assertThatThrownBy(() -> run("smtp"))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("app.email.transport=smtp is not implemented until Phase 2")
            .hasMessageContaining("'log' or 'ses'");
    }

    @Test
    void smtp_mixedCase_isRejectedWithPhase2Message() {
        assertThatThrownBy(() -> run("SMTP"))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("Phase 2");
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
