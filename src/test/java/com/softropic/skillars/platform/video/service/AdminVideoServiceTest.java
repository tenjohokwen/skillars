package com.softropic.skillars.platform.video.service;

import com.softropic.skillars.infrastructure.video.VideoProviderAdapter;
import com.softropic.skillars.platform.video.contract.AccessState;
import com.softropic.skillars.platform.video.contract.AdminVideoQueueFilter;
import com.softropic.skillars.platform.video.contract.AdminVideoQueueItemDto;
import com.softropic.skillars.platform.video.contract.OperationalState;
import com.softropic.skillars.platform.video.contract.QuotaProvider;
import com.softropic.skillars.platform.video.contract.UploadSessionStatus;
import com.softropic.skillars.platform.video.repo.ReconciliationIncidentRepository;
import com.softropic.skillars.platform.video.repo.UploadSession;
import com.softropic.skillars.platform.video.repo.UploadSessionRepository;
import com.softropic.skillars.platform.video.repo.Video;
import com.softropic.skillars.platform.video.repo.VideoModerationScan;
import com.softropic.skillars.platform.video.repo.VideoModerationScanRepository;
import com.softropic.skillars.platform.video.repo.VideoRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
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
    @Mock VideoModerationScanRepository moderationScanRepository;
    @Mock VideoDeletionService videoDeletionService;

    @InjectMocks
    AdminVideoService service;

    @BeforeEach
    void setUp() {
        lenient().when(transactionTemplate.execute(any())).thenAnswer(inv -> {
            org.springframework.transaction.support.TransactionCallback<?> cb = inv.getArgument(0);
            return cb.doInTransaction(null);
        });
    }

    /**
     * B5: the admin delete delegates the purge (state, quota decrement, deletion log, drill-ref-aware
     * outbox) to VideoDeletionService, and never deletes the Bunny asset inline.
     */
    @Test
    void deleteVideo_purgesThroughDeletionService_thenReleasesReservation_neverCallsProviderInline() {
        UUID videoId = UUID.randomUUID();
        UploadSession session = new UploadSession();
        session.setStatus(UploadSessionStatus.EXPIRED);
        session.setReservationHandle("handle-1");
        when(uploadSessionRepository.findFirstByVideoIdOrderByCreatedAtDesc(videoId)).thenReturn(Optional.of(session));

        service.deleteVideo(videoId);

        InOrder inOrder = inOrder(videoDeletionService, quotaProvider);
        inOrder.verify(videoDeletionService).deleteByAdmin(videoId);
        inOrder.verify(quotaProvider).release("handle-1");
        assertThat(session.getQuotaReleasedAt()).isNotNull();
        verify(videoProviderAdapter, never()).deleteAsset(any());
    }

    @Test
    void deleteVideo_noSession_neverReleasesQuota() {
        UUID videoId = UUID.randomUUID();
        when(uploadSessionRepository.findFirstByVideoIdOrderByCreatedAtDesc(videoId)).thenReturn(Optional.empty());

        service.deleteVideo(videoId);

        verify(videoDeletionService).deleteByAdmin(videoId);
        verify(quotaProvider, never()).release(any());
    }

    /**
     * Deferred-64 AC5 / Task 5.3: a release() failure must surface to the caller (loud, not
     * swallowed), and must NOT persist quotaReleasedAt — so the video stays retriable.
     */
    @Test
    void deleteVideo_releaseFails_propagatesAndDoesNotMarkReleased() {
        UUID videoId = UUID.randomUUID();
        UploadSession session = new UploadSession();
        session.setStatus(UploadSessionStatus.EXPIRED);
        session.setReservationHandle("handle-1");
        when(uploadSessionRepository.findFirstByVideoIdOrderByCreatedAtDesc(videoId)).thenReturn(Optional.of(session));

        RuntimeException releaseFailure = new RuntimeException("provider unavailable");
        org.mockito.Mockito.doThrow(releaseFailure).when(quotaProvider).release("handle-1");

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.deleteVideo(videoId))
            .isSameAs(releaseFailure);

        assertThat(session.getQuotaReleasedAt())
            .as("a failed release must not be marked released, so a retry re-attempts it")
            .isNull();
    }

    /**
     * Deferred-64 AC5: a repeat call (video already purged — deleteByAdmin is then a no-op) still
     * retries a release that previously failed, and never repeats one that succeeded.
     */
    @Test
    void deleteVideo_repeatCall_retriesUnreleasedReservationOnly() {
        UUID videoId = UUID.randomUUID();
        when(videoDeletionService.deleteByAdmin(videoId)).thenReturn(false);
        UploadSession session = new UploadSession();
        session.setStatus(UploadSessionStatus.EXPIRED);
        session.setReservationHandle("handle-1");
        when(uploadSessionRepository.findFirstByVideoIdOrderByCreatedAtDesc(videoId)).thenReturn(Optional.of(session));

        service.deleteVideo(videoId);
        verify(quotaProvider).release("handle-1");

        service.deleteVideo(videoId);
        verify(quotaProvider, org.mockito.Mockito.times(1)).release("handle-1");
    }

    @Test
    void deleteVideo_lostRaceToAnotherDelete_isIdempotent() {
        UUID videoId = UUID.randomUUID();
        org.mockito.Mockito.doThrow(new org.springframework.orm.ObjectOptimisticLockingFailureException(Video.class, videoId))
            .when(videoDeletionService).deleteByAdmin(videoId);
        Video purged = new Video();
        purged.setOperationalState(OperationalState.PURGED);
        when(videoRepository.findById(videoId)).thenReturn(Optional.of(purged));
        when(uploadSessionRepository.findFirstByVideoIdOrderByCreatedAtDesc(videoId)).thenReturn(Optional.empty());

        org.assertj.core.api.Assertions.assertThatCode(() -> service.deleteVideo(videoId)).doesNotThrowAnyException();
    }

    @Test
    void deleteVideo_lostRaceToNonDeleteChange_propagatesConflict() {
        UUID videoId = UUID.randomUUID();
        org.mockito.Mockito.doThrow(new org.springframework.orm.ObjectOptimisticLockingFailureException(Video.class, videoId))
            .when(videoDeletionService).deleteByAdmin(videoId);
        Video ready = new Video();
        ready.setOperationalState(OperationalState.READY);
        when(videoRepository.findById(videoId)).thenReturn(Optional.of(ready));

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.deleteVideo(videoId))
            .isInstanceOf(org.springframework.orm.ObjectOptimisticLockingFailureException.class);
    }

    // ---- admin video review queue (story 6.3 / FR-ADM-002) ----

    @Test
    void getReviewQueue_attention_selectsAllThreeConditions() {
        when(videoRepository.findAdminReviewQueue(anyBoolean(), anyBoolean(), anyBoolean(), any(Pageable.class)))
            .thenReturn(Page.empty());

        service.getReviewQueue(AdminVideoQueueFilter.ATTENTION, 0);

        verify(videoRepository).findAdminReviewQueue(true, true, true, PageRequest.of(0, 20));
        verify(moderationScanRepository, never()).findByVideoIdInAndOutcome(any(), any());
    }

    @Test
    void getReviewQueue_nullFilterAndNegativePage_defaultToAttentionAndFirstPage() {
        when(videoRepository.findAdminReviewQueue(anyBoolean(), anyBoolean(), anyBoolean(), any(Pageable.class)))
            .thenReturn(Page.empty());

        service.getReviewQueue(null, -3);

        verify(videoRepository).findAdminReviewQueue(true, true, true, PageRequest.of(0, 20));
    }

    @Test
    void getReviewQueue_singleFilters_selectOnlyTheirCondition() {
        when(videoRepository.findAdminReviewQueue(anyBoolean(), anyBoolean(), anyBoolean(), any(Pageable.class)))
            .thenReturn(Page.empty());

        service.getReviewQueue(AdminVideoQueueFilter.LOCKED, 1);
        service.getReviewQueue(AdminVideoQueueFilter.FAILED, 1);
        service.getReviewQueue(AdminVideoQueueFilter.BLOCKED, 1);

        verify(videoRepository).findAdminReviewQueue(true, false, false, PageRequest.of(1, 20));
        verify(videoRepository).findAdminReviewQueue(false, true, false, PageRequest.of(1, 20));
        verify(videoRepository).findAdminReviewQueue(false, false, true, PageRequest.of(1, 20));
    }

    @Test
    void getReviewQueue_attachesSortedFlaggedLayersPerVideo() {
        Video locked = queueVideo(OperationalState.LOCKED, AccessState.ACTIVE);
        Video blocked = queueVideo(OperationalState.READY, AccessState.BLOCKED);
        when(videoRepository.findAdminReviewQueue(anyBoolean(), anyBoolean(), anyBoolean(), any(Pageable.class)))
            .thenReturn(new PageImpl<>(List.of(locked, blocked), PageRequest.of(0, 20), 2));
        when(moderationScanRepository.findByVideoIdInAndOutcome(List.of(locked.getId(), blocked.getId()), "FLAGGED"))
            .thenReturn(List.of(scan(locked.getId(), "VIDEOINTEL"), scan(locked.getId(), "ARACHNID")));

        Page<AdminVideoQueueItemDto> page = service.getReviewQueue(AdminVideoQueueFilter.ATTENTION, 0);

        assertThat(page.getContent()).hasSize(2);
        assertThat(page.getContent().get(0).id()).isEqualTo(locked.getId());
        assertThat(page.getContent().get(0).flaggedLayers()).containsExactly("ARACHNID", "VIDEOINTEL");
        assertThat(page.getContent().get(1).flaggedLayers()).isEmpty();
        assertThat(page.getContent().get(1).accessState()).isEqualTo(AccessState.BLOCKED);
    }

    @Test
    void getVideo_unknownId_throwsNotFound() {
        UUID videoId = UUID.randomUUID();
        when(videoRepository.findById(videoId)).thenReturn(Optional.empty());

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.getVideo(videoId))
            .isInstanceOf(com.softropic.skillars.platform.video.contract.exception.VideoNotFoundException.class);
    }

    @Test
    void getVideo_returnsQueueShapeWithFlaggedLayers() {
        Video locked = queueVideo(OperationalState.LOCKED, AccessState.ACTIVE);
        when(videoRepository.findById(locked.getId())).thenReturn(Optional.of(locked));
        when(moderationScanRepository.findByVideoIdInAndOutcome(eq(List.of(locked.getId())), eq("FLAGGED")))
            .thenReturn(List.of(scan(locked.getId(), "MINOR_GATE")));

        AdminVideoQueueItemDto dto = service.getVideo(locked.getId());

        assertThat(dto.operationalState()).isEqualTo(OperationalState.LOCKED);
        assertThat(dto.flaggedLayers()).containsExactly("MINOR_GATE");
    }

    private static Video queueVideo(OperationalState op, AccessState access) {
        Video v = new Video();
        v.setId(UUID.randomUUID());
        v.setTitle("clip.mp4");
        v.setOwnerId("owner-1");
        v.setOperationalState(op);
        v.setAccessState(access);
        return v;
    }

    private static VideoModerationScan scan(UUID videoId, String layer) {
        VideoModerationScan s = new VideoModerationScan();
        s.setVideoId(videoId);
        s.setLayer(layer);
        s.setOutcome("FLAGGED");
        return s;
    }
}
