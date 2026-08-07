package com.softropic.skillars.platform.video.service;

import com.softropic.skillars.infrastructure.video.UploadCredentials;
import com.softropic.skillars.infrastructure.video.VideoProviderAdapter;
import com.softropic.skillars.platform.video.BaseVideoIT;
import com.softropic.skillars.platform.video.contract.ConfirmUploadResponse;
import com.softropic.skillars.platform.video.contract.InitializeUploadRequest;
import com.softropic.skillars.platform.video.contract.InitializeUploadResponse;
import com.softropic.skillars.platform.video.contract.OperationalState;
import com.softropic.skillars.platform.video.service.QuotaService;
import com.softropic.skillars.platform.video.contract.UploadSessionStatus;
import com.softropic.skillars.platform.video.repo.UploadSession;
import com.softropic.skillars.platform.video.repo.UploadSessionRepository;
import com.softropic.skillars.platform.video.repo.Video;
import com.softropic.skillars.platform.video.repo.VideoRepository;

import static org.mockito.ArgumentMatchers.any;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class VideoUploadConfirmationIT extends BaseVideoIT {

    @MockitoBean
    QuotaService quotaProvider;

    @MockitoBean
    VideoProviderAdapter videoProviderAdapter;

    @Autowired
    VideoService videoService;

    @Autowired
    UploadSessionExpiryScheduler expiryScheduler;

    @Autowired
    UploadSessionRepository uploadSessionRepository;

    @Autowired
    VideoRepository videoRepository;

    @BeforeEach
    void setUp() {
        wireMockServer.resetAll();
        uploadSessionRepository.deleteAll();
        videoRepository.deleteAll();
        when(quotaProvider.check(anyString(), anyLong())).thenReturn(true);
        when(quotaProvider.reserve(anyString(), anyLong(), any())).thenReturn("test-handle");
        when(videoProviderAdapter.initializeUpload(anyString(), anyLong()))
            .thenReturn(new UploadCredentials("guid-123", "https://video.bunnycdn.com/tusupload",
                "test-tus-sig", 9_999_999_999L, 12345L));
    }

    @Test
    void confirmUpload_happyPath_transitionsToProcessing() {
        InitializeUploadResponse init = videoService.initializeUpload(
            new InitializeUploadRequest("owner-1", "vid.mp4", 1024L, "video/mp4", null));

        ConfirmUploadResponse confirm = videoService.confirmUpload(init.videoId());

        assertThat(confirm.operationalState()).isEqualTo(OperationalState.PROCESSING);
        Video video = videoRepository.findById(init.videoId()).orElseThrow();
        assertThat(video.getOperationalState()).isEqualTo(OperationalState.PROCESSING);
        UploadSession session = uploadSessionRepository.findById(init.sessionId()).orElseThrow();
        assertThat(session.getStatus()).isEqualTo(UploadSessionStatus.COMMITTED);
        verify(quotaProvider).commit("test-handle");
    }

    @Test
    void confirmUpload_duplicate_idempotentNoDuplicateCommit() {
        InitializeUploadResponse init = videoService.initializeUpload(
            new InitializeUploadRequest("owner-2", "vid.mp4", 1024L, "video/mp4", null));

        videoService.confirmUpload(init.videoId());
        ConfirmUploadResponse second = videoService.confirmUpload(init.videoId());

        assertThat(second.operationalState()).isEqualTo(OperationalState.PROCESSING);
        verify(quotaProvider, times(1)).commit("test-handle");
    }

    @Test
    void processExpired_expiresSessionAndReleasesQuota() {
        InitializeUploadResponse init = videoService.initializeUpload(
            new InitializeUploadRequest("owner-3", "vid.mp4", 1024L, "video/mp4", null));

        UploadSession session = uploadSessionRepository.findById(init.sessionId()).orElseThrow();
        session.setExpiresAt(Instant.now().minusSeconds(60));
        uploadSessionRepository.save(session);

        expiryScheduler.processExpired();

        UploadSession expired = uploadSessionRepository.findById(init.sessionId()).orElseThrow();
        assertThat(expired.getStatus()).isEqualTo(UploadSessionStatus.EXPIRED);
        Video video = videoRepository.findById(init.videoId()).orElseThrow();
        assertThat(video.getOperationalState()).isEqualTo(OperationalState.FAILED);
        verify(quotaProvider).release("test-handle");
    }

    @Test
    void processExpired_quotaReleaseFails_sessionRemainsUnchanged() {
        InitializeUploadResponse init = videoService.initializeUpload(
            new InitializeUploadRequest("owner-4", "vid.mp4", 1024L, "video/mp4", null));

        UploadSession session = uploadSessionRepository.findById(init.sessionId()).orElseThrow();
        session.setExpiresAt(Instant.now().minusSeconds(60));
        uploadSessionRepository.save(session);

        doThrow(new RuntimeException("quota service down")).when(quotaProvider).release(anyString());

        expiryScheduler.processExpired();

        UploadSession unchanged = uploadSessionRepository.findById(init.sessionId()).orElseThrow();
        assertThat(unchanged.getStatus()).isEqualTo(UploadSessionStatus.PENDING);
        Video video = videoRepository.findById(init.videoId()).orElseThrow();
        assertThat(video.getOperationalState()).isEqualTo(OperationalState.UPLOADING);
    }
}
