package com.softropic.skillars.platform.video.service;

import com.softropic.skillars.infrastructure.video.VideoProviderAdapter;
import com.softropic.skillars.platform.video.BaseVideoIT;
import com.softropic.skillars.platform.video.contract.AccessState;
import com.softropic.skillars.platform.video.contract.OperationalState;
import com.softropic.skillars.platform.video.contract.VideoWebhookStatus;
import com.softropic.skillars.platform.video.contract.Visibility;
import com.softropic.skillars.platform.video.repo.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import static org.assertj.core.api.Assertions.assertThat;

@TestPropertySource(properties = {
    "app.video.webhook.max-attempts=2",
    "app.video.webhook.processor-delay-ms=86400000"  // prevent background scheduler from racing with manual calls
})
class WebhookPipelineIT extends BaseVideoIT {

    @MockitoBean
    VideoProviderAdapter videoProviderAdapter;

    @Autowired WebhookEventProcessorScheduler scheduler;
    @Autowired VideoWebhookEventRepository webhookEventRepository;
    @Autowired VideoRepository videoRepository;
    @Autowired UploadSessionRepository uploadSessionRepository;
    @Autowired PlaybackTokenRepository playbackTokenRepository;

    @BeforeEach
    void setUp() {
        playbackTokenRepository.deleteAll();
        uploadSessionRepository.deleteAll();
        webhookEventRepository.deleteAll();
        videoRepository.deleteAll();
    }

    @Test
    void processPending_encodingSuccess_advancesVideoToReady() {
        Video video = seedVideo(OperationalState.PROCESSING, "asset-encode-ok");
        VideoWebhookEvent seeded = seedWebhookEvent("asset-encode-ok:video.encoding.success:1000", "video.encoding.success", "asset-encode-ok");

        scheduler.processPending();

        assertThat(videoRepository.findById(video.getId()).orElseThrow().getOperationalState())
            .isEqualTo(OperationalState.READY);
        VideoWebhookEvent evt = webhookEventRepository.findById(seeded.getId()).orElseThrow();
        assertThat(evt.getStatus()).isEqualTo(VideoWebhookStatus.COMPLETED);
        assertThat(evt.getProcessedAt()).isNotNull();
    }

    @Test
    void processPending_uploadSuccess_advancesVideoToProcessing() {
        Video video = seedVideo(OperationalState.UPLOADING, "asset-upload-ok");
        seedWebhookEvent("asset-upload-ok:video.upload.success:1000", "video.upload.success", "asset-upload-ok");

        scheduler.processPending();

        assertThat(videoRepository.findById(video.getId()).orElseThrow().getOperationalState())
            .isEqualTo(OperationalState.PROCESSING);
    }

    @Test
    void processPending_encodingFailed_advancesVideoToFailed() {
        Video video = seedVideo(OperationalState.PROCESSING, "asset-encode-fail");
        seedWebhookEvent("asset-encode-fail:video.encoding.failed:1000", "video.encoding.failed", "asset-encode-fail");

        scheduler.processPending();

        assertThat(videoRepository.findById(video.getId()).orElseThrow().getOperationalState())
            .isEqualTo(OperationalState.FAILED);
    }

    @Test
    void processPending_completedEvent_isNotReprocessed() {
        Video video = seedVideo(OperationalState.PROCESSING, "asset-already-done");
        VideoWebhookEvent evt = seedWebhookEvent("asset-already-done:video.encoding.success:1000",
            "video.encoding.success", "asset-already-done");
        evt.setStatus(VideoWebhookStatus.COMPLETED);
        webhookEventRepository.save(evt);

        scheduler.processPending(); // finds zero PENDING events

        assertThat(videoRepository.findById(video.getId()).orElseThrow().getOperationalState())
            .isEqualTo(OperationalState.PROCESSING); // unchanged
    }

    @Test
    void processPending_permanentFailure_reachesDeadLetterAfterMaxAttempts() {
        // READY → PROCESSING is an invalid transition — throws TerminalStateViolationException
        // This forces handleFailure() path without mocking VideoLifecycleService
        seedVideo(OperationalState.READY, "asset-dlq");
        VideoWebhookEvent evt = seedWebhookEvent("asset-dlq:video.upload.success:2000",
            "video.upload.success", "asset-dlq");

        scheduler.processPending(); // attempt 1 → attempt_count=1, status=PENDING

        VideoWebhookEvent afterFirst = webhookEventRepository.findById(evt.getId()).orElseThrow();
        assertThat(afterFirst.getAttemptCount()).isEqualTo(1);
        assertThat(afterFirst.getStatus()).isEqualTo(VideoWebhookStatus.PENDING);
        assertThat(afterFirst.getErrorMessage()).isNotBlank();

        scheduler.processPending(); // attempt 2 >= maxAttempts(2) → status=FAILED

        VideoWebhookEvent afterSecond = webhookEventRepository.findById(evt.getId()).orElseThrow();
        assertThat(afterSecond.getAttemptCount()).isEqualTo(2);
        assertThat(afterSecond.getStatus()).isEqualTo(VideoWebhookStatus.FAILED);
    }

    @Test
    void processPending_unknownEventType_completesWithoutStateChange() {
        Video video = seedVideo(OperationalState.PROCESSING, "asset-unknown-event");
        VideoWebhookEvent seeded = seedWebhookEvent("asset-unknown-event:video.unknown.type:1000", "video.unknown.type", "asset-unknown-event");

        scheduler.processPending();

        assertThat(videoRepository.findById(video.getId()).orElseThrow().getOperationalState())
            .isEqualTo(OperationalState.PROCESSING); // unchanged
        assertThat(webhookEventRepository.findById(seeded.getId()).orElseThrow().getStatus())
            .isEqualTo(VideoWebhookStatus.COMPLETED);
    }

    private Video seedVideo(OperationalState opState, String providerAssetId) {
        Video v = new Video();
        v.setOwnerId("owner-webhook-it");
        v.setProvider("bunny");
        v.setProviderAssetId(providerAssetId);
        v.setTitle("test-webhook.mp4");
        v.setOperationalState(opState);
        v.setAccessState(AccessState.ACTIVE);
        v.setVisibility(Visibility.PRIVATE);
        return videoRepository.save(v);
    }

    private VideoWebhookEvent seedWebhookEvent(String eventId, String eventType, String providerAssetId) {
        VideoWebhookEvent evt = new VideoWebhookEvent();
        evt.setEventId(eventId);
        evt.setEventType(eventType);
        evt.setProviderAssetId(providerAssetId);
        evt.setRawPayload("{\"EventType\":\"" + eventType + "\",\"VideoGuid\":\"" + providerAssetId + "\",\"Timestamp\":1000}");
        evt.setStatus(VideoWebhookStatus.PENDING);
        return webhookEventRepository.save(evt);
    }
}
