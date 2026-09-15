package com.softropic.skillars.infrastructure.ses;

import com.softropic.skillars.infrastructure.email.EmailTransportRateLimitedException;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.github.resilience4j.core.exception.AcquirePermissionCancelledException;
import io.github.resilience4j.ratelimiter.RequestNotPermitted;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import org.junit.jupiter.api.AfterEach;
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

    // ------------------------------------------------------------------ skillars-deferred-111 AC5

    private ListAppender<ILoggingEvent> logAppender;
    private Logger limiterLogger;
    private Level originalLevel;

    @BeforeEach
    void setUpLogCapture() {
        limiterLogger = (Logger) LoggerFactory.getLogger(SesSendRateLimiter.class);
        originalLevel = limiterLogger.getLevel();
        limiterLogger.setLevel(Level.DEBUG);
        logAppender = new ListAppender<>();
        logAppender.start();
        limiterLogger.addAppender(logAppender);
    }

    @AfterEach
    void tearDownLogCapture() {
        limiterLogger.detachAppender(logAppender);
        limiterLogger.setLevel(originalLevel);
    }

    private long countAtLevel(Level level) {
        return logAppender.list.stream()
            .filter(event -> event.getLevel() == level)
            .filter(event -> event.getFormattedMessage().contains("rejecting send"))
            .count();
    }

    /**
     * skillars-deferred-111 AC5: two consecutive rejections must log exactly one WARN — the
     * {@code false -> true} transition — with the second rejection logged at DEBUG instead.
     * {@code // Mutation:} removing the {@code throttled.compareAndSet(false, true)} guard (always
     * logging WARN) turns this red.
     */
    @Test
    @DisplayName("AC5: two consecutive rejections log exactly one WARN (the transition), the second at DEBUG")
    void twoConsecutiveRejections_logOnlyOneWarn() {
        limiter.acquireOrThrow();
        limiter.acquireOrThrow();

        assertThatThrownBy(() -> limiter.acquireOrThrow()).isInstanceOf(EmailTransportRateLimitedException.class);
        assertThatThrownBy(() -> limiter.acquireOrThrow()).isInstanceOf(EmailTransportRateLimitedException.class);

        assertThat(countAtLevel(Level.WARN)).as("only the first rejection is a transition").isEqualTo(1);
        assertThat(countAtLevel(Level.DEBUG)).as("the second rejection, still throttled, logs at DEBUG").isEqualTo(1);
    }

    /**
     * The flag must clear once the limiter has been quiet (no rejections) for the cooldown, so a
     * later burst is a NEW transition and logs again — not permanently suppressed after the first
     * throttle episode.
     */
    @Test
    @DisplayName("AC5: a later rejection after a sustained-quiet period is a new transition and logs WARN again")
    void rejectionAfterASustainedQuietPeriod_logsWarnAgain() throws InterruptedException {
        limiter.acquireOrThrow();
        limiter.acquireOrThrow();
        assertThatThrownBy(() -> limiter.acquireOrThrow()).isInstanceOf(EmailTransportRateLimitedException.class);
        assertThat(countAtLevel(Level.WARN)).isEqualTo(1);

        // Outlast THROTTLE_LOG_COOLDOWN (3s) with successful acquires only, so the flag clears.
        for (int window = 0; window < 4; window++) {
            Thread.sleep(1100); // let the 1s window refresh
            limiter.acquireOrThrow();
        }
        limiter.acquireOrThrow();
        assertThatThrownBy(() -> limiter.acquireOrThrow()).isInstanceOf(EmailTransportRateLimitedException.class);

        assertThat(countAtLevel(Level.WARN))
            .as("a fresh throttle episode after a sustained-quiet period must log its own transition WARN")
            .isEqualTo(2);
    }

    /**
     * skillars-deferred-111 code review 2026-09-15 (H2). Before this fix, {@code throttled.set(false)}
     * ran unconditionally on every successful acquire — at a sandboxed {@code 1/s} rate, a sustained
     * burst grants roughly one permit per second, so that single success cleared the flag immediately
     * and the very next rejection re-triggered the WARN. Reproduced here with a {@code 1/s} limiter:
     * a rejection, then an isolated success one window later (well under the cooldown), then another
     * rejection — must still log only the first WARN.
     */
    @Test
    @DisplayName("AC5/H2: an isolated success mid-burst does not re-arm the WARN before the cooldown elapses")
    void isolatedSuccessDuringABurst_doesNotReArmWarnBeforeCooldown() throws InterruptedException {
        SesProperties props = new SesProperties();
        props.setMaxSendRatePerSecond(1);
        SesSendRateLimiter oneLimiter = new SesSendRateLimiter(props, meterRegistry);

        oneLimiter.acquireOrThrow();
        assertThatThrownBy(oneLimiter::acquireOrThrow).isInstanceOf(EmailTransportRateLimitedException.class);

        Thread.sleep(1100); // window refreshes — exactly one permit becomes available
        oneLimiter.acquireOrThrow(); // the isolated success H2 describes
        assertThatThrownBy(oneLimiter::acquireOrThrow).isInstanceOf(EmailTransportRateLimitedException.class);

        assertThat(countAtLevel(Level.WARN))
            .as("the isolated success must not re-arm the WARN inside the cooldown window")
            .isEqualTo(1);
    }
}
