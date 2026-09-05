package com.softropic.skillars.platform.video.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.softropic.skillars.platform.outbox.contract.OutboxMessageHandler;
import com.softropic.skillars.platform.video.contract.OperationalState;
import com.softropic.skillars.platform.video.contract.event.VideoModerationRetryEvent;
import com.softropic.skillars.platform.video.repo.Video;
import com.softropic.skillars.platform.video.repo.VideoRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import java.io.UncheckedIOException;

/**
 * skillars-deferred-92 AC5 — re-drives a moderation retry that {@code ModerationSlaMonitorService}
 * committed to the outbox.
 *
 * <h2>Idempotence (AC5.2)</h2>
 *
 * A repeat retry-request for a video that is no longer {@code SCANNING} is a <strong>documented
 * no-op, not an error</strong>: the video has since finished moderation, been locked, failed, or been
 * deleted, and re-running the pipeline over it would be actively wrong. Returning normally lets the
 * outbox row complete, which is correct — the intent has been honoured, it simply turned out to be
 * unnecessary. Throwing would create an immortal poison row that consumes a claim slot forever and
 * eventually trips {@code [OUTBOX_STUCK]} for a situation that needs no human.
 *
 * <p>The check is made <em>here</em>, not left to
 * {@code ModerationOrchestrationService.onModerationRetry}'s own identical guard, for two reasons: it
 * makes the no-op a property of this handler that a unit test can assert, and
 * {@code onModerationRetry} is {@code @Async}, so its decision happens after this handler has already
 * returned and the row has already been deleted.
 *
 * <p>Publishing rather than calling the pipeline directly is deliberate: the pipeline is long-running
 * blocking I/O (Arachnid, Video Intelligence, the minor-safety gate) and belongs on
 * {@code moderationTaskExecutor}, not on the outbox drain thread, which has a whole table to get
 * through. The outbox's guarantee is that the intent survives a crash, which is discharged the moment
 * the event is dispatched.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ModerationRetryOutboxHandler implements OutboxMessageHandler {

    private final VideoRepository videoRepository;
    private final ApplicationEventPublisher publisher;
    private final ObjectMapper objectMapper;

    @Override
    public String aggregateType() {
        return ModerationOutboxSupport.RETRY_AGGREGATE_TYPE;
    }

    @Override
    public void handle(String payload) {
        final ModerationOutboxSupport.ModerationRetryPayload p;
        try {
            p = objectMapper.readValue(payload, ModerationOutboxSupport.ModerationRetryPayload.class);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            // A malformed payload can never be re-driven; rethrow so the row keeps attempts++ /
            // last_error and [OUTBOX_STUCK] eventually surfaces it, rather than being dropped.
            throw new UncheckedIOException(e);
        }

        OperationalState state = videoRepository.findById(p.videoId())
            .map(Video::getOperationalState)
            .orElse(null);

        if (state == null) {
            log.info("[VIDEO_MODERATION_RETRY] no-op — videoId={} no longer exists (deleted since the "
                + "SLA cycle that requested the retry)", p.videoId());
            return;
        }
        if (state != OperationalState.SCANNING) {
            log.info("[VIDEO_MODERATION_RETRY] no-op — videoId={} left SCANNING (state={}); the "
                + "moderation this retry was requested for has already concluded", p.videoId(), state);
            return;
        }

        publisher.publishEvent(new VideoModerationRetryEvent(p.videoId(), p.ownerId()));
        log.info("[VIDEO_MODERATION_RETRY] re-dispatched moderation pipeline for videoId={}", p.videoId());
    }
}
