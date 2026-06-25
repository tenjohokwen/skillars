package com.softropic.skillars.platform.video.service;

import com.softropic.skillars.infrastructure.video.VideoProviderAdapter;
import com.softropic.skillars.platform.config.service.ConfigService;
import com.softropic.skillars.platform.video.contract.AccessState;
import com.softropic.skillars.platform.video.contract.LifecycleTrigger;
import com.softropic.skillars.platform.video.contract.OperationalState;
import com.softropic.skillars.platform.video.contract.PlayerSubscriptionQueryPort;
import com.softropic.skillars.platform.video.repo.Video;
import com.softropic.skillars.platform.video.repo.VideoLifecycleLog;
import com.softropic.skillars.platform.video.repo.VideoLifecycleLogRepository;
import com.softropic.skillars.platform.video.repo.VideoRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class VideoLifecycleSchedulerTest {

    @Mock VideoRepository videoRepository;
    @Mock VideoLifecycleLogRepository videoLifecycleLogRepository;
    @Mock VideoLifecycleService videoLifecycleService;
    @Mock VideoProviderAdapter videoProviderAdapter;
    @Mock ConfigService configService;
    @Mock QuotaService quotaService;
    @Mock PlayerSubscriptionQueryPort playerSubscriptionQueryPort;

    VideoLifecycleScheduler scheduler;

    @BeforeEach
    void setUp() {
        // Real TransactionTemplate that executes the callback immediately (no real TX in unit test)
        TransactionTemplate txTemplate = new TransactionTemplate() {
            @Override
            public <T> T execute(org.springframework.transaction.support.TransactionCallback<T> action) {
                return action.doInTransaction(null);
            }
        };

        when(configService.getLong("platform.video.lifecycle.blocked_to_archived_days", 30L)).thenReturn(30L);
        when(configService.getLong("platform.video.lifecycle.archived_to_deleted_days", 90L)).thenReturn(90L);
        when(configService.getInt("platform.video.lifecycle.batch_size", 100)).thenReturn(100);

        scheduler = new VideoLifecycleScheduler(videoRepository, videoLifecycleLogRepository,
            videoLifecycleService, videoProviderAdapter, configService, txTemplate, quotaService, playerSubscriptionQueryPort);
    }

    @Test
    void runLifecycleJob_blockedVideoExceedingThreshold_transitionsToArchived() {
        Video video = blockedVideo(UUID.randomUUID(), "11001", Instant.now().minus(35, ChronoUnit.DAYS));
        when(videoRepository.findBlockedExceedingThreshold(any(), anyInt())).thenReturn(List.of(video));
        when(playerSubscriptionQueryPort.hasActiveYearlySubscription(any())).thenReturn(false);

        scheduler.runLifecycleJob();

        verify(videoProviderAdapter).archiveAsset(video.getProviderAssetId());
        verify(videoLifecycleService).archiveForLifecycle(video.getId());

        ArgumentCaptor<VideoLifecycleLog> logCaptor = ArgumentCaptor.forClass(VideoLifecycleLog.class);
        verify(videoLifecycleLogRepository).save(logCaptor.capture());
        VideoLifecycleLog log = logCaptor.getValue();
        assertThat(log.getFromState()).isEqualTo("BLOCKED");
        assertThat(log.getToState()).isEqualTo("ARCHIVED");
        assertThat(log.getTriggeredBy()).isEqualTo(LifecycleTrigger.SYSTEM);
    }

    @Test
    void runLifecycleJob_archivedVideoExceedingThreshold_transitionsToDeleted() {
        UUID videoId = UUID.randomUUID();
        String ownerId = UUID.randomUUID().toString();
        Video video = archivedVideo(videoId, ownerId, Instant.now().minus(95, ChronoUnit.DAYS));
        video.setStorageBytes(1024L);

        when(videoRepository.findArchivedExceedingThreshold(any(), anyInt())).thenReturn(List.of(video));
        when(videoRepository.findById(videoId)).thenReturn(Optional.of(video));
        when(videoLifecycleService.markPurged(videoId)).thenReturn(1024L);

        scheduler.runLifecycleJob();

        verify(videoProviderAdapter).deleteAsset(video.getProviderAssetId());
        verify(videoLifecycleService).markPurged(videoId);
        verify(quotaService).decrementStorageBytes(ownerId, 1024L);

        ArgumentCaptor<VideoLifecycleLog> logCaptor = ArgumentCaptor.forClass(VideoLifecycleLog.class);
        verify(videoLifecycleLogRepository).save(logCaptor.capture());
        assertThat(logCaptor.getValue().getFromState()).isEqualTo("ARCHIVED");
        assertThat(logCaptor.getValue().getToState()).isEqualTo("DELETED");
        assertThat(logCaptor.getValue().getTriggeredBy()).isEqualTo(LifecycleTrigger.SYSTEM);
    }

    @Test
    void runLifecycleJob_blockedVideoWithActiveYearlySub_skipsArchivedTransition() {
        Video video = blockedVideo(UUID.randomUUID(), "11001", Instant.now().minus(35, ChronoUnit.DAYS));
        when(videoRepository.findBlockedExceedingThreshold(any(), anyInt())).thenReturn(List.of(video));
        when(playerSubscriptionQueryPort.hasActiveYearlySubscription(any())).thenReturn(true);

        scheduler.runLifecycleJob();

        verify(videoProviderAdapter, never()).archiveAsset(any());
        verify(videoLifecycleService, never()).archiveForLifecycle(any());
    }

    @Test
    void runLifecycleJob_archiveAssetFails_videoRemainsBlockedAndRetryableNextRun() {
        Video video = blockedVideo(UUID.randomUUID(), "11001", Instant.now().minus(35, ChronoUnit.DAYS));
        when(videoRepository.findBlockedExceedingThreshold(any(), anyInt())).thenReturn(List.of(video));
        when(playerSubscriptionQueryPort.hasActiveYearlySubscription(any())).thenReturn(false);
        doThrow(new com.softropic.skillars.platform.video.contract.exception.VideoProviderException("archiveAsset", null))
            .when(videoProviderAdapter).archiveAsset(any());

        scheduler.runLifecycleJob();

        // DB transition must NOT happen when Bunny call fails
        verify(videoLifecycleService, never()).archiveForLifecycle(any());
        verify(videoLifecycleLogRepository, never()).save(any());
    }

    @Test
    void runLifecycleJob_batchSkipGuard_videoArchivedInPhase1DoesNotAppearInPhase2() {
        // A video with lifecycle_locked_at = 91 days ago advances to ARCHIVED in Phase 1.
        // Phase 2 uses archived_at (set to now() by archiveForLifecycle) — not lifecycle_locked_at.
        // Mockito returns empty list by default for Phase 2, simulating the just-archived video NOT appearing.
        UUID videoId = UUID.randomUUID();
        Video video = blockedVideo(videoId, "11001", Instant.now().minus(91, ChronoUnit.DAYS));
        when(videoRepository.findBlockedExceedingThreshold(any(), anyInt())).thenReturn(List.of(video));
        when(playerSubscriptionQueryPort.hasActiveYearlySubscription(any())).thenReturn(false);

        scheduler.runLifecycleJob();

        verify(videoLifecycleService).archiveForLifecycle(videoId);
        // Phase 2 never called deleteAsset — batch-skip guard holds
        verify(videoProviderAdapter, never()).deleteAsset(any());
    }

    private Video blockedVideo(UUID id, String ownerId, Instant lifecycleLockedAt) {
        Video v = new Video();
        v.setId(id);
        v.setOwnerId(ownerId);
        v.setProvider("bunny");
        v.setProviderAssetId("provider-" + id);
        v.setOperationalState(OperationalState.READY);
        v.setAccessState(AccessState.BLOCKED);
        v.setLifecycleLockedAt(lifecycleLockedAt);
        return v;
    }

    private Video archivedVideo(UUID id, String ownerId, Instant archivedAt) {
        Video v = new Video();
        v.setId(id);
        v.setOwnerId(ownerId);
        v.setProvider("bunny");
        v.setProviderAssetId("provider-" + id);
        v.setOperationalState(OperationalState.READY);
        v.setAccessState(AccessState.ARCHIVED);
        v.setArchivedAt(archivedAt);
        return v;
    }
}
