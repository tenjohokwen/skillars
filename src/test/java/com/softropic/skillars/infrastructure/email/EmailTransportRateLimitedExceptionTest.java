package com.softropic.skillars.infrastructure.email;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story ses-1.3, code review 2026-09-12 — {@link EmailTransportRateLimitedException#isPresentIn}
 * backs two unrelated decisions (the circuit breaker's {@code ignoreException} predicate and
 * {@code MailManager}'s attempt accounting), and both see the exception only through
 * {@code MailManager.sendEmailSync}'s unconditional {@code RuntimeException} rewrap — so the cause
 * walk, its depth bound, and its cycle guard are all load-bearing.
 */
@DisplayName("EmailTransportRateLimitedException.isPresentIn")
class EmailTransportRateLimitedExceptionTest {

    @Test
    @DisplayName("matches the bare exception")
    void bareException_matches() {
        assertThat(EmailTransportRateLimitedException.isPresentIn(
            new EmailTransportRateLimitedException("rate limited"))).isTrue();
    }

    @Test
    @DisplayName("matches through the one RuntimeException wrapper MailManager always adds")
    void oneWrappingLevel_matches() {
        assertThat(EmailTransportRateLimitedException.isPresentIn(
            new RuntimeException("Unexpected retryable email error",
                new EmailTransportRateLimitedException("rate limited")))).isTrue();
    }

    @Test
    @DisplayName("matches through several layers, as the retry and circuit-breaker layers can add")
    void severalWrappingLevels_match() {
        Throwable deep = new IllegalStateException("outer",
            new RuntimeException("middle",
                new RuntimeException("inner",
                    new EmailTransportRateLimitedException("rate limited"))));

        assertThat(EmailTransportRateLimitedException.isPresentIn(deep)).isTrue();
    }

    @Test
    @DisplayName("does not match an unrelated transport failure, however deeply wrapped")
    void unrelatedException_doesNotMatch() {
        assertThat(EmailTransportRateLimitedException.isPresentIn(
            new RuntimeException("outer", new EmailTransportTransientException("timeout")))).isFalse();
        assertThat(EmailTransportRateLimitedException.isPresentIn(
            new EmailTransportPermanentException("bad address"))).isFalse();
    }

    @Test
    @DisplayName("a null throwable is not a rate-limit rejection — this is the success path at both call sites")
    void nullThrowable_doesNotMatch() {
        assertThat(EmailTransportRateLimitedException.isPresentIn(null)).isFalse();
    }

    @Test
    @DisplayName("a self-referential cause chain terminates instead of spinning")
    void selfReferentialCause_terminates() {
        // Not reachable via initCause (it rejects self-causation), but reachable via an override —
        // and a hang here would freeze the send path, so the guard is asserted rather than assumed.
        RuntimeException selfCaused = new RuntimeException("loop") {
            @Override
            public synchronized Throwable getCause() {
                return this;
            }
        };

        assertThat(EmailTransportRateLimitedException.isPresentIn(selfCaused)).isFalse();
    }
}
