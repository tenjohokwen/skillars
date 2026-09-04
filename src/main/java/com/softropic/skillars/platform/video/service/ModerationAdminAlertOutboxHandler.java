package com.softropic.skillars.platform.video.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.softropic.skillars.platform.outbox.contract.OutboxMessageHandler;
import com.softropic.skillars.platform.video.contract.event.VideoModerationAdminAlertEvent;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
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
 * <p>Delivery is still guarded downstream: {@code VideoModerationEmailListener.onAdminAlert} refuses
 * to send when {@code platform.admin_alert_email} is blank, and logs loudly when it does.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ModerationAdminAlertOutboxHandler implements OutboxMessageHandler {

    private final ApplicationEventPublisher publisher;
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

        publisher.publishEvent(new VideoModerationAdminAlertEvent(
            p.videoId(), p.ownerId(), p.subject(), p.body(), p.urgent()));
        log.info("[VIDEO_MODERATION_ADMIN_ALERT] re-dispatched alert for videoId={} subject={}",
            p.videoId(), p.subject());
    }
}
