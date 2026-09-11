package com.softropic.skillars.infrastructure.email;

/**
 * Transport-neutral seam for sending an email. "Who sends this email and how" is decided entirely
 * by which implementation is wired for {@code app.email.transport} — callers never know or care
 * whether that's SES, SMTP, or a local log/file transport.
 *
 * <p>Story ses-1.1 (Phase 1 of {@code requirements/ses-email-consolidation.md}): this port replaces
 * the old {@code SesEmailService} interface, which had one real implementation, a no-op, and a
 * dev-only bridge into SMTP. See that story's Dev Notes for the full rationale.
 */
public interface OutboundEmailSender {

    /**
     * Sends the email described by {@code request}.
     *
     * @throws EmailTransportTransientException on a failure the caller may reasonably retry
     * @throws EmailTransportPermanentException on a failure retrying cannot fix (e.g. a malformed
     *     recipient address)
     */
    OutboundEmailResult send(OutboundEmailRequest request);
}
