package com.softropic.skillars.platform.notification.service;

import com.softropic.skillars.infrastructure.email.EmailTransport;
import com.softropic.skillars.infrastructure.email.EmailTransportPermanentException;
import com.softropic.skillars.infrastructure.email.EmailTransportRateLimitedException;
import com.softropic.skillars.infrastructure.email.EmailTransportProperties;
import com.softropic.skillars.infrastructure.email.EmailTransportTransientException;
import com.softropic.skillars.infrastructure.email.OutboundEmailRequest;
import com.softropic.skillars.infrastructure.email.OutboundEmailResult;
import com.softropic.skillars.infrastructure.email.OutboundEmailSender;
import com.softropic.skillars.platform.notification.contract.EmailTemplate;
import com.softropic.skillars.platform.notification.contract.Recipient;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Story ses-1.2 AC3 — {@link MailService}'s reimplementation on the {@link OutboundEmailSender}
 * port had no direct unit test (code review 2026-09-11): every other {@code src/test} reference to
 * {@code MailService} uses it only as a mock seam, so nothing exercised the new mapping logic
 * itself — in particular, {@link EmailContentRenderer.Rendered#htmlBody()}/{@code textBody()}
 * landing in the right {@link OutboundEmailRequest} positions.
 *
 * <p>Story ses-1.3 AC5: also covers the new {@code mail.send} transport-tagged outcome metric —
 * success, every {@code EmailTransportException} subtype, the malformed-payload path (which must
 * also record {@code outcome=failure}, not only the post-construction send path), and the {@code
 * null}-transport → {@code "log"} tag fallback.
 */
@DisplayName("MailService")
class MailServiceTest {

    @Mock
    private EmailContentRenderer contentRenderer;

    @Mock
    private OutboundEmailSender outboundEmailSender;

    private SimpleMeterRegistry meterRegistry;
    private MailMetrics mailMetrics;
    private EmailTransportProperties transportProperties;
    private MailService mailService;
    private Recipient recipient;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        meterRegistry = new SimpleMeterRegistry();
        mailMetrics = new MailMetrics(meterRegistry);
        transportProperties = new EmailTransportProperties();
        transportProperties.setTransport(EmailTransport.SES);
        mailService = new MailService(contentRenderer, outboundEmailSender, mailMetrics, transportProperties);
        recipient = new Recipient();
        recipient.setEmail("player@example.com");
        recipient.setLangKey("en");
        when(outboundEmailSender.send(any())).thenReturn(new OutboundEmailResult("provider:msg-id"));
    }

    private double timerCount(String transport, String outcome) {
        return meterRegistry.get(MailMetrics.MAIL_SEND).tag("transport", transport).tag("outcome", outcome)
            .timer().count();
    }

    @Test
    @DisplayName("an html-bodied render maps htmlBody/textBody into the matching OutboundEmailRequest positions")
    void htmlRender_mapsFieldsWithoutSwapping() {
        when(contentRenderer.render(any(), any(), any()))
            .thenReturn(new EmailContentRenderer.Rendered("Subject line", "<p>html body</p>", null));

        mailService.sendEmailFromTemplate(recipient, EmailTemplate.ACTIVATION, Map.of());

        ArgumentCaptor<OutboundEmailRequest> captor = ArgumentCaptor.forClass(OutboundEmailRequest.class);
        org.mockito.Mockito.verify(outboundEmailSender).send(captor.capture());
        OutboundEmailRequest sent = captor.getValue();
        assertThat(sent.toAddress()).isEqualTo("player@example.com");
        assertThat(sent.subject()).isEqualTo("Subject line");
        assertThat(sent.htmlBody()).isEqualTo("<p>html body</p>");
        assertThat(sent.textBody()).isNull();
    }

    @Test
    @DisplayName("a text-bodied render (EmailTemplate.NONE) maps textBody, not htmlBody")
    void textRender_mapsFieldsWithoutSwapping() {
        when(contentRenderer.render(any(), any(), any()))
            .thenReturn(new EmailContentRenderer.Rendered("Ops Alert", null, "plain text body"));

        mailService.sendEmailFromTemplate(recipient, EmailTemplate.NONE, Map.of());

        ArgumentCaptor<OutboundEmailRequest> captor = ArgumentCaptor.forClass(OutboundEmailRequest.class);
        org.mockito.Mockito.verify(outboundEmailSender).send(captor.capture());
        OutboundEmailRequest sent = captor.getValue();
        assertThat(sent.subject()).isEqualTo("Ops Alert");
        assertThat(sent.htmlBody()).isNull();
        assertThat(sent.textBody()).isEqualTo("plain text body");
    }

    @Test
    @DisplayName("correlationId is a fresh UUID per call, not the sendId or anything from the values map")
    void correlationId_isAFreshUuidPerCall() {
        when(contentRenderer.render(any(), any(), any()))
            .thenReturn(new EmailContentRenderer.Rendered("Subject", "<p>x</p>", null));

        mailService.sendEmailFromTemplate(recipient, EmailTemplate.ACTIVATION, Map.of());
        mailService.sendEmailFromTemplate(recipient, EmailTemplate.ACTIVATION, Map.of());

        ArgumentCaptor<OutboundEmailRequest> captor = ArgumentCaptor.forClass(OutboundEmailRequest.class);
        org.mockito.Mockito.verify(outboundEmailSender, org.mockito.Mockito.times(2)).send(captor.capture());
        var requests = captor.getAllValues();
        String first = requests.get(0).correlationId();
        String second = requests.get(1).correlationId();

        assertThat(first).isNotEqualTo(second);
        assertThatCode(() -> UUID.fromString(first)).doesNotThrowAnyException();
        assertThatCode(() -> UUID.fromString(second)).doesNotThrowAnyException();
    }

    /**
     * Code review 2026-09-11, owner decision (b) — {@link OutboundEmailRequest}'s compact
     * constructor throws unchecked {@link IllegalArgumentException} on a blank recipient/subject;
     * left unwrapped that is neither an {@code EmailTransportException} nor a checked exception the
     * old code path produced, so {@code MailManager.isRetryable}'s classification (AC4,
     * {@code NON_REPAIRABLE_ERRORS = List.of(EmailTransportPermanentException.class)}) would never
     * match it and would retry a payload no re-drive can ever fix.
     */
    @Test
    @DisplayName("a blank recipient address (malformed payload) surfaces as EmailTransportPermanentException, not IllegalArgumentException")
    void blankRecipientEmail_surfacesAsPermanentTransportException() {
        recipient.setEmail("");
        when(contentRenderer.render(any(), any(), any()))
            .thenReturn(new EmailContentRenderer.Rendered("Subject", "<p>x</p>", null));

        assertThatThrownBy(() -> mailService.sendEmailFromTemplate(recipient, EmailTemplate.ACTIVATION, Map.of()))
            .isInstanceOf(EmailTransportPermanentException.class)
            .hasCauseInstanceOf(IllegalArgumentException.class);

        org.mockito.Mockito.verifyNoInteractions(outboundEmailSender);
    }

    @Test
    @DisplayName("a render with both bodies blank (malformed payload) also surfaces as EmailTransportPermanentException")
    void bothBodiesBlank_surfacesAsPermanentTransportException() {
        when(contentRenderer.render(any(), any(), any()))
            .thenReturn(new EmailContentRenderer.Rendered("Subject", null, null));

        assertThatThrownBy(() -> mailService.sendEmailFromTemplate(recipient, EmailTemplate.NONE, Map.of()))
            .isInstanceOf(EmailTransportPermanentException.class)
            .hasCauseInstanceOf(IllegalArgumentException.class);

        org.mockito.Mockito.verifyNoInteractions(outboundEmailSender);
    }

    // ------------------------------------------------------------ ses-1.3 AC5: mail.send metric

    @Test
    @DisplayName("a successful send records outcome=success, tagged with the active transport")
    void successfulSend_recordsSuccessOutcome() {
        when(contentRenderer.render(any(), any(), any()))
            .thenReturn(new EmailContentRenderer.Rendered("Subject", "<p>x</p>", null));

        mailService.sendEmailFromTemplate(recipient, EmailTemplate.ACTIVATION, Map.of());

        assertThat(timerCount("ses", "success")).isEqualTo(1.0);
    }

    @Test
    @DisplayName("the malformed-payload path (blank recipient) also records outcome=failure, not only the post-construction send path")
    void malformedPayloadPath_recordsFailureOutcome() {
        recipient.setEmail("");
        when(contentRenderer.render(any(), any(), any()))
            .thenReturn(new EmailContentRenderer.Rendered("Subject", "<p>x</p>", null));

        assertThatThrownBy(() -> mailService.sendEmailFromTemplate(recipient, EmailTemplate.ACTIVATION, Map.of()))
            .isInstanceOf(EmailTransportPermanentException.class);

        assertThat(timerCount("ses", "failure")).isEqualTo(1.0);
    }

    @Test
    @DisplayName("an EmailTransportTransientException from the send call records outcome=failure and is rethrown unchanged")
    void transientSendFailure_recordsFailureAndRethrowsSameInstance() {
        when(contentRenderer.render(any(), any(), any()))
            .thenReturn(new EmailContentRenderer.Rendered("Subject", "<p>x</p>", null));
        EmailTransportTransientException original = new EmailTransportTransientException("boom");
        when(outboundEmailSender.send(any())).thenThrow(original);

        assertThatThrownBy(() -> mailService.sendEmailFromTemplate(recipient, EmailTemplate.ACTIVATION, Map.of()))
            .isSameAs(original);

        assertThat(timerCount("ses", "failure")).isEqualTo(1.0);
    }

    @Test
    @DisplayName("an EmailTransportPermanentException from the send call records outcome=failure and is rethrown unchanged")
    void permanentSendFailure_recordsFailureAndRethrowsSameInstance() {
        when(contentRenderer.render(any(), any(), any()))
            .thenReturn(new EmailContentRenderer.Rendered("Subject", "<p>x</p>", null));
        EmailTransportPermanentException original = new EmailTransportPermanentException("nope");
        when(outboundEmailSender.send(any())).thenThrow(original);

        assertThatThrownBy(() -> mailService.sendEmailFromTemplate(recipient, EmailTemplate.ACTIVATION, Map.of()))
            .isSameAs(original);

        assertThat(timerCount("ses", "failure")).isEqualTo(1.0);
    }

    /**
     * AC6 asks for each {@code EmailTransportException} subtype, and this is the one whose
     * {@code isSameAs} survival actually carries weight: {@code ComponentConfig}'s retry classifier
     * and the circuit breaker's {@code ignoreException} predicate both identify a rate-limit
     * rejection by walking the cause chain, and {@code MailManager} skips consuming a delivery
     * attempt on the same basis. Any rewrap here that replaced the instance — rather than leaving it
     * untouched — would silently defeat all three.
     */
    @Test
    @DisplayName("an EmailTransportRateLimitedException records outcome=failure and is rethrown unchanged")
    void rateLimitedSendFailure_recordsFailureAndRethrowsSameInstance() {
        when(contentRenderer.render(any(), any(), any()))
            .thenReturn(new EmailContentRenderer.Rendered("Subject", "<p>x</p>", null));
        EmailTransportRateLimitedException original =
            new EmailTransportRateLimitedException("SES send rate limit exceeded: 10 sends/second");
        when(outboundEmailSender.send(any())).thenThrow(original);

        assertThatThrownBy(() -> mailService.sendEmailFromTemplate(recipient, EmailTemplate.ACTIVATION, Map.of()))
            .isSameAs(original);

        assertThat(timerCount("ses", "failure")).isEqualTo(1.0);
    }

    @Test
    @DisplayName("a null EmailTransportProperties.getTransport() tags the metric transport=log rather than throwing")
    void nullTransport_tagsMetricAsLog() {
        transportProperties.setTransport(null);
        when(contentRenderer.render(any(), any(), any()))
            .thenReturn(new EmailContentRenderer.Rendered("Subject", "<p>x</p>", null));

        assertThatCode(() -> mailService.sendEmailFromTemplate(recipient, EmailTemplate.ACTIVATION, Map.of()))
            .doesNotThrowAnyException();

        assertThat(timerCount("log", "success")).isEqualTo(1.0);
    }
}
