package com.softropic.skillars.platform.notification.config;

import com.softropic.skillars.infrastructure.email.EmailTransportRateLimitedException;
import com.softropic.skillars.platform.notification.service.MailManager;
import com.softropic.skillars.platform.notification.service.MailService;
import com.softropic.skillars.platform.notification.repo.EnvelopeEntityRepository;

import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.timelimiter.TimeLimiterConfig;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.cloud.circuitbreaker.resilience4j.Resilience4JCircuitBreakerFactory;
import org.springframework.cloud.circuitbreaker.resilience4j.Resilience4JConfigBuilder;
import org.springframework.cloud.client.circuitbreaker.CircuitBreakerFactory;
import org.springframework.cloud.client.circuitbreaker.Customizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.retry.support.RetryTemplate;

import java.time.Duration;

/**
 * Story ses-1.2 AC2 Dev Notes: {@code @EnableConfigurationProperties({EmailProperties.class,
 * SmtpHealthProperties.class})} is dropped entirely — both configuration-properties classes moved
 * to {@code infrastructure.email.smtp}, which now registers its own SMTP config beans via its own
 * {@code @Configuration}. This class's {@code MailManager} bean gate ({@code enable.test.mail}),
 * {@code RetryTemplate} and circuit-breaker customizer are unaffected.
 */
@Configuration
public class ComponentConfig {

    @Bean
    @ConditionalOnProperty(name = "enable.test.mail", havingValue = "false", matchIfMissing = true)
    MailManager mailManager(final MailService mailService,
                            final EnvelopeEntityRepository envelopeEntityRepo,
                            final CircuitBreakerFactory<?, ?> circuitBreakerFactory,
                            final RetryTemplate retryTemplate) {
        return new MailManager(mailService, envelopeEntityRepo, circuitBreakerFactory, retryTemplate);
    }

    /**
     * Story ses-1.3 AC4: {@code .notRetryOn(EmailTransportRateLimitedException.class)} +
     * {@code .traversingCauses()} put the classifier in blacklist mode — that one exception type
     * classifies as not-retryable, every other type is unchanged (retryable) — so a rate-limit
     * rejection fails on its first attempt instead of burning the 1s-backoff retries against the
     * transaction/circuit-breaker budget (§6.17); {@code EmailRetryScheduler}'s next tick re-drives
     * it instead. {@code .traversingCauses()} is required because {@code MailManager.sendEmailSync}'s
     * {@code retryTemplate.execute(...)} rewraps whatever it catches in one level of {@code
     * RuntimeException} before rethrowing — the classifier must walk that wrapping to find the real
     * exception underneath.
     *
     * <p><strong>Do not</strong> call {@code .maxAttempts(3)} together with {@code
     * .customPolicy(new SimpleRetryPolicy(...))} on this builder — both assert the builder's internal
     * {@code baseRetryPolicy} field is still {@code null} before setting it, so calling both throws
     * {@code IllegalArgumentException} at context startup, in every environment (verified via {@code
     * javap} on the pinned {@code spring-retry:2.0.13}). The builder-native form below avoids the
     * conflict: {@code .maxAttempts(3)} populates {@code baseRetryPolicy}, while
     * {@code .notRetryOn(...)}/{@code .traversingCauses()} populate a separate classifier that {@code
     * build()} composes alongside it via {@code CompositeRetryPolicy} — no conflicting field write.
     */
    @Bean
    public RetryTemplate retryTemplate() {
        return RetryTemplate.builder()
                .maxAttempts(3)
                .notRetryOn(EmailTransportRateLimitedException.class)
                .traversingCauses()
                .fixedBackoff(Duration.ofSeconds(1))
                .build();
    }

    /**
     * Story ses-1.3 code review 2026-09-12: a rate-limit rejection must not trip the breaker.
     *
     * <p>{@code SesSendRateLimiter} throws inside the supplier {@code MailManager.sendEmailSync}
     * hands to {@code circuitBreaker.run(...)}, so without this every rejection counts as one
     * failure. With {@code slidingWindowSize(5)}/{@code minimumNumberOfCalls(5)}/
     * {@code failureRateThreshold(50%)}, three rejections open {@code emailService} for 5s and
     * every unrelated envelope in that window — password resets, activation mail — fails with
     * {@code CallNotPermittedException}. That is a self-inflicted outage caused by the app's own
     * throttle, and AC4's {@code .notRetryOn(...)} made it materially more likely: the rejection now
     * propagates out of the breaker call on the first attempt instead of usually being absorbed by
     * the 1s-backoff retry (the limiter's refresh period is also 1s).
     *
     * <p>{@code ignoreException(Predicate)} rather than {@code ignoreExceptions(Class...)}: the
     * latter is a plain {@code instanceof} test, and what actually reaches the breaker is
     * {@code MailManager}'s unconditional {@code new RuntimeException(..., cause)} rewrap, never the
     * bare exception. {@link EmailTransportRateLimitedException#isPresentIn} does the cause walk.
     */
    @Bean
    public Customizer<Resilience4JCircuitBreakerFactory> defaultCustomizer() {
        return factory -> factory.configureDefault(id -> new Resilience4JConfigBuilder(id)
                .timeLimiterConfig(TimeLimiterConfig.custom().timeoutDuration(Duration.ofSeconds(10)).build())
                .circuitBreakerConfig(CircuitBreakerConfig.custom()
                        .slidingWindowSize(5)
                        .minimumNumberOfCalls(5)
                        .failureRateThreshold(50.0f)
                        .waitDurationInOpenState(Duration.ofSeconds(5))
                        .ignoreException(EmailTransportRateLimitedException::isPresentIn)
                        .build())
                .build());
    }
}
