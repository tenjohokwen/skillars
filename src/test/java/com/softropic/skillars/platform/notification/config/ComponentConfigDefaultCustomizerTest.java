package com.softropic.skillars.platform.notification.config;

import com.softropic.skillars.infrastructure.email.EmailTransportPermanentException;
import com.softropic.skillars.infrastructure.email.EmailTransportRateLimitedException;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.timelimiter.TimeLimiterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.circuitbreaker.resilience4j.Resilience4JCircuitBreakerFactory;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * skillars-deferred-110 AC4b, code review 2026-09-14 (patch) — proves the <strong>real</strong>
 * {@link ComponentConfig#defaultCustomizer()} bean's {@code ignoreException} predicate, not a
 * hand-rolled {@code CircuitBreakerConfig} that never consumes it (the way {@code
 * MailManagerResilienceTest} builds its own breaker config inline, independent of this class).
 * Deleting the {@code EmailTransportPermanentException.isPresentIn(throwable)} clause from {@code
 * defaultCustomizer()} would leave every other test in the suite green — this is the test that
 * would go red.
 *
 * <p>Uses the 2-arg {@code CircuitBreaker.run(Supplier, Function&lt;Throwable, T&gt; fallback)}
 * overload, matching how {@code MailManager.sendEmailSync} actually calls it — the 1-arg default
 * method has no fallback registered and throws {@code NoFallbackAvailableException} on any
 * failure, telling you nothing about whether the breaker itself is open.
 */
@DisplayName("ComponentConfig.defaultCustomizer()")
class ComponentConfigDefaultCustomizerTest {

    /** Rethrows whatever the breaker (or the supplier) threw, exactly like MailManager's own fallback. */
    private static final java.util.function.Function<Throwable, Object> RETHROW = throwable -> {
        if (throwable instanceof RuntimeException runtimeException) {
            throw runtimeException;
        }
        throw new RuntimeException(throwable);
    };

    private Resilience4JCircuitBreakerFactory circuitBreakerFactory;

    @BeforeEach
    void setUp() {
        circuitBreakerFactory = new Resilience4JCircuitBreakerFactory(
            CircuitBreakerRegistry.ofDefaults(), TimeLimiterRegistry.ofDefaults(), null);
        new ComponentConfig().defaultCustomizer().customize(circuitBreakerFactory);
    }

    /**
     * slidingWindowSize=5/minimumNumberOfCalls=5/failureRateThreshold=50%: 6 ignored failures must
     * never open the breaker — every call still reaches the (still-throwing) supplier rather than
     * being short-circuited with {@link CallNotPermittedException}.
     */
    @Test
    @DisplayName("an EmailTransportPermanentException never trips the breaker, however many times it recurs")
    void permanentException_neverTripsTheBreaker() {
        var breaker = circuitBreakerFactory.create("permanent-exception-test");

        for (int i = 0; i < 6; i++) {
            assertThatThrownBy(() -> breaker.run(() -> {
                throw new RuntimeException("wrapped", new EmailTransportPermanentException("bad address"));
            }, RETHROW))
                .as("call %d must reach the supplier, not be short-circuited by an OPEN breaker", i + 1)
                .isInstanceOf(RuntimeException.class)
                .hasCauseInstanceOf(EmailTransportPermanentException.class);
            // Mutation: delete the EmailTransportPermanentException clause from defaultCustomizer()
            // → the breaker opens after call 3, and this loop's 4th+ iteration instead throws
            // CallNotPermittedException (whose cause is null, not an EmailTransportPermanentException) → RED.
        }
    }

    @Test
    @DisplayName("an EmailTransportRateLimitedException never trips the breaker (regression guard, ses-1.3)")
    void rateLimitedException_neverTripsTheBreaker() {
        var breaker = circuitBreakerFactory.create("rate-limited-exception-test");

        for (int i = 0; i < 6; i++) {
            assertThatThrownBy(() -> breaker.run(() -> {
                throw new RuntimeException("wrapped", new EmailTransportRateLimitedException("rate limited"));
            }, RETHROW))
                .isInstanceOf(RuntimeException.class)
                .hasCauseInstanceOf(EmailTransportRateLimitedException.class);
        }
    }

    /**
     * The counterpart assertion that makes the two tests above non-vacuous: an ordinary,
     * non-ignored failure DOES trip the breaker at the configured threshold — proving the predicate
     * is doing real, selective work, not merely "the breaker never opens".
     */
    @Test
    @DisplayName("an ordinary (non-ignored) exception still trips the breaker at the configured threshold")
    void ordinaryException_stillTripsTheBreaker() {
        var breaker = circuitBreakerFactory.create("ordinary-exception-test");

        // 5 calls: minimumNumberOfCalls is reached, failureRateThreshold(50%) is exceeded by 100%
        // failures, so the breaker transitions to OPEN.
        for (int i = 0; i < 5; i++) {
            assertThatThrownBy(() -> breaker.run(() -> {
                throw new RuntimeException("ordinary failure");
            }, RETHROW)).isInstanceOf(RuntimeException.class);
        }

        assertThatThrownBy(() -> breaker.run(() -> "should never run", RETHROW))
            .as("the 6th call must be short-circuited by the now-OPEN breaker")
            .isInstanceOf(CallNotPermittedException.class);
    }
}
