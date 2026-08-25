package com.softropic.skillars.platform.video.service;

import com.softropic.skillars.infrastructure.video.VideoProviderAdapter;
import com.softropic.skillars.platform.video.contract.OperationalState;
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

import static org.assertj.core.api.Assertions.assertThat;
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
        assertThat(video.getOperationalState()).isEqualTo(OperationalState.DELETED);
        assertThat(session.getStatus()).isEqualTo(UploadSessionStatus.EXPIRED);
    }

    @Test
    void deleteVideo_noPendingSession_neverReleasesQuota() {
        UUID videoId = UUID.randomUUID();
        Video video = new Video();
        when(videoRepository.findById(videoId)).thenReturn(Optional.of(video));
        when(uploadSessionRepository.findFirstByVideoIdOrderByCreatedAtDesc(videoId)).thenReturn(Optional.empty());

        service.deleteVideo(videoId);

        verify(quotaProvider, never()).release(any());
        assertThat(video.getOperationalState()).isEqualTo(OperationalState.DELETED);
    }

    /**
     * Deferred-64 AC5 / Task 5.3: a release() failure must surface to the caller (loud, not
     * swallowed), and must NOT persist quotaReleasedAt — so the video stays retriable.
     */
    @Test
    void deleteVideo_releaseFails_propagatesAndDoesNotMarkReleased() {
        UUID videoId = UUID.randomUUID();
        Video video = new Video();
        when(videoRepository.findById(videoId)).thenReturn(Optional.of(video));

        UploadSession session = new UploadSession();
        session.setStatus(UploadSessionStatus.PENDING);
        session.setReservationHandle("handle-1");
        when(uploadSessionRepository.findFirstByVideoIdOrderByCreatedAtDesc(videoId)).thenReturn(Optional.of(session));

        RuntimeException releaseFailure = new RuntimeException("provider unavailable");
        org.mockito.Mockito.doThrow(releaseFailure).when(quotaProvider).release("handle-1");

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.deleteVideo(videoId))
            .isSameAs(releaseFailure);

        assertThat(video.getOperationalState())
            .as("Phase 1's DELETED write already committed in its own transaction and must not be undone")
            .isEqualTo(OperationalState.DELETED);
        assertThat(session.getQuotaReleasedAt())
            .as("a failed release must not be marked released, so a retry re-attempts it")
            .isNull();
    }

    /**
     * Deferred-64 AC5 / Task 5.3: retry-safety. Phase 1's PENDING filter means a session already
     * flipped to EXPIRED by an earlier deleteVideo call is invisible to Phase 1 on a repeat call —
     * but Phase 2 must still find it (via the same repository method, without that filter) and
     * retry the release that previously failed.
     */
    @Test
    void deleteVideo_repeatCallAfterPriorReleaseFailure_retriesAndSucceeds() {
        UUID videoId = UUID.randomUUID();
        Video video = new Video();
        video.setOperationalState(OperationalState.DELETED);
        when(videoRepository.findById(videoId)).thenReturn(Optional.of(video));

        // Already EXPIRED from an earlier call — Phase 1's PENDING filter no longer matches it,
        // but it still carries a reservation handle and no quotaReleasedAt yet.
        UploadSession session = new UploadSession();
        session.setStatus(UploadSessionStatus.EXPIRED);
        session.setReservationHandle("handle-1");
        when(uploadSessionRepository.findFirstByVideoIdOrderByCreatedAtDesc(videoId)).thenReturn(Optional.of(session));

        service.deleteVideo(videoId);

        verify(quotaProvider).release("handle-1");
        assertThat(session.getQuotaReleasedAt()).isNotNull();
    }

    /**
     * Deferred-64 AC5 / Task 5.3: a successful release must not be repeated on a further call for
     * the same, already-released session.
     */
    @Test
    void deleteVideo_sessionAlreadyReleased_doesNotReleaseAgain() {
        UUID videoId = UUID.randomUUID();
        Video video = new Video();
        video.setOperationalState(OperationalState.DELETED);
        when(videoRepository.findById(videoId)).thenReturn(Optional.of(video));

        UploadSession session = new UploadSession();
        session.setStatus(UploadSessionStatus.EXPIRED);
        session.setReservationHandle("handle-1");
        session.setQuotaReleasedAt(java.time.Instant.now().minusSeconds(60));
        when(uploadSessionRepository.findFirstByVideoIdOrderByCreatedAtDesc(videoId)).thenReturn(Optional.of(session));

        service.deleteVideo(videoId);

        verify(quotaProvider, never()).release(any());
    }
}
