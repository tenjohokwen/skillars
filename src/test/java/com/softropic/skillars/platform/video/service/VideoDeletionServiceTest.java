package com.softropic.skillars.platform.video.service;

import com.softropic.skillars.platform.video.contract.OperationalState;
import com.softropic.skillars.platform.video.contract.LifecycleTrigger;
import com.softropic.skillars.platform.video.contract.UploadSessionStatus;
import com.softropic.skillars.platform.video.repo.UploadSession;
import com.softropic.skillars.platform.video.repo.UploadSessionRepository;
import com.softropic.skillars.platform.video.repo.Video;
import com.softropic.skillars.platform.video.repo.VideoDeletionLog;
import com.softropic.skillars.platform.video.repo.VideoDeletionOutbox;
import com.softropic.skillars.platform.video.repo.VideoDeletionLogRepository;
import com.softropic.skillars.platform.video.repo.VideoDeletionOutboxRepository;
import com.softropic.skillars.platform.video.repo.VideoQuotaRepository;
import com.softropic.skillars.platform.video.repo.VideoRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.PageImpl;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class VideoDeletionServiceTest {

    @Mock private VideoRepository videoRepository;
    @Mock private VideoDeletionOutboxRepository outboxRepository;
    @Mock private VideoDeletionLogRepository deletionLogRepository;
    @Mock private VideoQuotaRepository videoQuotaRepository;
    @Mock private QuotaService quotaService;
    @Mock private ApplicationEventPublisher publisher;
    @Mock private VideoAccessGuard videoAccessGuard;
    @Mock private UploadSessionRepository uploadSessionRepository;

    private VideoDeletionService service;

    private static final String OWNER_ID = "owner-1";

    @BeforeEach
    void setUp() {
        service = new VideoDeletionService(videoRepository, outboxRepository, deletionLogRepository,
            videoQuotaRepository, quotaService, publisher, videoAccessGuard,
            uploadSessionRepository);
        // cascadeDeleteForAccount routes its per-video call through self.deleteVideo(...) so each
        // iteration gets its own transaction via the Spring proxy — wire self back to the same
        // test-constructed instance, since there's no real proxy here.
        ReflectionTestUtils.setField(service, "self", service);
    }

    private Video video(UUID id) {
        Video v = new Video();
        v.setId(id);
        v.setOwnerId(OWNER_ID);
        v.setOperationalState(OperationalState.READY);
        v.setStorageBytes(1000L);
        return v;
    }

    @Test
    void cascadeDeleteForAccount_allVideosDeletedSuccessfully_resetsQuota() {
        UUID videoId = UUID.randomUUID();
        Video v = video(videoId);
        lenient().when(videoRepository.findByOwnerIdAndOperationalStateNot(eq(OWNER_ID), any(), any()))
            .thenReturn(new PageImpl<>(List.of(v)), new PageImpl<>(List.of()));
        lenient().when(videoRepository.findById(videoId)).thenReturn(java.util.Optional.of(v));
        lenient().when(outboxRepository.existsByVideoIdAndStatus(any(), any())).thenReturn(false);

        service.cascadeDeleteForAccount(OWNER_ID);

        verify(videoQuotaRepository).resetBytesForOwner(OWNER_ID);
    }

    @Test
    void cascadeDeleteForAccount_oneVideoFailsToDelete_doesNotResetQuota() {
        UUID videoId = UUID.randomUUID();
        // findById throws inside deleteVideo() for this id, simulating a failed purge
        lenient().when(videoRepository.findByOwnerIdAndOperationalStateNot(eq(OWNER_ID), any(), any()))
            .thenReturn(new PageImpl<>(List.of(video(videoId))), new PageImpl<>(List.of()));
        lenient().when(videoRepository.findById(videoId)).thenThrow(new RuntimeException("db error"));

        service.cascadeDeleteForAccount(OWNER_ID);

        verify(videoQuotaRepository, never()).resetBytesForOwner(any());
    }

    @Test
    void cascadeDeleteForAccount_noVideosOwned_stillResetsQuota() {
        when(videoRepository.findByOwnerIdAndOperationalStateNot(eq(OWNER_ID), any(), any()))
            .thenReturn(new PageImpl<>(List.of()));

        service.cascadeDeleteForAccount(OWNER_ID);

        verify(videoQuotaRepository).resetBytesForOwner(OWNER_ID);
    }

    // ── V184: GDPR erasure of a player purges the coach videos about them ──

    @Test
    void purgeVideosAboutPlayer_purgesEachSubjectVideo_decrementingTheCoachOwnersQuota() {
        UUID videoId = UUID.randomUUID();
        Video v = video(videoId);
        v.setSubjectPlayerId(77L);
        when(videoRepository.findUnpurgedIdsBySubjectPlayerId(77L)).thenReturn(List.of(videoId));
        when(videoRepository.findById(videoId)).thenReturn(java.util.Optional.of(v));
        when(outboxRepository.existsByVideoIdAndStatus(videoId, "PENDING")).thenReturn(false);

        assertThat(service.purgeVideosAboutPlayer(77L)).isZero();

        assertThat(v.getOperationalState()).isEqualTo(OperationalState.PURGED);
        // The owner (coach) survives the child's erasure: their quota is decremented, never reset.
        verify(quotaService).decrementStorageBytes(OWNER_ID, 1000L);
        verify(videoQuotaRepository, never()).resetBytesForOwner(any());
        ArgumentCaptor<VideoDeletionLog> logRow = ArgumentCaptor.forClass(VideoDeletionLog.class);
        verify(deletionLogRepository).save(logRow.capture());
        assertThat(logRow.getValue().getTriggeredBy()).isEqualTo(LifecycleTrigger.ACCOUNT_DELETION);
    }

    @Test
    void purgeVideosAboutPlayer_oneFailure_continuesAndReportsIt() {
        UUID bad = UUID.randomUUID();
        UUID good = UUID.randomUUID();
        Video v = video(good);
        when(videoRepository.findUnpurgedIdsBySubjectPlayerId(77L)).thenReturn(List.of(bad, good));
        when(videoRepository.findById(bad)).thenThrow(new RuntimeException("db error"));
        when(videoRepository.findById(good)).thenReturn(java.util.Optional.of(v));

        assertThat(service.purgeVideosAboutPlayer(77L)).isEqualTo(1);

        assertThat(v.getOperationalState()).isEqualTo(OperationalState.PURGED);
    }

    // ── B5: admin delete goes through the same bookkeeping as every other delete ──

    @Test
    void deleteByAdmin_readyVideo_purgesDecrementsQuotaWritesLogAndOutbox() {
        UUID videoId = UUID.randomUUID();
        Video v = video(videoId);
        v.setProviderAssetId("bunny-1");
        when(videoRepository.findById(videoId)).thenReturn(java.util.Optional.of(v));
        when(uploadSessionRepository.findFirstByVideoIdOrderByCreatedAtDesc(videoId)).thenReturn(java.util.Optional.empty());
        when(outboxRepository.existsByVideoIdAndStatus(videoId, "PENDING")).thenReturn(false);

        assertThat(service.deleteByAdmin(videoId)).isTrue();

        assertThat(v.getOperationalState()).isEqualTo(OperationalState.PURGED);
        verify(quotaService).decrementStorageBytes(OWNER_ID, 1000L);
        ArgumentCaptor<VideoDeletionLog> logRow = ArgumentCaptor.forClass(VideoDeletionLog.class);
        verify(deletionLogRepository).save(logRow.capture());
        assertThat(logRow.getValue().getTriggeredBy()).isEqualTo(LifecycleTrigger.ADMIN_DELETION);
        // The Bunny asset is deleted by VideoDeletionOutboxProcessor (DrillVideoRef ref-count aware),
        // never inline by the admin path.
        ArgumentCaptor<VideoDeletionOutbox> outbox = ArgumentCaptor.forClass(VideoDeletionOutbox.class);
        verify(outboxRepository).save(outbox.capture());
        assertThat(outbox.getValue().getBunnyVideoId()).isEqualTo("bunny-1");
        assertThat(outbox.getValue().getTriggeredBy()).isEqualTo(LifecycleTrigger.ADMIN_DELETION);
    }

    @Test
    void deleteByAdmin_pendingUploadSession_isExpired() {
        UUID videoId = UUID.randomUUID();
        Video v = video(videoId);
        v.setOperationalState(OperationalState.UPLOADING);
        v.setStorageBytes(null);
        UploadSession session = new UploadSession();
        session.setStatus(UploadSessionStatus.PENDING);
        when(videoRepository.findById(videoId)).thenReturn(java.util.Optional.of(v));
        when(uploadSessionRepository.findFirstByVideoIdOrderByCreatedAtDesc(videoId)).thenReturn(java.util.Optional.of(session));

        service.deleteByAdmin(videoId);

        assertThat(session.getStatus()).isEqualTo(UploadSessionStatus.EXPIRED);
        verify(uploadSessionRepository).save(session);
        verify(quotaService, never()).decrementStorageBytes(anyString(), org.mockito.ArgumentMatchers.longThat(b -> b > 0));
    }

    @Test
    void deleteByAdmin_alreadyPurged_isNoOp() {
        UUID videoId = UUID.randomUUID();
        Video v = video(videoId);
        v.setOperationalState(OperationalState.PURGED);
        when(videoRepository.findById(videoId)).thenReturn(java.util.Optional.of(v));

        assertThat(service.deleteByAdmin(videoId)).isFalse();

        verify(quotaService, never()).decrementStorageBytes(any(), org.mockito.ArgumentMatchers.anyLong());
        verify(deletionLogRepository, never()).save(any());
        verify(outboxRepository, never()).save(any());
    }
}
