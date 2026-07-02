package com.softropic.skillars.platform.video.service;

import com.softropic.skillars.infrastructure.security.RateLimitingService;
import com.softropic.skillars.infrastructure.video.UploadCredentials;
import com.softropic.skillars.infrastructure.video.VideoProviderAdapter;
import com.softropic.skillars.platform.video.config.VideoProperties;
import com.softropic.skillars.platform.video.contract.OperationalState;
import com.softropic.skillars.platform.video.contract.QuotaProvider;
import com.softropic.skillars.platform.video.contract.RetryUploadRequest;
import com.softropic.skillars.platform.video.contract.UploadSessionStatus;
import com.softropic.skillars.platform.video.repo.UploadSession;
import com.softropic.skillars.platform.video.repo.UploadSessionRepository;
import com.softropic.skillars.platform.video.repo.Video;
import com.softropic.skillars.platform.video.repo.VideoRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Story deferred-6, AC2/AC3: confirmUpload() and retryUpload() must go through
 * VideoLifecycleService.transitionOperationalState() rather than a direct repository save,
 * so VALID_TRANSITIONS is enforced.
 */
@ExtendWith(MockitoExtension.class)
class VideoServiceTest {

    @Mock VideoValidationChain validationChain;
    @Mock QuotaProvider quotaProvider;
    @Mock VideoProviderAdapter videoProviderAdapter;
    @Mock VideoRepository videoRepository;
    @Mock UploadSessionRepository uploadSessionRepository;
    @Mock RateLimitingService rateLimitingService;
    @Mock VideoMetrics videoMetrics;
    @Mock VideoLifecycleService videoLifecycleService;
    @Mock ApplicationEventPublisher publisher;
    @Mock VideoTypeConstraints videoTypeConstraints;

    VideoService service;

    @BeforeEach
    void setUp() {
        TransactionTemplate txTemplate = new TransactionTemplate() {
            @Override
            public <T> T execute(TransactionCallback<T> action) {
                return action.doInTransaction(null);
            }
        };
        service = new VideoService(validationChain, quotaProvider, videoProviderAdapter, videoRepository,
            uploadSessionRepository, new VideoProperties(), txTemplate, rateLimitingService, videoMetrics,
            videoLifecycleService, publisher, videoTypeConstraints);
    }

    @Test
    void confirmUpload_transitionsViaLifecycleService() {
        UUID videoId = UUID.randomUUID();
        Video video = new Video();
        video.setOperationalState(OperationalState.UPLOADING);
        video.setProvider("bunny");
        when(videoRepository.findById(videoId)).thenReturn(Optional.of(video));

        UploadSession session = new UploadSession();
        session.setStatus(UploadSessionStatus.PENDING);
        session.setReservationHandle("handle-1");
        when(uploadSessionRepository.findFirstByVideoIdOrderByCreatedAtDesc(videoId)).thenReturn(Optional.of(session));

        service.confirmUpload(videoId);

        verify(videoLifecycleService).transitionOperationalState(videoId, OperationalState.PROCESSING);
        // Must not bypass lifecycle enforcement with a direct save
        verify(videoRepository, never()).save(any());
        verify(quotaProvider).commit("handle-1");
    }

    @Test
    void retryUpload_setsUploadingBeforeNewSession() {
        UUID videoId = UUID.randomUUID();
        Video video = new Video();
        video.setOperationalState(OperationalState.FAILED);
        video.setTitle("clip.mp4");
        when(videoRepository.findById(videoId)).thenReturn(Optional.of(video));
        when(quotaProvider.check(anyString(), anyLong())).thenReturn(true);
        when(quotaProvider.reserve(anyString(), anyLong(), any())).thenReturn("handle-2");
        when(uploadSessionRepository.save(any(UploadSession.class))).thenAnswer(inv -> {
            UploadSession s = inv.getArgument(0);
            s.setId(UUID.randomUUID());
            return s;
        });
        when(uploadSessionRepository.findById(any())).thenReturn(Optional.of(new UploadSession()));
        when(videoProviderAdapter.initializeUpload(anyString(), anyLong())).thenReturn(
            new UploadCredentials("provider-upload-id", "https://signed", "sig",
                Instant.now().plusSeconds(3600).getEpochSecond(), 123L));

        RetryUploadRequest request = new RetryUploadRequest(videoId, "owner-1", 1024L);

        service.retryUpload(request);

        InOrder inOrder = inOrder(videoLifecycleService, quotaProvider, uploadSessionRepository);
        // State must flip back to UPLOADING before the new session is reserved/created —
        // otherwise the ReconciliationWorker can re-FAIL the video mid-retry.
        inOrder.verify(videoLifecycleService).transitionOperationalState(videoId, OperationalState.UPLOADING);
        inOrder.verify(quotaProvider).reserve(anyString(), anyLong(), any());
        inOrder.verify(uploadSessionRepository).save(any(UploadSession.class));
    }
}
