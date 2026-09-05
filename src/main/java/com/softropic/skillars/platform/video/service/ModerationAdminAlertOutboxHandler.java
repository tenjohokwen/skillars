package com.softropic.skillars.platform.video.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.softropic.skillars.platform.outbox.contract.OutboxMessageHandler;
import com.softropic.skillars.platform.video.contract.ModerationAdminAlertSender;
import com.softropic.skillars.platform.video.contract.event.VideoModerationAdminAlertEvent;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.io.UncheckedIOException;

/**
 * skillars-deferred-92 AC5 — re-drives the operator alert for a video whose moderation permanently
 * failed. The companion of {@link ModerationRetryOutboxHandler}; see
 * {@link ModerationOutboxSupport} for why both intents moved onto the outbox.
 *
 * <h2>Idempotence</h2>
 *
 * Unconditional, and deliberately so. There is no state to check and nothing to suppress: the alert
 * describes a transition that has already committed, and a duplicate is one extra operator email for
 * a video that genuinely did fail permanently. That is strictly the right side to err on — this alert
 * is the only signal a human gets that a video needs manual review, so suppressing a possible
 * duplicate at the cost of possibly suppressing the only copy would be the wrong trade.
 *
 * <p>Delivery is still guarded downstream: {@code VideoModerationEmailListener} refuses to send when
 * {@code platform.admin_alert_email} is blank, and logs loudly when it does.
 *
 * <h2>Why the send is synchronous (skillars-deferred-92 code review, decision D1)</h2>
 *
 * This handler used to re-{@code publishEvent} the alert. {@code VideoModerationEmailListener}
 * ({@code @EventListener}) turned that into an {@code Envelope}, which {@code MailManager
 * .sendEmailFromTemplate} ({@code @Async} + {@code @TransactionalEventListener(AFTER_COMMIT)})
 * delivered — <em>after</em> the drain transaction had committed and deleted this row. A send failure
 * therefore marked the envelope {@code FAILED} against an outbox row that no longer existed: no
 * {@code attempts++}, no backoff, no {@code [OUTBOX_STUCK]}, and the durability this whole aggregate
 * type exists for was cosmetic. {@link ModerationAdminAlertSender#sendAdminAlertSync} closes that
 * loop by making delivery either happen or throw before {@code handle()} returns, mirroring
 * {@code NotificationEmailOutboxHandler}.
 *
 * <p>{@link ModerationRetryOutboxHandler} deliberately keeps its event dispatch: its payload is a
 * request to re-run the moderation pipeline, not an email, so there is no delivery outcome to read
 * back and its guarantee really is discharged the moment the event is dispatched.
 */
@Component
@RequiredArgsConstructor
public class ModerationAdminAlertOutboxHandler implements OutboxMessageHandler {

    private final ModerationAdminAlertSender adminAlertSender;
    private final ObjectMapper objectMapper;

    @Override
    public String aggregateType() {
        return ModerationOutboxSupport.ADMIN_ALERT_AGGREGATE_TYPE;
    }

    @Override
    public void handle(String payload) {
        final ModerationOutboxSupport.ModerationAdminAlertPayload p;
        try {
            p = objectMapper.readValue(payload, ModerationOutboxSupport.ModerationAdminAlertPayload.class);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new UncheckedIOException(e);
        }

        // Throws on a retryable failure so the row keeps attempts++ / last_error and is re-driven
        // after its backoff; logs and returns on a permanently undeliverable one. The delivered /
        // undeliverable log line is emitted by the sender, which is the only place that knows which
        // of the two happened.
        adminAlertSender.sendAdminAlertSync(new VideoModerationAdminAlertEvent(
            p.videoId(), p.ownerId(), p.subject(), p.body(), p.urgent()));
    }
}
