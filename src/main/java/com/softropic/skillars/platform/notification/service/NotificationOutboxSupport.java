package com.softropic.skillars.platform.notification.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.softropic.skillars.platform.notification.contract.EmailTemplate;
import com.softropic.skillars.platform.notification.contract.Recipient;
import com.softropic.skillars.platform.outbox.service.OutboxService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;

/**
 * skillars-deferred-91 AC3: transactional-email listeners ({@code BookingEmailListener},
 * {@code SessionPackEmailListener}) run on {@code @TransactionalEventListener(AFTER_COMMIT)} with no
 * ambient transaction and publish an {@code Envelope} event that {@code MailManager}'s own
 * {@code @TransactionalEventListener(AFTER_COMMIT, fallbackExecution=false)} listener then
 * <strong>silently drops</strong> — there is no transaction to hang the nested AFTER_COMMIT off, so
 * the email is never sent. Routing the request through the durable outbox (skillars-deferred-91
 * AC1) instead makes it re-drivable: {@link NotificationEmailOutboxHandler} calls
 * {@code MailManager.sendEmailSync} directly.
 *
 * <p>{@code REQUIRES_NEW} because the callers have no ambient transaction and {@code enqueue} is a
 * repository write.
 *
 * <p><strong>What this actually guarantees</strong> (skillars-deferred-91 code review, decision D5).
 * AC1 asks a producer to write its outbox row <em>inside</em> the business transaction, which is what
 * makes a transactional outbox atomic with the work it describes. This path cannot: its callers are
 * themselves {@code @TransactionalEventListener(AFTER_COMMIT)} listeners, so the business transaction
 * has already committed by the time they run, and the enqueue below is a separate transaction. The
 * guarantee is therefore narrower than AC3's wording suggests:
 *
 * <ul>
 *   <li><strong>Closed:</strong> the nested-AFTER_COMMIT silent drop — {@code MailManager}'s own
 *       {@code @TransactionalEventListener(AFTER_COMMIT, fallbackExecution=false)} had no transaction
 *       to hang off, so the email was never sent at all. It is now re-drivable with
 *       {@code attempts}/{@code last_error} and an {@code [OUTBOX_STUCK]} alert.</li>
 *   <li><strong>Still open:</strong> a crash, or a DB failure, in the window between the business
 *       commit and this enqueue's commit still loses the email. Closing it requires moving the
 *       enqueue into each of the 22 producing transactions — filed to {@code deferred-work.md}.</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class NotificationOutboxSupport {

    public static final String AGGREGATE_TYPE = "NOTIFICATION_EMAIL";

    /** How long after enqueue the send is still considered timely. Stamped into the payload. */
    private static final Duration DELIVERY_DEADLINE = Duration.ofDays(1);

    private final OutboxService outboxService;
    private final ObjectMapper objectMapper;

    /**
     * MUST be a cross-bean call (the AFTER_COMMIT listeners are in {@code ...infrastructure.listener})
     * so {@code REQUIRES_NEW} goes through the proxy.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void enqueueEmail(EmailTemplate template, Recipient recipient, Map<String, Object> data, String sendId) {
        // Stamped here, at enqueue time, not recomputed at delivery — otherwise a message that sat in
        // the outbox through an outage is delivered with a fresh deadline and any staleness guard is
        // unreachable (skillars-deferred-91 code review).
        NotificationEmailPayload payload = new NotificationEmailPayload(
            template.name(), recipient.getEmail(), recipient.getLangKey(), sendId,
            Instant.now().plus(DELIVERY_DEADLINE), data);
        try {
            outboxService.enqueue(AGGREGATE_TYPE, objectMapper.writeValueAsString(payload));
            outboxService.requestDrainAfterCommit();
        } catch (JsonProcessingException e) {
            // A small template + a string map; serialisation should never fail. Loud log, not a
            // silent drop — this is a notification a committed transaction promised.
            log.error("[NOTIFICATION_EMAIL_ENQUEUE_FAILED] template={} to={} sendId={} — email NOT enqueued",
                payload.template(), payload.toAddress(), payload.sendId(), e);
        }
    }

    /**
     * Outbox payload for a re-drivable transactional email.
     *
     * <p>{@code deadline} is nullable only so rows enqueued before it was introduced still
     * deserialise; {@code NotificationEmailOutboxHandler} falls back for those.
     */
    public record NotificationEmailPayload(String template, String toAddress, String langKey,
                                           String sendId, Instant deadline, Map<String, Object> data) {
    }
}
