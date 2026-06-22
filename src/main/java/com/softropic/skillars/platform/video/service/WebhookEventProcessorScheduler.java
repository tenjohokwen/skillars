package com.softropic.skillars.platform.video.service;

import com.softropic.skillars.platform.video.config.VideoProperties;
import com.softropic.skillars.platform.video.contract.OperationalState;
import com.softropic.skillars.platform.video.contract.QuotaProvider;
import com.softropic.skillars.platform.video.contract.UploadSessionStatus;
import com.softropic.skillars.platform.video.contract.VideoWebhookStatus;
import com.softropic.skillars.platform.video.contract.event.VideoUploadedEvent;
import com.softropic.skillars.platform.video.contract.exception.TerminalStateViolationException;
import com.softropic.skillars.platform.video.repo.UploadSession;
import com.softropic.skillars.platform.video.repo.UploadSessionRepository;
import com.softropic.skillars.platform.video.repo.Video;
import com.softropic.skillars.platform.video.repo.VideoRepository;
import com.softropic.skillars.platform.video.repo.VideoWebhookEvent;
import com.softropic.skillars.platform.video.repo.VideoWebhookEventRepository;
import io.micrometer.observation.annotation.Observed;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Scheduler responsible for processing pending video provider webhooks.
 *
 * <p>Idempotency is guaranteed at the database level by the {@code event_id} column
 * uniqueness constraint in the {@code video_webhook_events} table. Webhook reception
 * logic ensures only unique events are stored; this scheduler only processes
 * events marked as {@link VideoWebhookStatus#PENDING}.
 *
 * <p>Concurrent processing is prevented using {@code FOR UPDATE SKIP LOCKED}
 * during event retrieval, ensuring that only one scheduler node processes a
 * given event at any time.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WebhookEventProcessorScheduler {

    private final VideoWebhookEventRepository webhookEventRepository;
    private final VideoRepository videoRepository;
    private final VideoLifecycleService videoLifecycleService;
    private final VideoProperties properties;
    private final TransactionTemplate transactionTemplate;
    private final VideoMetrics videoMetrics;
    private final UploadSessionRepository uploadSessionRepository;
    private final VideoService videoService;
    private final ApplicationEventPublisher publisher;
    private final QuotaProvider quotaProvider;

    @Observed(name = "video.webhook.processQueue")
    @Scheduled(fixedDelayString = "${app.video.webhook.processor-delay-ms:5000}",
               initialDelayString = "${app.video.webhook.processor-delay-ms:5000}")
    public void processPending() {
        videoMetrics.updateWebhookQueueDepth(
            webhookEventRepository.countByStatus(VideoWebhookStatus.PENDING));
        videoMetrics.updateActiveUploadSessions(
            uploadSessionRepository.countByStatus(UploadSessionStatus.PENDING));

        int batchSize = properties.getReconciliation().getBatchSize();

        List<VideoWebhookEvent> events = Objects.requireNonNullElse(
            transactionTemplate.execute(status ->
                webhookEventRepository.findPendingForUpdate(batchSize)),
            List.of());

        for (VideoWebhookEvent event : events) {
            long eventStart = System.nanoTime();
            MDC.put("webhookEventId", event.getId().toString());
            MDC.put("eventType", event.getEventType());
            MDC.put("providerAssetId", event.getProviderAssetId());
            try {
                transactionTemplate.execute(txStatus -> {
                    VideoWebhookEvent e = webhookEventRepository.findById(event.getId()).orElse(null);
                    if (e == null || e.getStatus() != VideoWebhookStatus.PENDING) return null;
                    e.setStatus(VideoWebhookStatus.PROCESSING);
                    webhookEventRepository.save(e);
                    return null;
                });

                dispatchEvent(event);

                transactionTemplate.execute(txStatus -> {
                    VideoWebhookEvent e = webhookEventRepository.findById(event.getId()).orElseThrow();
                    e.setStatus(VideoWebhookStatus.COMPLETED);
                    e.setProcessedAt(Instant.now());
                    webhookEventRepository.save(e);
                    return null;
                });

                videoMetrics.recordWebhookProcessingLatency(
                    event.getEventType(), "success", System.nanoTime() - eventStart);

            } catch (Exception ex) {
                videoMetrics.recordWebhookProcessingLatency(
                    event.getEventType(), "error", System.nanoTime() - eventStart);
                log.warn("Webhook event processing failed: {}", event.getId(), ex);
                handleFailure(event, ex);
            } finally {
                MDC.remove("webhookEventId");
                MDC.remove("eventType");
                MDC.remove("providerAssetId");
            }
        }
    }

    private void dispatchEvent(VideoWebhookEvent event) {
        Optional<Video> videoOpt = videoRepository.findByProviderAssetId(event.getProviderAssetId());
        if (videoOpt.isEmpty()) {
            log.warn("No video found for providerAssetId={}, skipping event", event.getProviderAssetId());
            return;
        }
        Video video = videoOpt.get();
        UUID videoId = video.getId();
        switch (event.getEventType()) {
            case "video.upload.success" -> {
                // Publish event inside the TX so @TransactionalEventListener(AFTER_COMMIT) fires correctly.
                transactionTemplate.execute(status -> {
                    videoLifecycleService.transitionOperationalState(videoId, OperationalState.PROCESSING);
                    publisher.publishEvent(new VideoUploadedEvent(videoId, video.getOwnerId()));
                    return null;
                });
            }
            case "video.encoding.success" -> {
                // Compensate for out-of-order delivery: if Status=3 arrives before Status=7,
                // the video is still UPLOADING. Advance through PROCESSING first.
                if (video.getOperationalState() == OperationalState.UPLOADING) {
                    log.warn("video.encoding.success arrived before video.upload.success for videoId={} — compensating", videoId);
                    transactionTemplate.execute(status -> {
                        try {
                            videoLifecycleService.transitionOperationalState(videoId, OperationalState.PROCESSING);
                        } catch (TerminalStateViolationException e) {
                            log.debug("Compensating UPLOADING→PROCESSING skipped — state already advanced for videoId={}", videoId);
                        }
                        // Record encoding completion so moderation's advanceToTranscoding() uses the
                        // fast-path instead of calling triggerTranscoding() redundantly.
                        videoRepository.findById(videoId).ifPresent(v -> {
                            v.setEncodingCompletedAt(Instant.now());
                            videoRepository.save(v);
                        });
                        return null;
                    });
                    // In-memory video is stale (still shows UPLOADING); stop here — moderation pipeline
                    // will pick up encodingCompletedAt once the PROCESSING→SCANNING transition fires.
                    return;
                }
                // If still in PROCESSING: moderation listener is queued. Record encodingCompletedAt
                // so advanceToTranscoding() uses the fast-path. Do NOT call completeTranscoding() —
                // that would bypass all three moderation layers.
                if (video.getOperationalState() == OperationalState.PROCESSING) {
                    log.info("Encoding completed while video still in PROCESSING for videoId={} — recording to prevent moderation bypass", videoId);
                    transactionTemplate.execute(status -> {
                        Video v = videoRepository.findById(videoId).orElse(null);
                        if (v != null) {
                            v.setEncodingCompletedAt(Instant.now());
                            videoRepository.save(v);
                        }
                        return null;
                    });
                    return;
                }
                // If video is in SCANNING (moderation in progress), record encoding completion.
                if (video.getOperationalState() == OperationalState.SCANNING) {
                    log.info("Encoding completed while video in SCANNING state for videoId={} — recording completion", videoId);
                    transactionTemplate.execute(status -> {
                        Video v = videoRepository.findById(videoId).orElse(null);
                        if (v != null) {
                            v.setEncodingCompletedAt(Instant.now());
                            videoRepository.save(v);
                        }
                        return null;
                    });
                    // Self-heal: re-read state after encoding completion is persisted.
                    // If moderation already advanced to TRANSCODING, complete it now.
                    OperationalState currentState = transactionTemplate.execute(status ->
                        videoRepository.findById(videoId).map(Video::getOperationalState).orElse(null));
                    if (currentState == OperationalState.TRANSCODING) {
                        log.info("Video {} now in TRANSCODING after encoding.success — completing TRANSCODING→READY", videoId);
                        videoService.completeTranscoding(videoId);
                    }
                    // If still SCANNING: moderation will pick up encodingCompletedAt and use fast-path.
                    return;
                }
                videoService.completeTranscoding(videoId);
            }
            case "video.upload.failed" ->
                // Bunny Status=8: TUS upload failed at the provider
                videoService.failTranscoding(videoId);
            case "video.encoding.failed" -> {
                // If encoding fails while moderation is running, fail the video immediately.
                if (video.getOperationalState() == OperationalState.SCANNING) {
                    log.error("Encoding failed while video in SCANNING state for videoId={} — transitioning to FAILED", videoId);
                    boolean transitioned;
                    try {
                        transactionTemplate.execute(status -> {
                            videoLifecycleService.transitionOperationalState(videoId, OperationalState.FAILED);
                            return null;
                        });
                        transitioned = true;
                    } catch (TerminalStateViolationException e) {
                        log.debug("encoding.failed FAILED transition skipped — video already in terminal state for videoId={}", videoId);
                        transitioned = false;
                    }
                    // Only release quota if we won the SCANNING→FAILED race to avoid double-release.
                    // Re-read providerAssetId from DB — pre-loop video object may be stale.
                    if (transitioned) {
                        String freshAssetId = transactionTemplate.execute(status ->
                            videoRepository.findById(videoId).map(Video::getProviderAssetId).orElse(null));
                        releaseQuota(videoId, freshAssetId);
                    }
                    return;
                }
                videoService.failTranscoding(videoId);
            }
            default ->
                log.warn("Unknown webhook event type '{}', completing without state change", event.getEventType());
        }
    }

    private void releaseQuota(UUID videoId, String providerAssetId) {
        UploadSession session = (providerAssetId != null)
            ? uploadSessionRepository.findFirstByVideoIdAndProviderUploadIdOrderByCreatedAtDesc(videoId, providerAssetId).orElse(null)
            : uploadSessionRepository.findFirstByVideoIdOrderByCreatedAtDesc(videoId).orElse(null);
        if (session != null && session.getReservationHandle() != null) {
            quotaProvider.release(session.getReservationHandle());
        } else {
            log.warn("No reservation handle found for videoId={} — quota not released", videoId);
        }
    }

    private void handleFailure(VideoWebhookEvent event, Exception ex) {
        int maxAttempts = properties.getWebhook().getMaxAttempts();
        transactionTemplate.execute(txStatus -> {
            VideoWebhookEvent e = webhookEventRepository.findById(event.getId()).orElse(null);
            if (e == null) return null;
            e.setAttemptCount(e.getAttemptCount() + 1);
            String msg = ex.getMessage();
            e.setErrorMessage(msg != null ? msg.substring(0, Math.min(msg.length(), 2000)) : "unknown error");
            if (e.getAttemptCount() >= maxAttempts) {
                e.setStatus(VideoWebhookStatus.FAILED);
                log.error("Webhook event {} dead-lettered after {} attempts", e.getId(), e.getAttemptCount());
            } else {
                e.setStatus(VideoWebhookStatus.PENDING);
            }
            webhookEventRepository.save(e);
            return null;
        });
    }
}
