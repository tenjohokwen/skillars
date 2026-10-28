package com.softropic.skillars.platform.notification.service;

import com.softropic.skillars.infrastructure.email.EmailTransport;
import com.softropic.skillars.infrastructure.email.EmailTransportPermanentException;
import com.softropic.skillars.infrastructure.email.EmailTransportProperties;
import com.softropic.skillars.infrastructure.email.EmailTransportTransientException;
import com.softropic.skillars.infrastructure.email.OutboundEmailRequest;
import com.softropic.skillars.infrastructure.email.OutboundEmailSender;
import com.softropic.skillars.infrastructure.web.FrontendLinkBuilder;
import com.softropic.skillars.platform.notification.contract.EmailTemplate;
import com.softropic.skillars.platform.notification.contract.Recipient;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
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
    private final NotificationPreferenceService preferenceService;
    private final FrontendLinkBuilder frontendLinks;

    public MailService(final EmailContentRenderer contentRenderer,
                       final OutboundEmailSender outboundEmailSender,
                       final MailMetrics mailMetrics,
                       final EmailTransportProperties transportProperties,
                       final NotificationPreferenceService preferenceService,
                       final FrontendLinkBuilder frontendLinks) {
        this.contentRenderer = contentRenderer;
        this.outboundEmailSender = outboundEmailSender;
        this.mailMetrics = mailMetrics;
        this.transportProperties = transportProperties;
        this.preferenceService = preferenceService;
        this.frontendLinks = frontendLinks;
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
        boolean suppressed = false;
        try {
            // Notification centre (UI-gap chunk C9): the email opt-out is enforced HERE, at send time,
            // so it also covers rows already sitting in the outbox / EmailRetryScheduler queue when the
            // user opted out. A suppressed send returns normally — MailManager records the recipient as
            // handled (status SENT), which is the correct terminal outcome: a re-drive must not retry an
            // email the user asked not to receive. Transactional templates never reach the DB here.
            final NotificationPreferenceService.EmailDecision decision;
            try {
                decision = preferenceService.emailDecision(emailTemplate, recipient.getEmail());
            } catch (RuntimeException e) {
                // Neither sending (could ignore an opt-out) nor dropping (could lose a wanted email)
                // is safe on an unknown preference: fail retryably, inside the timed block.
                throw new EmailTransportTransientException("email preference lookup failed", e);
            }
            if (decision.suppressed()) {
                suppressed = true;
                log.info("[EMAIL_SUPPRESSED_BY_PREFERENCE] template={} sendId={}", emailTemplate, values.get("sendId"));
                return;
            }
            final Map<String, Object> renderValues;
            final Map<String, String> headers;
            if (decision.unsubscribeToken() != null) {
                final String token = URLEncoder.encode(decision.unsubscribeToken(), StandardCharsets.UTF_8);
                renderValues = new HashMap<>(values);
                // Body link: the hash-routed landing page, which asks for a click before unsubscribing.
                renderValues.put("unsubscribeUrl", frontendLinks.route("unsubscribe?token=" + token));
                // RFC 2369 / RFC 8058 one-click: the mailbox provider POSTs "List-Unsubscribe=One-Click"
                // straight to the API. The API is served under the frontend origin (same-origin SPA in
                // prod; the dev server proxies /api), so frontendLinks.base() is the right host.
                headers = Map.of(
                    "List-Unsubscribe",
                    "<" + frontendLinks.base() + "/api/notifications/unsubscribe?token=" + token + ">",
                    "List-Unsubscribe-Post", "List-Unsubscribe=One-Click");
            } else {
                renderValues = values;
                headers = Map.of();
            }

            final EmailContentRenderer.Rendered rendered = contentRenderer.render(recipient, emailTemplate, renderValues);

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
                    recipient.getEmail(), rendered.subject(), rendered.htmlBody(), rendered.textBody(), correlationId,
                    headers);
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
                // A preference-suppressed send is neither a send nor a failure: no metric.
                if (!suppressed) {
                    mailMetrics.recordSend(transportTag(), success ? "success" : "failure", System.nanoTime() - start);
                }
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
