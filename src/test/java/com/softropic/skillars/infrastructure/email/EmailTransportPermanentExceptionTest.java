package com.softropic.skillars.infrastructure.email;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * skillars-deferred-110 AC4b — {@link EmailTransportPermanentException#isPresentIn} mirrors {@link
 * EmailTransportRateLimitedException#isPresentIn}: it backs {@code ComponentConfig}'s circuit-breaker
 * {@code ignoreException} predicate, seeing the exception only through {@code
 * MailManager.sendEmailSync}'s unconditional {@code RuntimeException} rewrap — so the cause walk, its
 * depth bound, and its cycle guard are all load-bearing here too.
 */
@DisplayName("EmailTransportPermanentException.isPresentIn")
class EmailTransportPermanentExceptionTest {

    @Test
    @DisplayName("matches the bare exception")
    void bareException_matches() {
        assertThat(EmailTransportPermanentException.isPresentIn(
            new EmailTransportPermanentException("bad address"))).isTrue();
    }

    @Test
    @DisplayName("matches through the one RuntimeException wrapper MailManager always adds")
    void oneWrappingLevel_matches() {
        assertThat(EmailTransportPermanentException.isPresentIn(
            new RuntimeException("Unexpected non-repairable email error",
                new EmailTransportPermanentException("bad address")))).isTrue();
    }

    @Test
    @DisplayName("matches through several layers, as the retry and circuit-breaker layers can add")
    void severalWrappingLevels_match() {
        Throwable deep = new IllegalStateException("outer",
            new RuntimeException("middle",
                new RuntimeException("inner",
                    new EmailTransportPermanentException("bad address"))));

        assertThat(EmailTransportPermanentException.isPresentIn(deep)).isTrue();
    }

    @Test
    @DisplayName("does not match an unrelated transport failure, however deeply wrapped")
    void unrelatedException_doesNotMatch() {
        assertThat(EmailTransportPermanentException.isPresentIn(
            new RuntimeException("outer", new EmailTransportTransientException("timeout")))).isFalse();
        assertThat(EmailTransportPermanentException.isPresentIn(
            new EmailTransportRateLimitedException("rate limited"))).isFalse();
    }

    @Test
    @DisplayName("a null throwable is not a permanent failure — this is the success path at both call sites")
    void nullThrowable_doesNotMatch() {
        assertThat(EmailTransportPermanentException.isPresentIn(null)).isFalse();
    }

    @Test
    @DisplayName("a self-referential cause chain terminates instead of spinning")
    void selfReferentialCause_terminates() {
        RuntimeException selfCaused = new RuntimeException("loop") {
            @Override
            public synchronized Throwable getCause() {
                return this;
            }
        };

        assertThat(EmailTransportPermanentException.isPresentIn(selfCaused)).isFalse();
    }
}
