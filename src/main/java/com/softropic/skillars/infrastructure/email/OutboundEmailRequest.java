package com.softropic.skillars.infrastructure.email;

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
 */
public record OutboundEmailRequest(
    String toAddress,
    String subject,
    String htmlBody,
    String textBody,
    String correlationId) {

    public OutboundEmailRequest {
        requireNonBlank(toAddress, "toAddress");
        requireNonBlank(subject, "subject");
        requireNonBlank(correlationId, "correlationId");
        if (isBlank(htmlBody) && isBlank(textBody)) {
            throw new IllegalArgumentException(
                "at least one of htmlBody/textBody must be non-blank");
        }
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
