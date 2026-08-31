package com.softropic.skillars.infrastructure.ses;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Story skillars-deferred-88 AC9 — the {@link SesEnabledPropertyValidator} accepts exactly what
 * {@code @ConditionalOnProperty(name = "app.ses.enabled", havingValue = "true")} accepts
 * (case-insensitive {@code true}/{@code false}, no whitespace tolerance) and rejects everything else
 * with a message that names the offending value.
 */
class SesEnabledPropertyValidatorTest {

    private final SesEnabledPropertyValidator validator = new SesEnabledPropertyValidator();

    private void run(String value) {
        MockEnvironment env = new MockEnvironment();
        if (value != null) {
            env.setProperty(SesEnabledPropertyValidator.PROPERTY, value);
        }
        validator.postProcessEnvironment(env, null);
    }

    @Test
    void unset_isAllowed() {
        assertThatCode(() -> run(null)).doesNotThrowAnyException();
    }

    @Test
    void true_lowercase_isAllowed() {
        assertThatCode(() -> run("true")).doesNotThrowAnyException();
    }

    @Test
    void false_lowercase_isAllowed() {
        assertThatCode(() -> run("false")).doesNotThrowAnyException();
    }

    @Test
    void true_mixedCase_isAllowed() {
        assertThatCode(() -> run("TRUE")).doesNotThrowAnyException();
        assertThatCode(() -> run("True")).doesNotThrowAnyException();
    }

    @Test
    void yes_isRejectedWithValueInMessage() {
        assertThatThrownBy(() -> run("yes"))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("app.ses.enabled")
            .hasMessageContaining("yes");
    }

    @Test
    void trailingWhitespace_isRejected() {
        // @ConditionalOnProperty would also reject "true " (equalsIgnoreCase does not trim), so a
        // strict validator keeps the "accepted here <=> beans wire" invariant.
        assertThatThrownBy(() -> run("true "))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("app.ses.enabled");
    }

    @Test
    void emptyString_isRejected() {
        assertThatThrownBy(() -> run(""))
            .isInstanceOf(IllegalStateException.class);
    }
}
