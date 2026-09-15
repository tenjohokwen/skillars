package com.softropic.skillars.infrastructure.email.smtp;

import com.softropic.skillars.infrastructure.email.OutboundEmailSender;
import com.softropic.skillars.infrastructure.ses.SesEmailSender;
import com.softropic.skillars.infrastructure.ses.SesErrorClassifier;
import com.softropic.skillars.infrastructure.ses.SesProperties;
import com.softropic.skillars.infrastructure.ses.SesSendRateLimiter;
import com.softropic.skillars.infrastructure.email.EmailAddressParser;
import com.softropic.skillars.infrastructure.email.EmailTransportProperties;
import com.softropic.skillars.platform.notification.contract.EmailTemplate;
import com.softropic.skillars.platform.notification.contract.Envelope;
import com.softropic.skillars.platform.notification.contract.Recipient;
import com.softropic.skillars.platform.notification.repo.EnvelopeEntity;
import com.softropic.skillars.platform.notification.repo.EnvelopeEntityRepository;
import com.softropic.skillars.platform.notification.service.EmailContentRenderer;
import com.softropic.skillars.platform.notification.service.MailManager;
import com.softropic.skillars.platform.notification.service.MailMetrics;
import com.softropic.skillars.platform.notification.service.MailService;

import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.timelimiter.TimeLimiterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.cloud.circuitbreaker.resilience4j.Resilience4JCircuitBreakerFactory;
import org.springframework.cloud.client.circuitbreaker.CircuitBreakerFactory;
import org.springframework.retry.support.RetryTemplate;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import software.amazon.awssdk.services.sesv2.SesV2Client;
import software.amazon.awssdk.services.sesv2.model.BadRequestException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;

/**
 * skillars-deferred-111 AC3 — {@code requirements/ses-email-consolidation.md} §7.2 item 13's
 * adapter-wrap-depth guard had no test driving a real adapter: {@code MailManagerResilienceTest}
 * only ever hand-constructs {@link com.softropic.skillars.infrastructure.email.EmailTransportException}
 * at whatever depth the test author chooses, so a real adapter adding one extra wrapping layer "for
 * context" would silently push the real exception type outside {@code MailManager.isRetryable}'s
 * fixed two-level cause-chain walk and every permanent failure would start retrying six times,
 * without any test in the suite noticing.
 *
 * <p>Each test here wires the REAL adapter, REAL classifier, and a REAL {@link MailManager} (same
 * real {@code CircuitBreakerFactory}/{@code RetryTemplate} construction style as {@code
 * MailManagerResilienceTest}) end to end, with only the outermost transport boundary faked/mocked —
 * a socket-level fake SMTP server for the SMTP case, a mocked {@link SesV2Client} throwing a real
 * SDK exception type for the SES case — and asserts the persisted {@code EnvelopeEntity.isRetry()}
 * outcome, which is what {@code MailManager.isRetryable}'s cause-chain walk actually controls.
 *
 * <p>{@code // Mutation:} wrapping either adapter's thrown exception in one extra layer (e.g.
 * {@code SmtpEmailSender} rethrowing {@code errorClassifier.classify(ex)} inside a new {@code
 * RuntimeException}, or {@code SesEmailSender} doing the same) must turn the corresponding test in
 * this class red — verified during development, then reverted.
 */
class AdapterWrapDepthTest {

    /**
     * A single real attempt only: {@link FakeSmtpServer} accepts exactly one connection, and a
     * retried {@code JavaMailSenderImpl.send(...)} would open a second one that hangs forever
     * waiting on an {@code accept()} nothing is calling anymore. This AC is about classification
     * fidelity, not retry-count behaviour (already covered elsewhere), so one attempt is enough.
     */
    private static RetryTemplate singleAttemptRetryTemplate() {
        return RetryTemplate.builder().maxAttempts(1).fixedBackoff(Duration.ofMillis(1)).build();
    }

    private static CircuitBreakerFactory<?, ?> freshCircuitBreakerFactory() {
        CircuitBreakerRegistry registry = CircuitBreakerRegistry.of(CircuitBreakerConfig.custom()
            .slidingWindowSize(5)
            .minimumNumberOfCalls(5)
            .failureRateThreshold(50.0f)
            .waitDurationInOpenState(Duration.ofSeconds(10))
            .build());
        return new Resilience4JCircuitBreakerFactory(registry, TimeLimiterRegistry.ofDefaults(), null);
    }

    private static Envelope envelopeFor(String toAddress) {
        Recipient recipient = new Recipient();
        recipient.setEmail(toAddress);
        recipient.setLangKey("en");
        return new Envelope(
            List.of(recipient),
            EmailTemplate.NONE,
            Instant.now().plus(Duration.ofDays(1)),
            Map.of("subject", "wrap-depth subject", "body", "wrap-depth body"),
            UUID.randomUUID().toString());
    }

    private static MailService mailService(OutboundEmailSender outboundEmailSender) {
        // EmailTemplate.NONE short-circuits EmailContentRenderer.render before either collaborator
        // is touched, so a real renderer built with null Thymeleaf/MessageSource dependencies is
        // safe here — see that class's own NONE special-case.
        EmailContentRenderer contentRenderer = new EmailContentRenderer(null, null);
        MailMetrics mailMetrics = new MailMetrics(new SimpleMeterRegistry());
        return new MailService(contentRenderer, outboundEmailSender, mailMetrics, new EmailTransportProperties());
    }

    private FakeSmtpServer fakeSmtp;

    @AfterEach
    void stopFakeSmtp() {
        if (fakeSmtp != null) {
            fakeSmtp.close();
        }
    }

    /**
     * SMTP leg: a real {@link FakeSmtpServer} answers {@code RCPT TO} with a hard {@code 550},
     * producing the real {@code org.eclipse.angus.mail.smtp.SMTPAddressFailedException} the pinned
     * angus-mail jar actually throws for that reply, through the real {@link SmtpEmailSender} →
     * {@link SmtpErrorClassifier} → {@link MailManager#isRetryable} chain.
     */
    @Test
    void realSmtpPermanentFailure_isNotRetried() throws IOException {
        String rejectedAddress = "no-such-user@example.com";
        fakeSmtp = new FakeSmtpServer(rejectedAddress);

        ProviderConfig providerConfig = new ProviderConfig();
        providerConfig.setName("fake");
        providerConfig.setHost("localhost");
        providerConfig.setPort(String.valueOf(fakeSmtp.port()));
        providerConfig.setUsername("fake-user");
        providerConfig.setPassword("fake-password");
        SmtpProperties smtpProperties = new SmtpProperties();
        smtpProperties.setProviderConfigs(List.of(providerConfig));

        SmtpEmailSender smtpEmailSender = new SmtpEmailSender(
            new MailSenderProvider(smtpProperties), new SmtpErrorClassifier());

        EnvelopeEntityRepository envelopeEntityRepository = mock(EnvelopeEntityRepository.class);
        MailManager mailManager = new MailManager(
            mailService(smtpEmailSender), envelopeEntityRepository,
            freshCircuitBreakerFactory(), singleAttemptRetryTemplate());

        mailManager.sendEmailSync(envelopeFor(rejectedAddress));

        ArgumentCaptor<EnvelopeEntity> captor = ArgumentCaptor.forClass(EnvelopeEntity.class);
        Mockito.verify(envelopeEntityRepository).saveAndFlush(captor.capture());
        assertThat(captor.getValue().isRetry())
            .as("a real RCPT TO 550 through the real SMTP adapter chain must classify permanent")
            .isFalse();
    }

    /**
     * SES leg: a mocked {@link SesV2Client} throws a real {@link BadRequestException} (the same SDK
     * exception type {@code SesErrorClassifierTest} tests directly, not a hand-rolled stand-in)
     * through the real {@link SesEmailSender} → {@link SesErrorClassifier} → {@link
     * MailManager#isRetryable} chain.
     */
    @Test
    void realSesPermanentFailure_isNotRetried() {
        SesV2Client sesV2Client = mock(SesV2Client.class);
        doThrow(BadRequestException.builder().message("bad request").statusCode(400).build())
            .when(sesV2Client).sendEmail(any(java.util.function.Consumer.class));

        SesProperties sesProperties = new SesProperties();
        sesProperties.setFromAddress("noreply@skillars.test");
        sesProperties.setMaxSendRatePerSecond(10);

        SesEmailSender sesEmailSender = new SesEmailSender(
            sesV2Client, sesProperties, new EmailAddressParser(), new SesErrorClassifier(),
            new SesSendRateLimiter(sesProperties, new SimpleMeterRegistry()));

        EnvelopeEntityRepository envelopeEntityRepository = mock(EnvelopeEntityRepository.class);
        MailManager mailManager = new MailManager(
            mailService(sesEmailSender), envelopeEntityRepository,
            freshCircuitBreakerFactory(), singleAttemptRetryTemplate());

        mailManager.sendEmailSync(envelopeFor("player@example.com"));

        ArgumentCaptor<EnvelopeEntity> captor = ArgumentCaptor.forClass(EnvelopeEntity.class);
        Mockito.verify(envelopeEntityRepository).saveAndFlush(captor.capture());
        assertThat(captor.getValue().isRetry())
            .as("a real BadRequestException through the real SES adapter chain must classify permanent")
            .isFalse();
    }
}
