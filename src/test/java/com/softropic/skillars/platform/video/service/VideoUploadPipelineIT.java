package com.softropic.skillars.platform.video.service;

import com.softropic.skillars.infrastructure.video.VideoMetadata;
import com.softropic.skillars.infrastructure.video.VideoProviderAdapter;
import com.softropic.skillars.platform.video.BaseVideoIT;
import com.softropic.skillars.platform.video.contract.AccessState;
import com.softropic.skillars.platform.video.contract.OperationalState;
import com.softropic.skillars.platform.video.contract.QuotaProvider;
import com.softropic.skillars.platform.video.contract.UploadSessionStatus;
import com.softropic.skillars.platform.video.contract.VideoWebhookStatus;
import com.softropic.skillars.platform.video.contract.Visibility;
import com.softropic.skillars.platform.video.contract.event.VideoUploadedEvent;
import com.softropic.skillars.platform.video.contract.exception.VideoProviderException;
import com.softropic.skillars.platform.video.repo.UploadSession;
import com.softropic.skillars.platform.video.repo.UploadSessionRepository;
import com.softropic.skillars.platform.video.repo.Video;
import com.softropic.skillars.platform.video.repo.VideoRepository;
import com.softropic.skillars.platform.video.repo.VideoWebhookEvent;
import com.softropic.skillars.platform.video.repo.VideoWebhookEventRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@TestPropertySource(properties = {
    "app.video.webhook.processor-delay-ms=86400000",
    "app.video.reconciliation.fixed-delay-ms=86400000",
    "app.video.upload.expiry-scheduler-delay-ms=86400000"
})
@RecordApplicationEvents
class VideoUploadPipelineIT extends BaseVideoIT {

    @MockitoBean
    VideoProviderAdapter videoProviderAdapter;

    @MockitoBean
    QuotaProvider quotaProvider;

    // Mocked to isolate upload pipeline tests from the async moderation pipeline.
    // upload.success publishes VideoUploadedEvent which triggers ModerationOrchestrationService;
    // without this mock the async pipeline races test assertions and creates video_moderation_scans
    // rows (ON DELETE RESTRICT) that would block setUp's deleteAll().
    @MockitoBean
    ModerationOrchestrationService moderationOrchestrationService;

    @Autowired WebhookEventProcessorScheduler scheduler;
    @Autowired VideoRepository videoRepository;
    @Autowired VideoWebhookEventRepository webhookEventRepository;
    @Autowired UploadSessionRepository uploadSessionRepository;

    @Autowired
    ApplicationEvents applicationEvents;

    @BeforeEach
    void setUp() {
        uploadSessionRepository.deleteAll();
        webhookEventRepository.deleteAll();
        videoRepository.deleteAll();
        when(videoProviderAdapter.getVideoMetadata(anyString()))
            .thenReturn(new VideoMetadata(120_000L, 52_428_800L));
    }

    @Test
    void encodingSuccess_setsMetadataCommitsQuotaTransitionsToReady() {
        // Video must be in TRANSCODING for encoding.success to call completeTranscoding().
        // PROCESSING videos record encodingCompletedAt and wait for the moderation pipeline.
        Video video = seedVideo(OperationalState.TRANSCODING, "asset-1");
        seedUploadSession(video.getId(), "test-handle-1");
        seedWebhookEvent("asset-1:video.encoding.success", "video.encoding.success", "asset-1");

        scheduler.processPending();

        Video updated = videoRepository.findById(video.getId()).orElseThrow();
        assertThat(updated.getOperationalState()).isEqualTo(OperationalState.READY);
        assertThat(updated.getDurationMs()).isEqualTo(120_000L);
        assertThat(updated.getStorageBytes()).isEqualTo(52_428_800L);
        verify(quotaProvider).commit("test-handle-1");

        VideoWebhookEvent evt = webhookEventRepository.findAll().iterator().next();
        assertThat(evt.getStatus()).isEqualTo(VideoWebhookStatus.COMPLETED);
    }

    @Test
    void encodingSuccess_providerMetadataCallFails_stillTransitionsToReady() {
        when(videoProviderAdapter.getVideoMetadata(anyString()))
            .thenThrow(new VideoProviderException("getVideoMetadata", new RuntimeException("network error")));

        Video video = seedVideo(OperationalState.TRANSCODING, "asset-2");
        seedUploadSession(video.getId(), "test-handle-2");
        seedWebhookEvent("asset-2:video.encoding.success", "video.encoding.success", "asset-2");

        scheduler.processPending(); // must not propagate exception

        Video updated = videoRepository.findById(video.getId()).orElseThrow();
        assertThat(updated.getOperationalState()).isEqualTo(OperationalState.READY);
        verify(quotaProvider).commit("test-handle-2");
    }

    @Test
    void encodingFailed_releasesQuotaAndTransitionsToFailed() {
        Video video = seedVideo(OperationalState.PROCESSING, "asset-3");
        seedUploadSession(video.getId(), "test-handle-3");
        seedWebhookEvent("asset-3:video.encoding.failed", "video.encoding.failed", "asset-3");

        scheduler.processPending();

        assertThat(videoRepository.findById(video.getId()).orElseThrow().getOperationalState())
            .isEqualTo(OperationalState.FAILED);
        verify(quotaProvider).release("test-handle-3");
    }

    @Test
    void encodingFailed_noSession_gracefullyTransitionsToFailed() {
        // No upload session seeded
        Video video = seedVideo(OperationalState.PROCESSING, "asset-4");
        seedWebhookEvent("asset-4:video.encoding.failed", "video.encoding.failed", "asset-4");

        scheduler.processPending(); // must not throw

        assertThat(videoRepository.findById(video.getId()).orElseThrow().getOperationalState())
            .isEqualTo(OperationalState.FAILED);
        verify(quotaProvider, never()).release(anyString());
    }

    @Test
    void encodingSuccess_sessionHasNullReservationHandle_stillTransitionsToReady() {
        Video video = seedVideo(OperationalState.TRANSCODING, "asset-5");
        seedUploadSessionWithNullHandle(video.getId());
        seedWebhookEvent("asset-5:video.encoding.success", "video.encoding.success", "asset-5");

        scheduler.processPending(); // must not throw

        assertThat(videoRepository.findById(video.getId()).orElseThrow().getOperationalState())
            .isEqualTo(OperationalState.READY);
        verify(quotaProvider, never()).commit(anyString());
    }

    @Test
    void videoUploadSuccess_transitionsToProcessingAndPublishesVideoUploadedEvent() {
        // upload.success advances UPLOADING→PROCESSING and publishes VideoUploadedEvent,
        // which triggers the moderation pipeline (mocked in this test class).
        Video video = seedVideo(OperationalState.UPLOADING, "asset-6");
        seedWebhookEvent("asset-6:video.upload.success", "video.upload.success", "asset-6");

        scheduler.processPending();

        assertThat(videoRepository.findById(video.getId()).orElseThrow().getOperationalState())
            .isEqualTo(OperationalState.PROCESSING);

        long eventCount = applicationEvents.stream(VideoUploadedEvent.class)
            .filter(e -> e.videoId().equals(video.getId()))
            .count();
        assertThat(eventCount).isEqualTo(1);
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private Video seedVideo(OperationalState opState, String providerAssetId) {
        Video v = new Video();
        v.setOwnerId("owner-pipeline-it");
        v.setProvider("bunny");
        v.setProviderAssetId(providerAssetId);
        v.setTitle("test-pipeline.mp4");
        v.setOperationalState(opState);
        v.setAccessState(AccessState.ACTIVE);
        v.setVisibility(Visibility.PRIVATE);
        return videoRepository.save(v);
    }

    private UploadSession seedUploadSession(UUID videoId, String reservationHandle) {
        UploadSession s = new UploadSession();
        s.setVideoId(videoId);
        s.setStatus(UploadSessionStatus.PENDING);
        s.setReservedBytes(1024L);
        s.setReservationHandle(reservationHandle);
        s.setExpiresAt(Instant.now().plusSeconds(3600));
        // Must match the seeded video's providerAssetId so findFirstByVideoIdAndProviderUploadId anchors correctly
        s.setProviderUploadId(videoRepository.findById(videoId).map(Video::getProviderAssetId).orElse(null));
        return uploadSessionRepository.save(s);
    }

    private UploadSession seedUploadSessionWithNullHandle(UUID videoId) {
        UploadSession s = new UploadSession();
        s.setVideoId(videoId);
        s.setStatus(UploadSessionStatus.PENDING);
        s.setReservedBytes(1024L);
        s.setReservationHandle(null);
        s.setExpiresAt(Instant.now().plusSeconds(3600));
        s.setProviderUploadId(videoRepository.findById(videoId).map(Video::getProviderAssetId).orElse(null));
        return uploadSessionRepository.save(s);
    }

    private VideoWebhookEvent seedWebhookEvent(String eventId, String eventType, String providerAssetId) {
        VideoWebhookEvent evt = new VideoWebhookEvent();
        evt.setEventId(eventId);
        evt.setEventType(eventType);
        evt.setProviderAssetId(providerAssetId);
        evt.setRawPayload("{\"VideoLibraryId\":12345,\"VideoGuid\":\"" + providerAssetId + "\",\"Status\":3}");
        evt.setStatus(VideoWebhookStatus.PENDING);
        return webhookEventRepository.save(evt);
    }
}
