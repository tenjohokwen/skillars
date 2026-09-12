package com.softropic.skillars.platform.notification.service;

import com.softropic.skillars.infrastructure.email.EmailTransportPermanentException;
import com.softropic.skillars.infrastructure.email.OutboundEmailRequest;
import com.softropic.skillars.infrastructure.email.OutboundEmailSender;
import com.softropic.skillars.platform.notification.contract.EmailTemplate;
import com.softropic.skillars.platform.notification.contract.Recipient;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.UUID;

import io.micrometer.observation.annotation.Observed;

/**
 * Story ses-1.2 AC3: no longer constructs a {@code MimeMessage} or selects an SMTP provider itself —
 * rendering is delegated to {@link EmailContentRenderer} (AC1) and the actual send to whichever
 * {@link OutboundEmailSender} is wired for {@code app.email.transport}.
 */
@Slf4j
@Service
public class MailService {

    private final EmailContentRenderer contentRenderer;
    private final OutboundEmailSender outboundEmailSender;

    public MailService(final EmailContentRenderer contentRenderer,
                       final OutboundEmailSender outboundEmailSender) {
        this.contentRenderer = contentRenderer;
        this.outboundEmailSender = outboundEmailSender;
    }

    @Observed(name = "mail.send_from_template")
    public void sendEmailFromTemplate(final Recipient recipient,
                                      final EmailTemplate emailTemplate,
                                      final Map<String, Object> values) {
        final EmailContentRenderer.Rendered rendered = contentRenderer.render(recipient, emailTemplate, values);

        // One correlation id per call, not per logical send — a RetryTemplate retry (MailManager)
        // gets a new id. Correlating retries of the same envelope back to one logical send is a
        // Phase 4 concern, once OutboundEmailResult.messageId() is persisted onto EnvelopeEntity and
        // envelope.sendId() becomes available to key off of.
        final String correlationId = UUID.randomUUID().toString();
        // Code review 2026-09-11, owner decision (b): OutboundEmailRequest's compact constructor
        // throws unchecked IllegalArgumentException on a blank recipient/subject or two blank
        // bodies — a malformed-payload failure, not a transport one. Left unwrapped, it is neither
        // an EmailTransportPermanentException nor a checked exception the old code path produced,
        // so MailManager.isRetryable's depth-bounded walk (AC4's NON_REPAIRABLE_ERRORS, unchanged
        // per the owner's decision) would never match it and would retry a payload no re-drive can
        // ever fix. Before this story a blank recipient reached MimeMessageHelper.setTo and surfaced
        // as AddressException -> permanent; wrapping here restores that classification.
        final OutboundEmailRequest request;
        try {
            request = new OutboundEmailRequest(
                recipient.getEmail(), rendered.subject(), rendered.htmlBody(), rendered.textBody(), correlationId);
        } catch (IllegalArgumentException ex) {
            throw new EmailTransportPermanentException("malformed email payload: " + ex.getMessage(), ex);
        }
        final var result = outboundEmailSender.send(request);
        log.info("Email sent. correlationId={}, messageId={}", correlationId, result.messageId());
    }
}
