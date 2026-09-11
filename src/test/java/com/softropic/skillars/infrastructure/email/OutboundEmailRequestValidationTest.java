package com.softropic.skillars.infrastructure.email;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Story ses-1.1 AC1 — {@link OutboundEmailRequest}'s compact constructor is the single validation
 * point for presence (null/blank) only. Every null/blank combination named in the AC, plus
 * html-only and text-only both accepted.
 */
class OutboundEmailRequestValidationTest {

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   "})
    void blankToAddress_isRejected(String toAddress) {
        assertThatThrownBy(() -> new OutboundEmailRequest(toAddress, "subject", "<html/>", null, "cid"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("toAddress");
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   "})
    void blankSubject_isRejected(String subject) {
        assertThatThrownBy(() -> new OutboundEmailRequest("to@x.com", subject, "<html/>", null, "cid"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("subject");
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   "})
    void blankCorrelationId_isRejected(String correlationId) {
        assertThatThrownBy(() -> new OutboundEmailRequest("to@x.com", "subject", "<html/>", null, correlationId))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("correlationId");
    }

    @Test
    void bothBodiesBlank_isRejected() {
        assertThatThrownBy(() -> new OutboundEmailRequest("to@x.com", "subject", null, null, "cid"))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new OutboundEmailRequest("to@x.com", "subject", "  ", "", "cid"))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void htmlOnly_isAccepted() {
        assertThatCode(() -> new OutboundEmailRequest("to@x.com", "subject", "<html/>", null, "cid"))
            .doesNotThrowAnyException();
    }

    @Test
    void textOnly_isAccepted() {
        assertThatCode(() -> new OutboundEmailRequest("to@x.com", "subject", null, "plain text", "cid"))
            .doesNotThrowAnyException();
    }

    @Test
    void bothBodiesPresent_isAccepted() {
        assertThatCode(() -> new OutboundEmailRequest("to@x.com", "subject", "<html/>", "plain text", "cid"))
            .doesNotThrowAnyException();
    }
}
