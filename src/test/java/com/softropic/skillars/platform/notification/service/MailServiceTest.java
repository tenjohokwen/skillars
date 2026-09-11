package com.softropic.skillars.platform.notification.service;

import com.softropic.skillars.infrastructure.email.EmailTransportPermanentException;
import com.softropic.skillars.infrastructure.email.OutboundEmailRequest;
import com.softropic.skillars.infrastructure.email.OutboundEmailResult;
import com.softropic.skillars.infrastructure.email.OutboundEmailSender;
import com.softropic.skillars.platform.notification.contract.EmailTemplate;
import com.softropic.skillars.platform.notification.contract.Recipient;

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
 */
@DisplayName("MailService")
class MailServiceTest {

    @Mock
    private EmailContentRenderer contentRenderer;

    @Mock
    private OutboundEmailSender outboundEmailSender;

    private MailService mailService;
    private Recipient recipient;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        mailService = new MailService(contentRenderer, outboundEmailSender);
        recipient = new Recipient();
        recipient.setEmail("player@example.com");
        recipient.setLangKey("en");
        when(outboundEmailSender.send(any())).thenReturn(new OutboundEmailResult("provider:msg-id"));
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
}
