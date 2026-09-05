package com.softropic.skillars.platform.video.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.softropic.skillars.platform.outbox.service.OutboxService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * skillars-deferred-92 AC5 — puts {@code ModerationSlaMonitorService}'s two intents on the durable
 * outbox instead of a bare {@code ApplicationEventPublisher} call.
 *
 * <p>Before this, a crash after {@code findScanningOlderThan()} returned but before the events
 * published lost that cycle's retry and alert intents outright. The impact was bounded — the next
 * five-minute cycle re-selects the same stuck videos — but it was the last uncovered case in the
 * ledger's {@code AFTER_COMMIT}-reliability catalogue, and skillars-deferred-91's generic outbox made
 * closing it mechanical.
 *
 * <p><strong>What the outbox buys, precisely.</strong> The intent is now committed atomically with
 * the state change that justifies it — the retry-count increment for a retry, the {@code FAILED}
 * transition for an alert. Either both are durable or neither is, so the counter can no longer be
 * incremented for a retry that was never requested (which would burn one of the video's finite
 * attempts for nothing).
 *
 * <p>{@link Propagation#MANDATORY} for the same reason as
 * {@code NotificationOutboxSupport.enqueueEmail}: joining the caller's transaction is the whole
 * point, so a caller with no transaction must fail loudly rather than quietly opening one and
 * reintroducing the gap. Both call sites are inside {@code ModerationSlaMonitorService}'s
 * {@code REQUIRES_NEW} per-video template.
 *
 * <p>Follows {@code SluSnapshotOutboxSupport} / {@code SluSnapshotOutboxHandler} as the reference
 * pair, and lives in {@code platform.video} rather than {@code platform.outbox} — the generic outbox
 * owns the table and the drain; each domain owns its own support/handler.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ModerationOutboxSupport {

    /** A request to re-run the moderation pipeline for a video stuck in {@code SCANNING}. */
    public static final String RETRY_AGGREGATE_TYPE = "VIDEO_MODERATION_RETRY";

    /** An operator-facing alert about a video whose moderation has permanently failed. */
    public static final String ADMIN_ALERT_AGGREGATE_TYPE = "VIDEO_MODERATION_ADMIN_ALERT";

    private final OutboxService outboxService;
    private final ObjectMapper objectMapper;

    @Transactional(propagation = Propagation.MANDATORY)
    public void enqueueRetry(UUID videoId, String ownerId) {
        enqueue(RETRY_AGGREGATE_TYPE, new ModerationRetryPayload(videoId, ownerId),
            "videoId=" + videoId);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void enqueueAdminAlert(UUID videoId, String ownerId, String subject, String body, boolean urgent) {
        enqueue(ADMIN_ALERT_AGGREGATE_TYPE,
            new ModerationAdminAlertPayload(videoId, ownerId, subject, body, urgent),
            "videoId=" + videoId + " subject=" + subject);
    }

    private void enqueue(String aggregateType, Object payload, String context) {
        try {
            outboxService.enqueue(aggregateType, objectMapper.writeValueAsString(payload));
            outboxService.requestDrainAfterCommit();
        } catch (JsonProcessingException e) {
            // Five scalar fields; serialisation cannot realistically fail. Rethrowing rather than
            // logging is deliberate and matches NotificationOutboxSupport: the caller's transaction is
            // still open, so swallowing here would commit the retry-count increment (or the FAILED
            // transition) without the intent it is supposed to be atomic with — the precise failure
            // this class exists to remove. Rolling the per-video REQUIRES_NEW transaction back costs
            // nothing: the next SLA cycle re-selects the video in five minutes.
            throw new IllegalStateException(
                "[MODERATION_OUTBOX_ENQUEUE_FAILED] could not serialise " + aggregateType + " " + context, e);
        }
    }

    public record ModerationRetryPayload(UUID videoId, String ownerId) {
    }

    public record ModerationAdminAlertPayload(UUID videoId, String ownerId, String subject, String body,
                                              boolean urgent) {
    }
}
