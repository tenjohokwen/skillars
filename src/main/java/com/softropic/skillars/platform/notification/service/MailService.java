package com.softropic.skillars.platform.notification.service;

import com.softropic.skillars.infrastructure.email.EmailTransport;
import com.softropic.skillars.infrastructure.email.EmailTransportPermanentException;
import com.softropic.skillars.infrastructure.email.EmailTransportProperties;
import com.softropic.skillars.infrastructure.email.OutboundEmailRequest;
import com.softropic.skillars.infrastructure.email.OutboundEmailSender;
import com.softropic.skillars.platform.notification.contract.EmailTemplate;
import com.softropic.skillars.platform.notification.contract.Recipient;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Locale;
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
    private final MailMetrics mailMetrics;
    private final EmailTransportProperties transportProperties;

    public MailService(final EmailContentRenderer contentRenderer,
                       final OutboundEmailSender outboundEmailSender,
                       final MailMetrics mailMetrics,
                       final EmailTransportProperties transportProperties) {
        this.contentRenderer = contentRenderer;
        this.outboundEmailSender = outboundEmailSender;
        this.mailMetrics = mailMetrics;
        this.transportProperties = transportProperties;
    }

    @Observed(name = "mail.send_from_template")
    public void sendEmailFromTemplate(final Recipient recipient,
                                      final EmailTemplate emailTemplate,
                                      final Map<String, Object> values) {
        // Story ses-1.3 AC5: spans the whole method body, not only the send call — a malformed
        // rendered payload (caught below) is just as much a real failure as a transport error, and
        // must be counted too, or mail.send_seconds_count under-counts relative to actual attempts.
        final long start = System.nanoTime();
        boolean success = false;
        try {
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
            success = true;
        } finally {
            // Code review 2026-09-12: a throw out of a finally block silently DISCARDS the exception
            // in flight. If recording were ever to throw (meter name/type collision on mail.send, a
            // registry closed during shutdown), an EmailTransportPermanentException would be replaced
            // by an unrelated runtime exception, and MailManager.isRetryable's NON_REPAIRABLE_ERRORS
            // walk would then reclassify a permanently-broken payload as retryable and re-drive it
            // forever — precisely the misclassification the comment above exists to prevent. No
            // metric is worth that, so failure to record is logged and swallowed.
            try {
                mailMetrics.recordSend(transportTag(), success ? "success" : "failure", System.nanoTime() - start);
            } catch (RuntimeException metricsFailure) {
                log.warn("Failed to record the mail.send metric; the send outcome itself is unaffected",
                    metricsFailure);
            }
        }
    }

    /**
     * Story ses-1.3 AC5: {@code EmailTransportProperties.getTransport()} can be {@code null} — the
     * field has no default, and property-absent is a state this codebase deliberately keeps working
     * ({@code LoggingEmailSender} carries {@code matchIfMissing = true} for exactly this). Micrometer's
     * {@code Tag} does {@code Objects.requireNonNull} on both key and value, so tagging with a raw
     * {@code null} would throw on every single send in that state — resolve to {@code "log"} instead.
     * The explicit {@link Locale#ROOT} avoids a Turkish-locale {@code toLowerCase()} trap on
     * {@code "SES"}/{@code "LOG"}.
     */
    private String transportTag() {
        EmailTransport transport = transportProperties.getTransport();
        return transport == null ? "log" : transport.name().toLowerCase(Locale.ROOT);
    }
}
