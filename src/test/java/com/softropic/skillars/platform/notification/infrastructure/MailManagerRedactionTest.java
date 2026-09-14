package com.softropic.skillars.platform.notification.infrastructure;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

import com.softropic.skillars.infrastructure.email.EmailTransportPermanentException;
import com.softropic.skillars.platform.notification.contract.EmailTemplate;
import com.softropic.skillars.platform.notification.contract.Envelope;
import com.softropic.skillars.platform.notification.contract.Recipient;
import com.softropic.skillars.platform.notification.repo.EnvelopeEntity;
import com.softropic.skillars.platform.notification.repo.EnvelopeEntityRepository;
import com.softropic.skillars.platform.notification.service.MailManager;
import com.softropic.skillars.platform.notification.service.MailService;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.slf4j.LoggerFactory;
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
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;

/**
 * Story ses-1.4 AC7, code review 2026-09-12: a masking control with no regression test is not a
 * control. The Dev Notes' original claim ("Verified in {@code MailManagerDuplicateSendIdIT}'s
 * captured log output") was a manual console observation during development, not an assertion any
 * test actually made — {@code grep -rn "REDACTED" src/test/java} matched only the unrelated
 * {@code BodySanitizerTest} before this class existed. A change to {@code Envelope}'s shape, to
 * either log statement's format string, or a new sensitive template added without updating
 * {@code SENSITIVE_DATA_TEMPLATES} would all silently reintroduce the leak with nothing to catch it.
 *
 * <p>Asserts both log sites {@code MailManager} carries per AC7 — the success-path INFO line and the
 * failure-path ERROR line — and both the sensitive (OTP/verify/SEND_OTP) and non-sensitive (booking)
 * cases, so the assertions cannot pass by masking unconditionally.
 */
class MailManagerRedactionTest {

    private MailManager mailManager;
    private Logger mailManagerLogger;
    private ListAppender<ILoggingEvent> logCapture;

    @Mock
    private MailService mailService;

    @Mock
    private EnvelopeEntityRepository envelopeEntityRepository;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);

        CircuitBreakerRegistry circuitBreakerRegistry = CircuitBreakerRegistry.of(
            CircuitBreakerConfig.custom().slidingWindowSize(10).minimumNumberOfCalls(10).build());
        CircuitBreakerFactory<?, ?> circuitBreakerFactory = new Resilience4JCircuitBreakerFactory(
            circuitBreakerRegistry, io.github.resilience4j.timelimiter.TimeLimiterRegistry.ofDefaults(), null);
        RetryTemplate retryTemplate = RetryTemplate.builder()
            .maxAttempts(1).fixedBackoff(Duration.ofMillis(1)).build();

        mailManager = new MailManager(mailService, envelopeEntityRepository, circuitBreakerFactory, retryTemplate);

        mailManagerLogger = (Logger) LoggerFactory.getLogger(MailManager.class);
        // logback-test.xml pins root at WARN; the success-path log this test targets is INFO.
        mailManagerLogger.setLevel(Level.INFO);
        logCapture = new ListAppender<>();
        logCapture.start();
        mailManagerLogger.addAppender(logCapture);
    }

    @AfterEach
    void tearDown() {
        mailManagerLogger.detachAppender(logCapture);
        mailManagerLogger.setLevel(null);
    }

    private Envelope envelope(EmailTemplate template, Map<String, Object> data) {
        Recipient recipient = new Recipient();
        recipient.setEmail("otp.test@example.com");
        return new Envelope(List.of(recipient), template, Instant.now().plus(Duration.ofDays(1)), data, UUID.randomUUID().toString());
    }

    private String allFormattedMessages() {
        StringBuilder sb = new StringBuilder();
        for (ILoggingEvent event : logCapture.list) {
            sb.append(event.getFormattedMessage()).append('\n');
        }
        return sb.toString();
    }

    @Test
    void successfulSend_ofOtpTemplate_masksOtpCodeValueButKeepsKeyName() {
        doNothing().when(mailService).sendEmailFromTemplate(any(), any(), any());
        when(envelopeEntityRepository.findBySendId(any())).thenReturn(null);

        mailManager.sendEmailSync(envelope(EmailTemplate.COACH_OTP, Map.of("otpCode", "654321")));

        String rendered = allFormattedMessages();
        assertThat(rendered).as("the key name must survive so the line stays diagnostic").contains("otpCode=[REDACTED]");
        assertThat(rendered).as("the actual OTP code must never appear in the log").doesNotContain("654321");
    }

    @Test
    void successfulSend_ofVerifyTemplate_masksVerifyUrlValueButKeepsKeyName() {
        doNothing().when(mailService).sendEmailFromTemplate(any(), any(), any());
        when(envelopeEntityRepository.findBySendId(any())).thenReturn(null);

        mailManager.sendEmailSync(envelope(EmailTemplate.PARENT_EMAIL_VERIFY,
            Map.of("verifyUrl", "https://skillars.example/verify?token=super-secret-token")));

        String rendered = allFormattedMessages();
        assertThat(rendered).contains("verifyUrl=[REDACTED]");
        assertThat(rendered).as("the bearer token must never appear in the log")
            .doesNotContain("super-secret-token");
    }

    /** AC7's own correction (S/auditor finding): SEND_OTP (login-2FA) is sensitive too, not just registration. */
    @Test
    void successfulSend_ofLoginOtpTemplate_masksBothKeysButKeepsBothKeyNames() {
        doNothing().when(mailService).sendEmailFromTemplate(any(), any(), any());
        when(envelopeEntityRepository.findBySendId(any())).thenReturn(null);

        mailManager.sendEmailSync(envelope(EmailTemplate.SEND_OTP, Map.of("otpCode", "111222", "helpCode", "HLP-7788")));

        String rendered = allFormattedMessages();
        assertThat(rendered).contains("otpCode=[REDACTED]").contains("helpCode=[REDACTED]");
        assertThat(rendered).doesNotContain("111222").doesNotContain("HLP-7788");
    }

    @Test
    void successfulSend_ofBookingTemplate_isNotMasked() {
        doNothing().when(mailService).sendEmailFromTemplate(any(), any(), any());
        when(envelopeEntityRepository.findBySendId(any())).thenReturn(null);

        mailManager.sendEmailSync(envelope(EmailTemplate.BOOKING_CONFIRMED, Map.of("coachDisplayName", "Coach Ada")));

        assertThat(allFormattedMessages())
            .as("non-sensitive templates must be logged in full, unmasked — this proves masking is "
                + "conditional, not unconditional")
            .contains("Coach Ada")
            .doesNotContain("[REDACTED]");
    }

    @Test
    void failedSend_ofOtpTemplate_errorLogMasksOtpCodeValueButKeepsKeyName() {
        doThrow(new EmailTransportPermanentException("bad address")).when(mailService)
            .sendEmailFromTemplate(any(), any(), any());
        when(envelopeEntityRepository.findBySendId(any())).thenReturn(null);
        ArgumentCaptor<EnvelopeEntity> captor = ArgumentCaptor.forClass(EnvelopeEntity.class);

        mailManager.sendEmailSync(envelope(EmailTemplate.PLAYER_OTP, Map.of("otpCode", "999000")));

        org.mockito.Mockito.verify(envelopeEntityRepository).saveAndFlush(captor.capture());
        String rendered = allFormattedMessages();
        assertThat(rendered).as("the failure-path ERROR log must mask the same way as the success-path INFO log")
            .contains("otpCode=[REDACTED]");
        assertThat(rendered).doesNotContain("999000");
    }
}
