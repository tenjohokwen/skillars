package com.softropic.skillars.platform.notification.infrastructure;

import com.softropic.skillars.platform.notification.contract.EmailTemplate;
import com.softropic.skillars.platform.notification.contract.Envelope;
import com.softropic.skillars.platform.notification.contract.Recipient;
import com.softropic.skillars.platform.notification.repo.EnvelopeEntity;
import com.softropic.skillars.platform.notification.repo.EnvelopeEntityRepository;
import com.softropic.skillars.platform.notification.service.MailManager;
import com.softropic.skillars.platform.notification.service.MailService;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.cloud.circuitbreaker.resilience4j.Resilience4JCircuitBreakerFactory;
import org.springframework.cloud.client.circuitbreaker.CircuitBreakerFactory;
import org.springframework.retry.support.RetryTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import jakarta.mail.MessagingException;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

public class MailManagerResilienceTest {

    private MailManager mailManager;

    @Mock
    private MailService mailService;

    @Mock
    private EnvelopeEntityRepository envelopeEntityRepository;

    @Mock
    private CircuitBreakerFactory<?, ?> circuitBreakerFactory;

    private Envelope envelope;
    private Recipient recipient;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);

        // REAL CircuitBreaker configuration for state transition testing
        CircuitBreakerRegistry circuitBreakerRegistry = CircuitBreakerRegistry.of(
                CircuitBreakerConfig.custom()
                        .slidingWindowSize(5)
                        .minimumNumberOfCalls(5)
                        .failureRateThreshold(50.0f)
                        .waitDurationInOpenState(Duration.ofSeconds(10))
                        .build()
        );

        Resilience4JCircuitBreakerFactory circuitBreakerFactory = new Resilience4JCircuitBreakerFactory(
                circuitBreakerRegistry,
                io.github.resilience4j.timelimiter.TimeLimiterRegistry.ofDefaults(),
                null
        );

        // Manual RetryTemplate configuration
        RetryTemplate retryTemplate = RetryTemplate.builder()
                .maxAttempts(3)
                .fixedBackoff(Duration.ofMillis(10))
                .build();

        mailManager = new MailManager(mailService, envelopeEntityRepository, circuitBreakerFactory, retryTemplate);

        recipient = new Recipient();
        recipient.setEmail("test@example.com");

        envelope = new Envelope(
                List.of(recipient),
                EmailTemplate.ACTIVATION,
                Instant.now().plus(Duration.ofDays(1)),
                Map.of(),
                UUID.randomUUID().toString()
        );
    }

    @Test
    void testSendEmail_Success() throws MessagingException {
        mailManager.sendEmailSync(envelope);

        verify(mailService, times(1)).sendEmailFromTemplate(any(), any(), any());
        verify(envelopeEntityRepository).save(any(EnvelopeEntity.class));
    }

    @Test
    void testSendEmail_RetryLogic() throws MessagingException {
        // Fail twice, succeed on third
        doThrow(new MessagingException("Fail"))
                .doThrow(new MessagingException("Fail"))
                .doNothing()
                .when(mailService).sendEmailFromTemplate(any(), any(), any());

        mailManager.sendEmailSync(envelope);

        verify(mailService, times(3)).sendEmailFromTemplate(any(), any(), any());
    }

    @Test
    void testSendEmail_CircuitBreakerOpens() throws MessagingException {
        // Using RuntimeException to ensure CB records it as failure by default
        doThrow(new RuntimeException("Crash"))
                .when(mailService).sendEmailFromTemplate(any(), any(), any());

        // Call 5 times to trip the breaker (slidingWindowSize=5)
        for (int i = 0; i < 5; i++) {
            mailManager.sendEmailSync(envelope);
        }

        // 6th call should be blocked by circuit breaker
        mailManager.sendEmailSync(envelope);

        // Total calls should be 5 calls * 3 retries = 15.
        // If 6th call made it, it would be 18.
        verify(mailService, times(15)).sendEmailFromTemplate(any(), any(), any());
    }
}
