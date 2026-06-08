package com.softropic.skillars.platform.video.service;

import com.softropic.skillars.infrastructure.video.UploadCredentials;
import com.softropic.skillars.infrastructure.video.VideoProviderAdapter;
import com.softropic.skillars.platform.video.BaseVideoIT;
import com.softropic.skillars.platform.video.config.VideoProperties;
import com.softropic.skillars.platform.video.contract.InitializeUploadRequest;
import com.softropic.skillars.platform.video.contract.InitializeUploadResponse;
import com.softropic.skillars.platform.video.contract.QuotaProvider;
import com.softropic.skillars.platform.video.contract.exception.VideoProviderException;
import com.softropic.skillars.platform.video.contract.exception.VideoValidationException;
import com.softropic.skillars.platform.video.repo.UploadSession;
import com.softropic.skillars.platform.video.repo.UploadSessionRepository;
import com.softropic.skillars.platform.video.repo.Video;
import com.softropic.skillars.platform.video.repo.VideoRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.Optional;
import java.util.stream.LongStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class VideoUploadInitializationIT extends BaseVideoIT {

    @MockitoBean
    QuotaProvider quotaProvider;

    @MockitoBean
    VideoProviderAdapter videoProviderAdapter;

    @Autowired
    VideoService videoService;

    @Autowired
    VideoRepository videoRepository;

    @Autowired
    UploadSessionRepository uploadSessionRepository;

    @Autowired
    VideoProperties videoProperties;

    @BeforeEach
    void setUpEach() {
        wireMockServer.resetAll();
        when(quotaProvider.check(anyString(), anyLong())).thenReturn(true);
        when(quotaProvider.reserve(anyString(), anyLong())).thenReturn("test-reservation-handle");
        when(videoProviderAdapter.initializeUpload(anyString(), anyLong()))
            .thenReturn(new UploadCredentials("test-asset-guid-123", "http://video.bunnycdn.com/tusupload"));
    }

    @Test
    void happyPath_persistsVideoAndSessionWithCorrectState() {
        InitializeUploadResponse response = videoService.initializeUpload(
            new InitializeUploadRequest("owner-happy", "test-video.mp4", 1024L, "video/mp4"));

        assertThat(response.videoId()).isNotNull();
        assertThat(response.uploadSessionId()).isNotNull();
        assertThat(response.providerUploadId()).isEqualTo("test-asset-guid-123");
        assertThat(response.signedUploadUrl()).isEqualTo("http://video.bunnycdn.com/tusupload");

        Optional<Video> savedVideo = videoRepository.findById(response.videoId());
        assertThat(savedVideo).isPresent();
        Video video = savedVideo.get();
        assertThat(video.getOwnerId()).isEqualTo("owner-happy");
        assertThat(video.getProviderAssetId()).isEqualTo("test-asset-guid-123");
        assertThat(video.getOperationalState().name()).isEqualTo("UPLOADING");
        assertThat(video.getAccessState().name()).isEqualTo("ACTIVE");
        assertThat(video.getVisibility().name()).isEqualTo("PRIVATE");

        Optional<UploadSession> savedSession = uploadSessionRepository.findById(response.uploadSessionId());
        assertThat(savedSession).isPresent();
        UploadSession session = savedSession.get();
        assertThat(session.getStatus().name()).isEqualTo("PENDING");
        assertThat(session.getReservedBytes()).isEqualTo(1024L);
        assertThat(session.getProviderUploadId()).isEqualTo("test-asset-guid-123");
        assertThat(session.getReservationHandle()).isEqualTo("test-reservation-handle");
    }

    @Test
    void quotaCallOrder_checkBeforeReserve() {
        videoService.initializeUpload(
            new InitializeUploadRequest("owner-order", "test-video.mp4", 1024L, "video/mp4"));

        InOrder order = inOrder(quotaProvider);
        order.verify(quotaProvider).check("owner-order", 1024L);
        order.verify(quotaProvider).reserve("owner-order", 1024L);
    }

    @Test
    void validationFailure_throwsVideoValidationExceptionNotQuotaException() {
        long oversizedBytes = videoProperties.getUpload().getMaxBytes() + 1;

        assertThatThrownBy(() -> videoService.initializeUpload(
            new InitializeUploadRequest("owner-validation", "test-video.mp4", oversizedBytes, "video/mp4")))
            .isInstanceOf(VideoValidationException.class);

        verify(quotaProvider, never()).check(anyString(), anyLong());
        verify(quotaProvider, never()).reserve(anyString(), anyLong());
        verify(videoProviderAdapter, never()).initializeUpload(anyString(), anyLong());
    }

    @Test
    void nfr1_99thPercentileUnder500ms() {
        int callCount = 100;
        long[] latenciesNs = new long[callCount];
        for (int i = 0; i < callCount; i++) {
            long start = System.nanoTime();
            videoService.initializeUpload(
                new InitializeUploadRequest("nfr-owner-" + i, "test-video.mp4", 1024L, "video/mp4"));
            latenciesNs[i] = System.nanoTime() - start;
        }

        long p99Ms = LongStream.of(latenciesNs).sorted().toArray()[98] / 1_000_000;
        assertThat(p99Ms)
            .as("99th-percentile latency must be < 500ms but was %dms", p99Ms)
            .isLessThan(500);
    }

    @Test
    void providerFailure_quotaReleasedAndVideoProviderExceptionThrown() {
        when(videoProviderAdapter.initializeUpload(anyString(), anyLong()))
            .thenThrow(new VideoProviderException("initializeUpload", new RuntimeException("bunny error")));

        assertThatThrownBy(() -> videoService.initializeUpload(
            new InitializeUploadRequest("owner-prov-fail", "test-video.mp4", 1024L, "video/mp4")))
            .isInstanceOf(VideoProviderException.class);

        verify(quotaProvider).release("test-reservation-handle");
    }
}
