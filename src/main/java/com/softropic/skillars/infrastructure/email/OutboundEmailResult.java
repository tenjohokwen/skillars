package com.softropic.skillars.infrastructure.email;

/**
 * The outcome of a successful {@link OutboundEmailSender#send(OutboundEmailRequest)} call.
 *
 * @param messageId a transport-specific identifier for the sent message. Never null — the SES
 *     adapter returns the SDK's own message id, and {@code LoggingEmailSender} returns
 *     {@code "log:" + correlationId} rather than leave this column unexplainably null once Phase 4
 *     persists it.
 */
public record OutboundEmailResult(String messageId) {
}
