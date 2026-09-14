package com.softropic.skillars.platform.notification.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.softropic.skillars.platform.notification.contract.EmailDeliveryStatus;
import com.softropic.skillars.platform.notification.contract.EmailTemplate;
import com.softropic.skillars.platform.notification.contract.Envelope;
import com.softropic.skillars.platform.notification.contract.Recipient;
import com.softropic.skillars.platform.notification.repo.EnvelopeEntity;
import com.softropic.skillars.platform.notification.repo.EnvelopeEntityRepository;
import com.softropic.skillars.platform.notification.service.NotificationOutboxSupport.NotificationEmailPayload;
import com.softropic.skillars.platform.outbox.contract.OutboxMessageHandler;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.UncheckedIOException;
import java.time.Instant;
import java.util.List;

/**
 * skillars-deferred-91 AC3: sends a transactional email enqueued by {@link NotificationOutboxSupport}
 * by calling {@code MailManager.sendEmailSync} directly (its own {@code REQUIRES_NEW} transaction +
 * circuit breaker + retry). A re-drive re-sends — acceptable per AC3, and the producer-side dedupe
 * stamps (e.g. {@code SessionPackExpiryNotifier.expiryWarnedAt}) prevent the common duplicate.
 *
 * <p><strong>Failure detection</strong> (skillars-deferred-91 code review, decision D1).
 * {@code MailManager.sendEmailSync} catches {@code Exception} and <em>never rethrows</em>: it stamps
 * {@code EnvelopeEntity.status = FAILED} and returns normally. Treating that normal return as
 * success meant the outbox counted the row {@code processed} and deleted it, so every SMTP outage,
 * open circuit breaker and exhausted retry destroyed the message with {@code attempts} still 0 and
 * {@code [OUTBOX_STUCK]} unreachable — AC3's durability guarantee did not exist. We therefore read
 * back the envelope this send just persisted and decide from its recorded outcome:
 *
 * <ul>
 *   <li>{@code FAILED} + {@code retry == true} (SMTP down, circuit open, timeout) — throw, so the
 *       row keeps {@code attempts++} / {@code last_error} and is re-driven after its backoff.</li>
 *   <li>{@code FAILED} + {@code retry == false} (malformed address, non-repairable
 *       {@code AddressException}) — a re-drive can never succeed, so log
 *       {@code [NOTIFICATION_EMAIL_UNDELIVERABLE]} at ERROR and let the row complete rather than
 *       creating an immortal poison row that consumes a claim slot forever.</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class NotificationEmailOutboxHandler implements OutboxMessageHandler {

    private final MailManager mailManager;
    private final EnvelopeEntityRepository envelopeEntityRepository;
    private final ObjectMapper objectMapper;

    @Override
    public String aggregateType() {
        return NotificationOutboxSupport.AGGREGATE_TYPE;
    }

    @Override
    public void handle(String payload) {
        final NotificationEmailPayload p;
        try {
            p = objectMapper.readValue(payload, NotificationEmailPayload.class);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new UncheckedIOException(e);
        }

        Recipient recipient = new Recipient();
        recipient.setEmail(p.toAddress());
        recipient.setLangKey(p.langKey());
        // Story ses-1.4 AC2: firstname is nullable and only ever set by registration/OTP producers;
        // every other outbox-routed template ignores ${recipient.firstname} so a null here is a no-op.
        recipient.setFirstname(p.firstname());

        final Instant deadline = deadlineOf(p);
        final Envelope envelope = new Envelope(
            List.of(recipient), EmailTemplate.valueOf(p.template()), deadline, p.data(), p.sendId());

        // Story ses-1.4 AC3 (code review B1): the deadline was previously consulted only by
        // EmailRetryScheduler, and only for rows already FAILED+retry=true. A first-attempt send
        // through the outbox never checked it, so a row delayed by drain-pool saturation, a pod
        // restart, or an SES outage could be delivered whenever it finally drained — potentially
        // hours after a 10-minute OTP's own TTL. Guarding here, rather than inside
        // MailManager.sendEmailSync, keeps the change scoped to this story's outbox-routed path
        // instead of every existing caller of that shared method.
        if (Instant.now().isAfter(deadline)) {
            log.warn("[NOTIFICATION_EMAIL_DEADLINE_EXPIRED] template={} to={} sendId={} deadline={} "
                    + "— releasing outbox row with no delivery attempt",
                p.template(), p.toAddress(), p.sendId(), deadline);
            // Code review 2026-09-12 (blind+edge+auditor): a first-attempt deadline expiry used to
            // leave no durable record at all — no EnvelopeEntity row, no [OUTBOX_STUCK], no metric —
            // asymmetric with EmailRetryScheduler's identical DEADLINE_EXPIRED precheck (:97-101),
            // which persists it. Symmetric fix: persist DEADLINE_EXPIRED/retry=false here too, via
            // findBySendId first (not a blind insert) so a re-drive can never collide on the sendId
            // unique constraint against a row this same guard already wrote.
            persistDeadlineExpired(envelope);
            return;
        }

        mailManager.sendEmailSync(envelope);

        final EnvelopeEntity persisted = envelopeEntityRepository.findBySendId(p.sendId());
        if (persisted != null && persisted.getStatus() == EmailDeliveryStatus.FAILED) {
            if (persisted.isRetry()) {
                throw new IllegalStateException("email send FAILED (retryable) for template="
                    + p.template() + " sendId=" + p.sendId());
            }
            log.error("[NOTIFICATION_EMAIL_UNDELIVERABLE] template={} to={} sendId={} — permanently "
                    + "undeliverable, no re-drive can succeed; outbox row released",
                p.template(), p.toAddress(), p.sendId());
            return;
        }
        log.debug("[NOTIFICATION_EMAIL] sent {} to {} (sendId={})", p.template(), p.toAddress(), p.sendId());
    }

    /**
     * Story ses-1.4 AC3, code review 2026-09-12: persists the terminal outcome of a first-attempt
     * deadline expiry so it is visible on the same operational surface as every other terminal
     * outcome this handler and {@code EmailRetryScheduler} produce, rather than log-only.
     *
     * <p>{@code findBySendId} first, not a blind insert: this guard can in principle run more than
     * once for the same {@code sendId} (e.g. a future change that lets the generic outbox re-dispatch
     * a row this method already returned normally for), and a blind {@code saveAndFlush} would then
     * collide on the {@code send_id} unique constraint. Attempts is left untouched (zero for a fresh
     * row) — a deadline-expired precheck must not count as a delivery attempt, matching
     * {@code EmailRetryScheduler}'s own DEADLINE_EXPIRED path.
     */
    private void persistDeadlineExpired(final Envelope envelope) {
        final EnvelopeEntity existing = envelopeEntityRepository.findBySendId(envelope.sendId());
        if (existing != null) {
            existing.setStatus(EmailDeliveryStatus.DEADLINE_EXPIRED);
            existing.setRetry(false);
            return;
        }
        final EnvelopeEntity entity = EnvelopeMapper.toEntity(envelope);
        entity.setStatus(EmailDeliveryStatus.DEADLINE_EXPIRED);
        entity.setRetry(false);
        entity.setAttempts(0);
        envelopeEntityRepository.saveAndFlush(entity);
    }

    /**
     * The deadline is stamped at <em>enqueue</em> time and carried in the payload. It used to be
     * recomputed here as {@code Instant.now().plus(1 day)}, so a message that sat in the outbox for
     * nine days was delivered with a freshly minted 24-hour deadline and any staleness guard was
     * structurally unreachable. Older rows enqueued before this change carry no deadline; they fall
     * back to the previous behaviour rather than being rejected.
     */
    private static Instant deadlineOf(NotificationEmailPayload p) {
        return p.deadline() != null ? p.deadline() : Instant.now().plus(java.time.Duration.ofDays(1));
    }
}
