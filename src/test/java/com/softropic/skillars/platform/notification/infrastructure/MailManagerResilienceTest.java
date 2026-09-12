package com.softropic.skillars.platform.notification.infrastructure;

import com.softropic.skillars.infrastructure.email.EmailTransportPermanentException;
import com.softropic.skillars.infrastructure.email.EmailTransportRateLimitedException;
import com.softropic.skillars.infrastructure.email.EmailTransportTransientException;
import com.softropic.skillars.platform.notification.contract.EmailTemplate;
import com.softropic.skillars.platform.notification.contract.Envelope;
import com.softropic.skillars.platform.notification.contract.Recipient;
import com.softropic.skillars.platform.notification.repo.EnvelopeEntity;
import com.softropic.skillars.platform.notification.repo.EnvelopeEntityRepository;
import com.softropic.skillars.platform.notification.service.MailManager;
import com.softropic.skillars.platform.notification.service.MailService;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
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

import static org.assertj.core.api.Assertions.assertThat;
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
    void testSendEmail_Success() {
        mailManager.sendEmailSync(envelope);

        verify(mailService, times(1)).sendEmailFromTemplate(any(), any(), any());
        verify(envelopeEntityRepository).save(any(EnvelopeEntity.class));
    }

    @Test
    void testSendEmail_RetryLogic() {
        // Fail twice, succeed on third
        doThrow(new EmailTransportTransientException("Fail"))
                .doThrow(new EmailTransportTransientException("Fail"))
                .doNothing()
                .when(mailService).sendEmailFromTemplate(any(), any(), any());

        mailManager.sendEmailSync(envelope);

        verify(mailService, times(3)).sendEmailFromTemplate(any(), any(), any());
    }

    @Test
    void testSendEmail_CircuitBreakerOpens() {
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

    @Test
    void isRetryable_permanentTransportException_persistsRetryFalse() {
        // Story ses-1.2 AC4: the transport (SmtpErrorClassifier/SesErrorClassifier) already did the
        // jakarta.mail/spring-mail classification — MailService now only ever throws the
        // transport-neutral taxonomy, unwrapped, directly.
        doThrow(new EmailTransportPermanentException("bad template"))
            .when(mailService).sendEmailFromTemplate(any(), any(), any());

        mailManager.sendEmailSync(envelope);

        ArgumentCaptor<EnvelopeEntity> captor = ArgumentCaptor.forClass(EnvelopeEntity.class);
        verify(envelopeEntityRepository).save(captor.capture());
        // The EmailRetryScheduler polls on this flag — a structurally impossible parse error must
        // not be picked up again, or it would fail forever.
        assertThat(captor.getValue().isRetry()).isFalse();
    }

    /**
     * Story ses-1.3, code review 2026-09-12 (decision D3). A rate-limit rejection never reached the
     * transport — the limiter refused it locally — so it must not spend one of the six
     * {@code EmailRetryScheduler} attempts. Without this, a burst that the limiter exists to *defer*
     * instead exhausts the attempt budget and lands perfectly deliverable envelopes in
     * {@code ATTEMPTS_EXHAUSTED} with {@code retry=false}, never to be fetched again.
     *
     * <p>Contrast with the transient case directly below, which does still consume an attempt: that
     * one genuinely failed at the transport.
     */
    @Test
    void rateLimitRejection_persistsRetryTrueWithoutConsumingAnAttempt() {
        doThrow(new EmailTransportRateLimitedException("SES send rate limit exceeded: 10 sends/second"))
            .when(mailService).sendEmailFromTemplate(any(), any(), any());

        mailManager.sendEmailSync(envelope);

        ArgumentCaptor<EnvelopeEntity> captor = ArgumentCaptor.forClass(EnvelopeEntity.class);
        verify(envelopeEntityRepository).save(captor.capture());
        assertThat(captor.getValue().isRetry()).isTrue();
        assertThat(captor.getValue().getAttempts())
            .as("a rate-limit rejection must not consume a delivery attempt")
            .isZero();
    }

    /**
     * The counterpart assertion that makes the case above non-vacuous: an ordinary transient failure
     * DOES consume an attempt, so the zero above is the rate-limit branch and not simply how every
     * failure is counted.
     */
    @Test
    void transientTransportException_doesConsumeAnAttempt() {
        doThrow(new EmailTransportTransientException("Connection timed out"))
            .when(mailService).sendEmailFromTemplate(any(), any(), any());

        mailManager.sendEmailSync(envelope);

        ArgumentCaptor<EnvelopeEntity> captor = ArgumentCaptor.forClass(EnvelopeEntity.class);
        verify(envelopeEntityRepository).save(captor.capture());
        assertThat(captor.getValue().getAttempts()).isEqualTo(1);
    }

    @Test
    void isRetryable_transientTransportException_persistsRetryTrue() {
        doThrow(new EmailTransportTransientException("Connection timed out"))
            .when(mailService).sendEmailFromTemplate(any(), any(), any());

        mailManager.sendEmailSync(envelope);

        ArgumentCaptor<EnvelopeEntity> captor = ArgumentCaptor.forClass(EnvelopeEntity.class);
        verify(envelopeEntityRepository).save(captor.capture());
        assertThat(captor.getValue().isRetry()).isTrue();
    }
}
