package com.softropic.skillars.infrastructure.email;

import java.util.Map;
import java.util.regex.Pattern;

/**
 * A transport-neutral request to send one email.
 *
 * <p>The compact constructor is the single validation point for <strong>presence</strong>
 * (null/blank) only. Transport-specific <strong>format</strong> validation — e.g. the SES adapter's
 * recipient-address check — is a separate concern that belongs in the adapter, per
 * {@code requirements/ses-email-consolidation.md#6.3}'s argument that format rules must live with
 * the transport and disappear with it. Presence is checked once, centrally; shape is checked
 * per-transport, where the transport's own rules apply.
 *
 * @param toAddress the recipient address, in whatever form the transport accepts
 * @param subject the email subject
 * @param htmlBody the rendered HTML body, or null/blank if this is a text-only email
 * @param textBody the plain-text body, or null/blank if this is an html-only email. At least one
 *     of {@code htmlBody}/{@code textBody} must be present — both being null/blank is rejected —
 *     but which one(s) is not fixed: html-only and text-only requests are both valid
 * @param correlationId an opaque id echoed into logs, the SES message tag, and the
 *     {@code LoggingEmailSender} filename. It carries no idempotency contract.
 * @param headers extra RFC 5322 header fields to set on the message (e.g. {@code List-Unsubscribe}),
 *     never {@code null} — empty when there are none. Names must be RFC 5322 field-name characters
 *     and values must not contain CR/LF, so a value can never inject a second header.
 */
public record OutboundEmailRequest(
    String toAddress,
    String subject,
    String htmlBody,
    String textBody,
    String correlationId,
    Map<String, String> headers) {

    private static final Pattern HEADER_NAME = Pattern.compile("[!-9;-~]+");

    public OutboundEmailRequest {
        requireNonBlank(toAddress, "toAddress");
        requireNonBlank(subject, "subject");
        requireNonBlank(correlationId, "correlationId");
        if (isBlank(htmlBody) && isBlank(textBody)) {
            throw new IllegalArgumentException(
                "at least one of htmlBody/textBody must be non-blank");
        }
        headers = headers == null ? Map.of() : Map.copyOf(headers);
        headers.forEach((name, value) -> {
            if (!HEADER_NAME.matcher(name).matches()) {
                throw new IllegalArgumentException("invalid header name: " + name);
            }
            if (value == null || value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0) {
                throw new IllegalArgumentException("invalid value for header " + name);
            }
        });
    }

    /** A request with no extra headers — the shape every caller used before headers existed. */
    public OutboundEmailRequest(String toAddress, String subject, String htmlBody, String textBody,
                                String correlationId) {
        this(toAddress, subject, htmlBody, textBody, correlationId, Map.of());
    }

    private static void requireNonBlank(String value, String fieldName) {
        if (isBlank(value)) {
            throw new IllegalArgumentException(fieldName + " must not be null or blank");
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
