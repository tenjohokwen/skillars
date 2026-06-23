package com.softropic.skillars.platform.video.service;

import com.softropic.skillars.infrastructure.video.UploadCredentials;
import com.softropic.skillars.infrastructure.video.VideoProviderAdapter;
import com.softropic.skillars.platform.video.BaseVideoIT;
import com.softropic.skillars.platform.video.contract.AccessState;
import com.softropic.skillars.platform.video.contract.ConfirmUploadResponse;
import com.softropic.skillars.platform.video.contract.InitializeUploadResponse;
import com.softropic.skillars.platform.video.contract.OperationalState;
import com.softropic.skillars.platform.video.service.QuotaService;
import com.softropic.skillars.platform.video.contract.RetryUploadRequest;
import com.softropic.skillars.platform.video.contract.UploadSessionStatus;
import com.softropic.skillars.platform.video.contract.Visibility;
import com.softropic.skillars.platform.video.contract.exception.VideoValidationException;
import com.softropic.skillars.platform.video.repo.UploadSession;
import com.softropic.skillars.platform.video.repo.UploadSessionRepository;
import com.softropic.skillars.platform.video.repo.Video;
import com.softropic.skillars.platform.video.repo.VideoRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class VideoRetryUploadIT extends BaseVideoIT {

    @MockitoBean
    QuotaService quotaProvider;

    @MockitoBean
    VideoProviderAdapter videoProviderAdapter;

    @Autowired
    VideoService videoService;

    @Autowired
    VideoRepository videoRepository;

    @Autowired
    UploadSessionRepository uploadSessionRepository;

    @BeforeEach
    void setUp() {
        uploadSessionRepository.deleteAll();
        videoRepository.deleteAll();
        wireMockServer.resetAll();
        when(quotaProvider.check(anyString(), anyLong())).thenReturn(true);
        when(quotaProvider.reserve(anyString(), anyLong())).thenReturn("retry-handle");
        when(quotaProvider.reserve(anyString(), anyLong(), any())).thenReturn("retry-handle");
        when(videoProviderAdapter.initializeUpload(anyString(), anyLong()))
            .thenReturn(new UploadCredentials("new-bunny-guid", "https://bunny/tus/retry",
                "0".repeat(64), 9_999_999_999L, 0L));
    }

    private Video seedFailedVideo(String ownerId) {
        Video v = new Video();
        v.setOwnerId(ownerId);
        v.setProvider("bunny");
        v.setProviderAssetId("old-bunny-guid");
        v.setTitle("retry.mp4");
        v.setOperationalState(OperationalState.FAILED);
        v.setAccessState(AccessState.ACTIVE);
        v.setVisibility(Visibility.PRIVATE);
        return videoRepository.save(v);
    }

    @Test
    void retryUpload_onFailedVideo_createsNewSessionVideoStaysFailed() {
        Video failed = seedFailedVideo("owner-retry");

        InitializeUploadResponse response = videoService.retryUpload(
            new RetryUploadRequest(failed.getId(), "owner-retry", 1024L));

        assertThat(response.videoId()).isEqualTo(failed.getId());

        Video video = videoRepository.findById(failed.getId()).orElseThrow();
        assertThat(video.getOperationalState()).isEqualTo(OperationalState.FAILED);
        assertThat(video.getProviderAssetId()).isEqualTo("new-bunny-guid");

        List<UploadSession> sessions = uploadSessionRepository.findAll();
        assertThat(sessions).hasSize(1);
        UploadSession session = sessions.get(0);
        assertThat(session.getVideoId()).isEqualTo(failed.getId());
        assertThat(session.getStatus()).isEqualTo(UploadSessionStatus.PENDING);
        assertThat(session.getProviderUploadId()).isEqualTo("new-bunny-guid");
    }

    @Test
    void retryUpload_thenConfirmUpload_transitionsToProcessing() {
        Video failed = seedFailedVideo("owner-confirm");

        videoService.retryUpload(
            new RetryUploadRequest(failed.getId(), "owner-confirm", 1024L));

        ConfirmUploadResponse confirm = videoService.confirmUpload(failed.getId());

        assertThat(confirm.operationalState()).isEqualTo(OperationalState.PROCESSING);

        Video video = videoRepository.findById(failed.getId()).orElseThrow();
        assertThat(video.getOperationalState()).isEqualTo(OperationalState.PROCESSING);

        List<UploadSession> sessions = uploadSessionRepository.findAll();
        assertThat(sessions).hasSize(1);
        assertThat(sessions.get(0).getStatus()).isEqualTo(UploadSessionStatus.COMMITTED);
    }

    @Test
    void retryUpload_onNonFailedVideo_throwsValidationException() {
        Video processing = new Video();
        processing.setOwnerId("owner-proc");
        processing.setProvider("bunny");
        processing.setTitle("proc.mp4");
        processing.setOperationalState(OperationalState.PROCESSING);
        processing.setAccessState(AccessState.ACTIVE);
        processing.setVisibility(Visibility.PRIVATE);
        processing = videoRepository.save(processing);
        final Video saved = processing;

        assertThatThrownBy(() -> videoService.retryUpload(
            new RetryUploadRequest(saved.getId(), "owner-proc", 1024L)))
            .isInstanceOf(VideoValidationException.class);

        assertThat(uploadSessionRepository.findAll()).isEmpty();
    }

    @Test
    void retryUpload_quotaReleaseFails_sessionNotCreated() {
        Video failed = seedFailedVideo("owner-quota");

        doThrow(new RuntimeException("quota service down"))
            .when(quotaProvider).release(anyString());
        doThrow(new RuntimeException("provider unavailable"))
            .when(videoProviderAdapter).initializeUpload(anyString(), anyLong());

        assertThatThrownBy(() -> videoService.retryUpload(
            new RetryUploadRequest(failed.getId(), "owner-quota", 1024L)))
            .isInstanceOf(RuntimeException.class);

        // quota.release() was attempted even though it threw
        verify(quotaProvider).release("retry-handle");

        // Video remains FAILED (unchanged)
        Video video = videoRepository.findById(failed.getId()).orElseThrow();
        assertThat(video.getOperationalState()).isEqualTo(OperationalState.FAILED);

        // Session was created before provider failed — only the providerUploadId update is skipped.
        // However the session exists in PENDING state (orphaned, handled by expiry scheduler)
        List<UploadSession> sessions = uploadSessionRepository.findAll();
        assertThat(sessions).hasSize(1);
        assertThat(sessions.get(0).getStatus()).isEqualTo(UploadSessionStatus.PENDING);
        assertThat(sessions.get(0).getProviderUploadId()).isNull();
    }
}
