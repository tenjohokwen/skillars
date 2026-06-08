package com.softropic.skillars.platform.notification.config;

import com.softropic.skillars.platform.notification.contract.EmailProperties;
import com.softropic.skillars.platform.notification.service.MailManager;
import com.softropic.skillars.platform.notification.service.MailService;
import com.softropic.skillars.platform.notification.repo.EnvelopeEntityRepository;

import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.timelimiter.TimeLimiterConfig;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.cloud.circuitbreaker.resilience4j.Resilience4JCircuitBreakerFactory;
import org.springframework.cloud.circuitbreaker.resilience4j.Resilience4JConfigBuilder;
import org.springframework.cloud.client.circuitbreaker.CircuitBreakerFactory;
import org.springframework.cloud.client.circuitbreaker.Customizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.retry.support.RetryTemplate;

import java.time.Duration;

@Configuration
@EnableConfigurationProperties(EmailProperties.class)
public class ComponentConfig {

    @Bean
    @ConditionalOnProperty(name = "enable.test.mail", havingValue = "false", matchIfMissing = true)
    MailManager mailManager(final MailService mailService,
                            final EnvelopeEntityRepository envelopeEntityRepo,
                            final CircuitBreakerFactory<?, ?> circuitBreakerFactory,
                            final RetryTemplate retryTemplate) {
        return new MailManager(mailService, envelopeEntityRepo, circuitBreakerFactory, retryTemplate);
    }

    @Bean
    public RetryTemplate retryTemplate() {
        return RetryTemplate.builder()
                .maxAttempts(3)
                .fixedBackoff(Duration.ofSeconds(1))
                .build();
    }

    @Bean
    public Customizer<Resilience4JCircuitBreakerFactory> defaultCustomizer() {
        return factory -> factory.configureDefault(id -> new Resilience4JConfigBuilder(id)
                .timeLimiterConfig(TimeLimiterConfig.custom().timeoutDuration(Duration.ofSeconds(10)).build())
                .circuitBreakerConfig(CircuitBreakerConfig.custom()
                        .slidingWindowSize(5)
                        .minimumNumberOfCalls(5)
                        .failureRateThreshold(50.0f)
                        .waitDurationInOpenState(Duration.ofSeconds(5))
                        .build())
                .build());
    }
}
