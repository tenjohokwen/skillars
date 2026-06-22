package com.softropic.skillars.platform.video.service;

import com.softropic.skillars.platform.video.config.VideoProperties;
import com.softropic.skillars.platform.video.contract.OperationalState;
import com.softropic.skillars.platform.video.contract.UploadSessionStatus;
import com.softropic.skillars.platform.video.contract.VideoWebhookStatus;
import com.softropic.skillars.platform.video.contract.exception.TerminalStateViolationException;
import com.softropic.skillars.platform.video.repo.UploadSessionRepository;
import com.softropic.skillars.platform.video.repo.Video;
import com.softropic.skillars.platform.video.repo.VideoRepository;
import com.softropic.skillars.platform.video.repo.VideoWebhookEvent;
import com.softropic.skillars.platform.video.repo.VideoWebhookEventRepository;
import io.micrometer.observation.annotation.Observed;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
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
                transactionTemplate.execute(status -> {
                    videoLifecycleService.transitionOperationalState(videoId, OperationalState.PROCESSING);
                    return null;
                });
            }
            case "video.encoding.success" -> {
                // Compensate for out-of-order delivery: if Status=3 arrives before Status=7,
                // the video is still UPLOADING. Advance through PROCESSING first so that
                // completeTranscoding()'s PROCESSING→READY transition is valid.
                if (video.getOperationalState() == OperationalState.UPLOADING) {
                    log.warn("video.encoding.success arrived before video.upload.success for videoId={} — compensating", videoId);
                    transactionTemplate.execute(status -> {
                        try {
                            videoLifecycleService.transitionOperationalState(videoId, OperationalState.PROCESSING);
                        } catch (TerminalStateViolationException e) {
                            // Another node already advanced the state — safe to proceed
                            log.debug("Compensating UPLOADING→PROCESSING skipped — state already advanced for videoId={}", videoId);
                        }
                        return null;
                    });
                }
                videoService.completeTranscoding(videoId);
            }
            case "video.upload.failed" ->
                // Bunny Status=8: TUS upload failed at the provider
                // failTranscoding() handles both FAILED state transition and quota release
                videoService.failTranscoding(videoId);
            case "video.encoding.failed" ->
                videoService.failTranscoding(videoId);
            default ->
                log.warn("Unknown webhook event type '{}', completing without state change", event.getEventType());
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
