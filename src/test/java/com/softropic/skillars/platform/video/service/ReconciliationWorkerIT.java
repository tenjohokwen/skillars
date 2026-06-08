package com.softropic.skillars.platform.video.service;

import com.softropic.skillars.infrastructure.video.AssetStatus;
import com.softropic.skillars.infrastructure.video.VideoProviderAdapter;
import com.softropic.skillars.platform.video.BaseVideoIT;
import com.softropic.skillars.platform.video.contract.AccessState;
import com.softropic.skillars.platform.video.contract.OperationalState;
import com.softropic.skillars.platform.video.contract.ReconciliationIncidentType;
import com.softropic.skillars.platform.video.contract.Visibility;
import com.softropic.skillars.platform.video.contract.exception.VideoProviderException;
import com.softropic.skillars.platform.video.repo.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@TestPropertySource(properties = {
    "app.video.reconciliation.fixed-delay-ms=86400000"  // prevent background scheduler from racing with manual calls
})
class ReconciliationWorkerIT extends BaseVideoIT {

    @MockitoBean
    VideoProviderAdapter videoProviderAdapter;

    @Autowired ReconciliationWorkerScheduler scheduler;
    @Autowired VideoRepository videoRepository;
    @Autowired ReconciliationIncidentRepository incidentRepository;
    @Autowired PlaybackTokenRepository playbackTokenRepository;
    @Autowired UploadSessionRepository uploadSessionRepository;
    @Autowired VideoWebhookEventRepository webhookEventRepository;

    @BeforeEach
    void setUp() {
        incidentRepository.deleteAll();
        playbackTokenRepository.deleteAll();
        uploadSessionRepository.deleteAll();
        webhookEventRepository.deleteAll();
        videoRepository.deleteAll();
    }

    @Test
    void reconcile_processingVideoReadyAtProvider_advancesToReadyWithStateCorrectedIncident() {
        Video video = seedVideo(OperationalState.PROCESSING, "asset-rec-ready");
        when(videoProviderAdapter.getAssetStatus("asset-rec-ready")).thenReturn(AssetStatus.READY);

        scheduler.reconcile();

        assertThat(videoRepository.findById(video.getId()).orElseThrow().getOperationalState())
            .isEqualTo(OperationalState.READY);
        var incidents = incidentRepository.findAll();
        assertThat(incidents).hasSize(1);
        assertThat(incidents.get(0).getIncidentType()).isEqualTo(ReconciliationIncidentType.STATE_CORRECTED);
        assertThat(incidents.get(0).getVideoId()).isEqualTo(video.getId());
        assertThat(incidents.get(0).getProviderAssetId()).isEqualTo("asset-rec-ready");
        assertThat(incidents.get(0).getDescription()).isNotBlank();
    }

    @Test
    void reconcile_processingVideoMissingAtProvider_marksFailedWithMissingAssetIncident() {
        Video video = seedVideo(OperationalState.PROCESSING, "asset-rec-deleted");
        when(videoProviderAdapter.getAssetStatus("asset-rec-deleted")).thenReturn(AssetStatus.DELETED);

        scheduler.reconcile();

        assertThat(videoRepository.findById(video.getId()).orElseThrow().getOperationalState())
            .isEqualTo(OperationalState.FAILED);
        var incidents = incidentRepository.findAll();
        assertThat(incidents).hasSize(1);
        assertThat(incidents.get(0).getIncidentType()).isEqualTo(ReconciliationIncidentType.MISSING_ASSET);
        assertThat(incidents.get(0).getVideoId()).isEqualTo(video.getId());
    }

    @Test
    void reconcile_transientProviderException_videoUnchangedNoIncident() {
        Video video = seedVideo(OperationalState.PROCESSING, "asset-rec-transient");
        when(videoProviderAdapter.getAssetStatus("asset-rec-transient"))
            .thenThrow(new VideoProviderException("getAssetStatus", new RuntimeException("timeout")));

        scheduler.reconcile();

        assertThat(videoRepository.findById(video.getId()).orElseThrow().getOperationalState())
            .isEqualTo(OperationalState.PROCESSING);
        assertThat(incidentRepository.findAll()).isEmpty();
    }

    @Test
    void reconcile_uploadingVideoReadyAtProvider_marksFailedWithMissingAssetIncident() {
        Video video = seedVideo(OperationalState.UPLOADING, "asset-rec-uploading-ready");
        when(videoProviderAdapter.getAssetStatus("asset-rec-uploading-ready")).thenReturn(AssetStatus.READY);

        scheduler.reconcile();

        assertThat(videoRepository.findById(video.getId()).orElseThrow().getOperationalState())
            .isEqualTo(OperationalState.FAILED);
        var incidents = incidentRepository.findAll();
        assertThat(incidents).hasSize(1);
        assertThat(incidents.get(0).getIncidentType()).isEqualTo(ReconciliationIncidentType.MISSING_ASSET);
    }

    @Test
    void reconcile_readyVideoNotFetched() {
        Video readyVideo = seedVideo(OperationalState.READY, "asset-rec-already-ready");
        when(videoProviderAdapter.getAssetStatus(any())).thenReturn(AssetStatus.DELETED);

        scheduler.reconcile();

        assertThat(videoRepository.findById(readyVideo.getId()).orElseThrow().getOperationalState())
            .isEqualTo(OperationalState.READY);
        assertThat(incidentRepository.findAll()).isEmpty();
        verify(videoProviderAdapter, never()).getAssetStatus(any());
    }

    private Video seedVideo(OperationalState opState, String providerAssetId) {
        Video v = new Video();
        v.setOwnerId("owner-recon-it");
        v.setProvider("bunny");
        v.setProviderAssetId(providerAssetId);
        v.setTitle("test-recon.mp4");
        v.setOperationalState(opState);
        v.setAccessState(AccessState.ACTIVE);
        v.setVisibility(Visibility.PRIVATE);
        return videoRepository.save(v);
    }
}
