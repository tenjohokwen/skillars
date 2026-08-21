package com.softropic.skillars.platform.video.service;

import com.softropic.skillars.infrastructure.video.VideoProviderAdapter;
import com.softropic.skillars.platform.video.contract.QuotaProvider;
import com.softropic.skillars.platform.video.contract.UploadSessionStatus;
import com.softropic.skillars.platform.video.repo.ReconciliationIncidentRepository;
import com.softropic.skillars.platform.video.repo.UploadSession;
import com.softropic.skillars.platform.video.repo.UploadSessionRepository;
import com.softropic.skillars.platform.video.repo.Video;
import com.softropic.skillars.platform.video.repo.VideoRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Story deferred-52 AC2: quotaProvider.release() must happen after the transaction that writes
 * DELETED/EXPIRED returns, not inside it, so a release() failure can no longer roll back those writes.
 */
@ExtendWith(MockitoExtension.class)
class AdminVideoServiceTest {

    @Mock VideoRepository videoRepository;
    @Mock UploadSessionRepository uploadSessionRepository;
    @Mock ReconciliationIncidentRepository incidentRepository;
    @Mock VideoLifecycleService videoLifecycleService;
    @Mock VideoProviderAdapter videoProviderAdapter;
    @Mock QuotaProvider quotaProvider;
    @Mock TransactionTemplate transactionTemplate;

    @InjectMocks
    AdminVideoService service;

    @BeforeEach
    void setUp() {
        lenient().when(transactionTemplate.execute(any())).thenAnswer(inv -> {
            org.springframework.transaction.support.TransactionCallback<?> cb = inv.getArgument(0);
            return cb.doInTransaction(null);
        });
    }

    @Test
    void deleteVideo_pendingSession_releasesQuotaAfterTransactionCommits() {
        UUID videoId = UUID.randomUUID();
        Video video = new Video();
        when(videoRepository.findById(videoId)).thenReturn(Optional.of(video));

        UploadSession session = new UploadSession();
        session.setStatus(UploadSessionStatus.PENDING);
        session.setReservationHandle("handle-1");
        when(uploadSessionRepository.findFirstByVideoIdOrderByCreatedAtDesc(videoId)).thenReturn(Optional.of(session));

        service.deleteVideo(videoId);

        InOrder inOrder = inOrder(videoRepository, quotaProvider);
        inOrder.verify(videoRepository).save(video);
        inOrder.verify(quotaProvider).release("handle-1");
    }

    @Test
    void deleteVideo_noPendingSession_neverReleasesQuota() {
        UUID videoId = UUID.randomUUID();
        Video video = new Video();
        when(videoRepository.findById(videoId)).thenReturn(Optional.of(video));
        when(uploadSessionRepository.findFirstByVideoIdOrderByCreatedAtDesc(videoId)).thenReturn(Optional.empty());

        service.deleteVideo(videoId);

        verify(quotaProvider, never()).release(any());
    }
}
