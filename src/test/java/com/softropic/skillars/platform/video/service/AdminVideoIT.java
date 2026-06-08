package com.softropic.skillars.platform.video.service;

import com.softropic.skillars.infrastructure.video.AssetStatus;
import com.softropic.skillars.infrastructure.video.VideoProviderAdapter;
import com.softropic.skillars.platform.video.BaseVideoIT;
import com.softropic.skillars.platform.video.contract.*;
import com.softropic.skillars.platform.video.contract.exception.TerminalStateViolationException;
import com.softropic.skillars.platform.video.contract.exception.VideoProviderException;
import com.softropic.skillars.platform.video.repo.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class AdminVideoIT extends BaseVideoIT {

    @MockitoBean
    VideoProviderAdapter videoProviderAdapter;

    @Autowired AdminVideoService adminVideoService;
    @Autowired VideoRepository videoRepository;
    @Autowired UploadSessionRepository uploadSessionRepository;
    @Autowired PlaybackTokenRepository playbackTokenRepository;
    @Autowired VideoWebhookEventRepository webhookEventRepository;
    @Autowired ReconciliationIncidentRepository incidentRepository;

    @BeforeEach
    void setUp() {
        incidentRepository.deleteAll();
        playbackTokenRepository.deleteAll();
        uploadSessionRepository.deleteAll();
        webhookEventRepository.deleteAll();
        videoRepository.deleteAll();
    }

    @Test
    void setVideoAccessState_blocksReadyVideo_updatesAccessState() {
        Video video = seedVideo(OperationalState.READY, AccessState.ACTIVE, "asset-admin-1");

        Video updated = adminVideoService.setVideoAccessState(video.getId(), AccessState.BLOCKED);

        assertThat(updated.getAccessState()).isEqualTo(AccessState.BLOCKED);
        assertThat(videoRepository.findById(video.getId()).orElseThrow().getAccessState())
                .isEqualTo(AccessState.BLOCKED);
    }

    @Test
    void setVideoAccessState_deletedVideo_throwsTerminalStateViolation() {
        Video video = seedVideo(OperationalState.DELETED, AccessState.ACTIVE, "asset-admin-2");

        assertThatThrownBy(() -> adminVideoService.setVideoAccessState(video.getId(), AccessState.BLOCKED))
                .isInstanceOf(TerminalStateViolationException.class);
    }

    @Test
    void deleteVideo_readyVideo_marksDeletedReleasesQuotaForPendingSession() {
        Video video = seedVideo(OperationalState.READY, AccessState.ACTIVE, "asset-admin-delete");
        UploadSession pendingSession = seedPendingSession(video, "quota-handle-admin-1");
        doNothing().when(videoProviderAdapter).deleteAsset("asset-admin-delete");

        adminVideoService.deleteVideo(video.getId());

        assertThat(videoRepository.findById(video.getId()).orElseThrow().getOperationalState())
                .isEqualTo(OperationalState.DELETED);
        UploadSession refreshed = uploadSessionRepository.findById(pendingSession.getId()).orElseThrow();
        assertThat(refreshed.getStatus()).isEqualTo(UploadSessionStatus.EXPIRED);
    }

    @Test
    void deleteVideo_processingVideo_noPendingSession_marksDeleted() {
        Video video = seedVideo(OperationalState.PROCESSING, AccessState.ACTIVE, "asset-admin-del-no-sess");
        doNothing().when(videoProviderAdapter).deleteAsset("asset-admin-del-no-sess");

        adminVideoService.deleteVideo(video.getId());

        assertThat(videoRepository.findById(video.getId()).orElseThrow().getOperationalState())
                .isEqualTo(OperationalState.DELETED);
    }

    @Test
    void deleteVideo_providerThrows_videoNotDeleted() {
        Video video = seedVideo(OperationalState.READY, AccessState.ACTIVE, "asset-admin-del-fail");
        doThrow(new VideoProviderException("deleteAsset", new RuntimeException("provider down")))
                .when(videoProviderAdapter).deleteAsset("asset-admin-del-fail");

        assertThatThrownBy(() -> adminVideoService.deleteVideo(video.getId()))
                .isInstanceOf(VideoProviderException.class);

        assertThat(videoRepository.findById(video.getId()).orElseThrow().getOperationalState())
                .isEqualTo(OperationalState.READY);
    }

    @Test
    void getUploadSession_returnsSession() {
        Video video = seedVideo(OperationalState.PROCESSING, AccessState.ACTIVE, "asset-admin-sess");
        UploadSession session = seedPendingSession(video, "handle-get-session");

        UploadSession found = adminVideoService.getUploadSession(session.getId());

        assertThat(found.getId()).isEqualTo(session.getId());
        assertThat(found.getVideoId()).isEqualTo(video.getId());
    }

    @Test
    void listVideoSessions_returnsAllSessionsOrderedByCreatedAtDesc() {
        Video video = seedVideo(OperationalState.PROCESSING, AccessState.ACTIVE, "asset-admin-sessions");
        UploadSession s1 = seedPendingSession(video, "handle-1");
        UploadSession s2 = seedPendingSession(video, "handle-2");

        List<UploadSession> sessions = adminVideoService.listVideoSessions(video.getId());

        assertThat(sessions).hasSizeGreaterThanOrEqualTo(2);
        assertThat(sessions).extracting(UploadSession::getId).contains(s1.getId(), s2.getId());
    }

    @Test
    void triggerReconciliation_processingVideoReadyAtProvider_correctedToReady() {
        Video video = seedVideo(OperationalState.PROCESSING, AccessState.ACTIVE, "asset-admin-recon-1");
        when(videoProviderAdapter.getAssetStatus("asset-admin-recon-1")).thenReturn(AssetStatus.READY);

        ReconcileResponse response = adminVideoService.triggerReconciliation(video.getId());

        assertThat(response.operationalState()).isEqualTo(OperationalState.READY);
        assertThat(response.incident()).isNotNull();
        assertThat(response.incident().incidentType()).isEqualTo(ReconciliationIncidentType.STATE_CORRECTED);
    }

    @Test
    void triggerReconciliation_processingVideoMissingAtProvider_markedFailed() {
        Video video = seedVideo(OperationalState.PROCESSING, AccessState.ACTIVE, "asset-admin-recon-2");
        when(videoProviderAdapter.getAssetStatus("asset-admin-recon-2")).thenReturn(AssetStatus.DELETED);

        ReconcileResponse response = adminVideoService.triggerReconciliation(video.getId());

        assertThat(response.operationalState()).isEqualTo(OperationalState.FAILED);
        assertThat(response.incident()).isNotNull();
        assertThat(response.incident().incidentType()).isEqualTo(ReconciliationIncidentType.MISSING_ASSET);
    }

    @Test
    void triggerReconciliation_deletedVideo_returnsCurrentStateNoAction() {
        Video video = seedVideo(OperationalState.DELETED, AccessState.ACTIVE, "asset-admin-recon-3");

        ReconcileResponse response = adminVideoService.triggerReconciliation(video.getId());

        assertThat(response.operationalState()).isEqualTo(OperationalState.DELETED);
        assertThat(response.incident()).isNull();
        verify(videoProviderAdapter, never()).getAssetStatus(any());
    }

    // ── helpers ────────────────────────────────────────────────────────────

    private Video seedVideo(OperationalState opState, AccessState accessState, String providerAssetId) {
        Video v = new Video();
        v.setOwnerId("owner-admin-it");
        v.setProvider("bunny");
        v.setProviderAssetId(providerAssetId);
        v.setTitle("test-admin.mp4");
        v.setOperationalState(opState);
        v.setAccessState(accessState);
        v.setVisibility(Visibility.PRIVATE);
        return videoRepository.save(v);
    }

    private UploadSession seedPendingSession(Video video, String reservationHandle) {
        UploadSession s = new UploadSession();
        s.setVideoId(video.getId());
        s.setProviderUploadId("provider-upload-" + reservationHandle);
        s.setStatus(UploadSessionStatus.PENDING);
        s.setReservedBytes(100_000_000L);
        s.setReservationHandle(reservationHandle);
        s.setExpiresAt(Instant.now().plusSeconds(3600));
        return uploadSessionRepository.save(s);
    }
}
