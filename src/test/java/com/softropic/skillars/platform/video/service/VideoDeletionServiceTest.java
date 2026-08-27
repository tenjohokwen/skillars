package com.softropic.skillars.platform.video.service;

import com.softropic.skillars.platform.config.service.ConfigService;
import com.softropic.skillars.platform.video.contract.OperationalState;
import com.softropic.skillars.platform.video.repo.Video;
import com.softropic.skillars.platform.video.repo.VideoApprovalRequestRepository;
import com.softropic.skillars.platform.video.repo.VideoDeletionLogRepository;
import com.softropic.skillars.platform.video.repo.VideoDeletionOutboxRepository;
import com.softropic.skillars.platform.video.repo.VideoQuotaRepository;
import com.softropic.skillars.platform.video.repo.VideoRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.PageImpl;

import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
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
    @Mock private VideoApprovalRequestRepository approvalRequestRepository;
    @Mock private VideoQuotaRepository videoQuotaRepository;
    @Mock private QuotaService quotaService;
    @Mock private ConfigService configService;
    @Mock private ApplicationEventPublisher publisher;
    @Mock private VideoAccessGuard videoAccessGuard;

    private VideoDeletionService service;

    private static final String OWNER_ID = "owner-1";

    @BeforeEach
    void setUp() {
        service = new VideoDeletionService(videoRepository, outboxRepository, deletionLogRepository,
            approvalRequestRepository, videoQuotaRepository, quotaService, configService, publisher, videoAccessGuard);
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
}
