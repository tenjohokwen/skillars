package com.softropic.skillars.infrastructure.ses;

import com.softropic.skillars.infrastructure.email.EmailTransportRateLimitedException;

import io.github.resilience4j.core.exception.AcquirePermissionCancelledException;
import io.github.resilience4j.ratelimiter.RequestNotPermitted;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Story ses-1.3 AC6 — {@link SesSendRateLimiter} fails fast, never blocks, and the thrown/observed
 * types are exactly the transport-neutral ones this story introduces.
 *
 * <p>Uses a deliberately small {@code maxSendRatePerSecond=2}, not the real {@code
 * app.ses.max-send-rate-per-second} default (10). The production-hardcoded 1-second refresh window
 * is irrelevant to determinism here — every acquire in this test is a synchronous, in-memory call
 * completing in microseconds, nowhere near a window boundary — but a small, explicit limit still
 * keeps the test's intent ("the 3rd acquire is rejected") readable rather than tied to the real
 * default.
 */
@DisplayName("SES Send Rate Limiter")
class SesSendRateLimiterTest {

    private SimpleMeterRegistry meterRegistry;
    private SesSendRateLimiter limiter;

    @BeforeEach
    void setUp() {
        SesProperties props = new SesProperties();
        props.setMaxSendRatePerSecond(2);
        meterRegistry = new SimpleMeterRegistry();
        limiter = new SesSendRateLimiter(props, meterRegistry);
    }

    @Test
    @DisplayName("the first limitForPeriod acquires succeed")
    void firstAcquiresSucceed() {
        assertThatCode(() -> limiter.acquireOrThrow()).doesNotThrowAnyException();
        assertThatCode(() -> limiter.acquireOrThrow()).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("the acquire beyond limitForPeriod is rejected, fails fast, and never blocks")
    void acquireBeyondLimit_rejectsFastWithoutBlocking() {
        limiter.acquireOrThrow();
        limiter.acquireOrThrow();

        long start = System.nanoTime();
        assertThatThrownBy(() -> limiter.acquireOrThrow())
            .isInstanceOf(EmailTransportRateLimitedException.class)
            .isNotInstanceOf(RequestNotPermitted.class)
            .isNotInstanceOf(AcquirePermissionCancelledException.class);
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        // Generous on purpose: this bound only has to stay clear of CI jitter. It is NOT what proves
        // non-blocking — a 150ms timeoutDuration would sail under it. timeoutDurationIsZero() below
        // carries that guarantee structurally; this assertion just catches an outright block.
        assertThat(elapsedMs).as("a non-blocking rejection must not measurably wait").isLessThan(200);
    }

    /**
     * The structural counterpart to the wall-clock bound above, and the assertion that actually fails
     * if someone reintroduces a waiting acquire: {@code timeoutDuration(Duration.ZERO)} is the entire
     * mechanism behind §6.5's "never blocks inside sendEmailSync's transaction."
     */
    @Test
    @DisplayName("the limiter is configured with timeoutDuration=ZERO, a 1s window, and the configured rate")
    void timeoutDurationIsZero() {
        assertThat(limiter.config().getTimeoutDuration())
            .as("a non-zero timeoutDuration would make acquire block inside the open transaction")
            .isZero();
        assertThat(limiter.config().getLimitForPeriod()).isEqualTo(2);
        assertThat(limiter.config().getLimitRefreshPeriod()).isEqualTo(Duration.ofSeconds(1));
    }

    @Test
    @DisplayName("a rejection increments the mail.ses.rate_limiter.rejected counter")
    void rejection_incrementsRejectedCounter() {
        limiter.acquireOrThrow();
        limiter.acquireOrThrow();

        assertThatThrownBy(() -> limiter.acquireOrThrow())
            .isInstanceOf(EmailTransportRateLimitedException.class);

        assertThat(meterRegistry.get("mail.ses.rate_limiter.rejected").counter().count()).isEqualTo(1.0);
    }
}
