package com.softropic.skillars.platform.notification.infrastructure.listener;

import com.softropic.skillars.platform.notification.contract.Envelope;
import com.softropic.skillars.platform.notification.contract.Recipient;
import com.softropic.skillars.platform.notification.service.InAppNotificationService;
import com.softropic.skillars.platform.notification.service.NotificationCatalog;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import static net.logstash.logback.argument.StructuredArguments.kv;

/**
 * In-app inbox rows for emails sent by publishing an {@link Envelope} directly (performance report
 * shared, video moderation owner notice) rather than through {@code NotificationOutboxSupport}.
 *
 * <p>BEFORE_COMMIT on the very event {@code MailManager.sendEmailFromTemplate} sends AFTER_COMMIT, so
 * the inbox row is written inside the producing transaction: if it rolls back, neither the row nor
 * the email exists. An {@code Envelope} published with no transaction active reaches neither
 * listener (both default to {@code fallbackExecution = false}) — the email and the inbox stay
 * consistent there too.
 *
 * <p>{@code recordForEmail} never throws and runs its writes in a savepoint, so an inbox failure
 * cannot fail the report / moderation transaction; the catch below is belt-and-braces only.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class InAppEnvelopeListener {

    private final InAppNotificationService inAppNotificationService;

    @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT)
    public void onEnvelope(final Envelope envelope) {
        if (envelope.emailTemplate() == null || !NotificationCatalog.of(envelope.emailTemplate()).inApp()
                || envelope.recipients() == null) {
            return;
        }
        for (final Recipient recipient : envelope.recipients()) {
            try {
                inAppNotificationService.recordForEmail(
                    envelope.emailTemplate(), recipient.getEmail(), envelope.data(), envelope.sendId());
            } catch (Exception e) {
                log.error("Failed to record in-app notification", kv("template", envelope.emailTemplate()),
                    kv("sendId", envelope.sendId()), e);
            }
        }
    }
}
