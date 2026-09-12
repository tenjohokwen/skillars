package com.softropic.skillars.infrastructure.ses;

import com.softropic.skillars.infrastructure.email.EmailTransportRateLimitedException;

import io.github.resilience4j.ratelimiter.RateLimiter;
import io.github.resilience4j.ratelimiter.RateLimiterConfig;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Non-blocking send-rate limiter guarding {@link SesEmailSender}'s SES API call — story ses-1.3 AC3.
 *
 * <p>Built from {@code app.ses.max-send-rate-per-second} (already validated positive at startup by
 * {@link SesPropertiesValidator}) with {@code timeoutDuration(Duration.ZERO)} — the whole point
 * (§6.5): every {@link #acquireOrThrow()} call is non-blocking and never waits inside the caller,
 * i.e. never adds hold time to {@code sendEmailSync}'s open transaction.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "app.email.transport", havingValue = "ses")
public class SesSendRateLimiter {

    private static final String REJECTED_COUNTER_NAME = "mail.ses.rate_limiter.rejected";

    private final SesProperties props;
    private final MeterRegistry meterRegistry;
    private volatile RateLimiter rateLimiter;

    public SesSendRateLimiter(SesProperties props, MeterRegistry meterRegistry) {
        this.props = props;
        this.meterRegistry = meterRegistry;
    }

    /**
     * Acquires one permit and returns immediately. On rejection: logs at WARN, increments {@code
     * mail.ses.rate_limiter.rejected}, and throws {@link EmailTransportRateLimitedException}.
     *
     * <p>Calls the interface's no-arg {@link RateLimiter#acquirePermission()} directly — deliberately
     * <strong>not</strong> the {@link RateLimiter#waitForPermission(RateLimiter)} static helper, which
     * additionally checks the calling thread's interrupt flag and throws a second, distinct
     * resilience4j type ({@code AcquirePermissionCancelledException}, unchecked) that is not {@code
     * RequestNotPermitted} and would slip past every catch clause this codebase has for a rate-limit
     * rejection, reaching {@code MailManager.isRetryable} unclassified. {@code acquirePermission()}
     * has no such interrupt-checking side path, so there is nothing to leak.
     */
    public void acquireOrThrow() {
        if (!rateLimiter().acquirePermission()) {
            log.warn("SES send rate limit exceeded (limitForPeriod={}/s); rejecting send",
                props.getMaxSendRatePerSecond());
            Counter.builder(REJECTED_COUNTER_NAME).register(meterRegistry).increment();
            throw new EmailTransportRateLimitedException(
                "SES send rate limit exceeded: " + props.getMaxSendRatePerSecond() + " sends/second");
        }
    }

    /**
     * Test seam: the configuration actually handed to resilience4j. Exposed because the
     * "never blocks" guarantee lives in {@code timeoutDuration(ZERO)}, and a wall-clock assertion
     * cannot distinguish a non-blocking rejection from a short blocking one — switching this to
     * {@code ofMillis(150)} would keep every timing-based test green while reintroducing exactly the
     * in-transaction hold §6.5 forbids (code review 2026-09-12).
     */
    RateLimiterConfig config() {
        return rateLimiter().getRateLimiterConfig();
    }

    /**
     * Built lazily on first {@link #acquireOrThrow()} call, not at construction. {@link
     * RateLimiterConfig.Builder#limitForPeriod(int)} throws its own, generically-worded {@code
     * IllegalArgumentException} for a non-positive value — {@link SesPropertiesValidator} already
     * owns the friendly, actionable message for exactly that misconfiguration via its own {@code
     * @PostConstruct}, but {@code @PostConstruct} ordering between two independent {@code
     * @Component}s is not guaranteed. Deferring construction to first use means that validator's
     * startup-time check has already run — and failed fast — well before this class ever builds a
     * {@link RateLimiterConfig}, avoiding the race rather than depending on bean-creation order.
     */
    private RateLimiter rateLimiter() {
        RateLimiter local = rateLimiter;
        if (local == null) {
            synchronized (this) {
                local = rateLimiter;
                if (local == null) {
                    RateLimiterConfig config = RateLimiterConfig.custom()
                        .limitForPeriod(props.getMaxSendRatePerSecond())
                        .limitRefreshPeriod(Duration.ofSeconds(1))
                        .timeoutDuration(Duration.ZERO)
                        .build();
                    local = RateLimiter.of("ses-send", config);
                    rateLimiter = local;
                }
            }
        }
        return local;
    }
}
