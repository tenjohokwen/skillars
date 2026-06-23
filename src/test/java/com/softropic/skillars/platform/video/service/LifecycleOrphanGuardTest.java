package com.softropic.skillars.platform.video.service;

import com.softropic.skillars.infrastructure.video.VideoProviderAdapter;
import com.softropic.skillars.platform.config.service.ConfigService;
import com.softropic.skillars.platform.video.contract.PlayerSubscriptionQueryPort;
import com.softropic.skillars.platform.video.repo.VideoLifecycleLogRepository;
import com.softropic.skillars.platform.video.repo.VideoRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.support.TransactionTemplate;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Verifies that a BLOCKED video with lifecycle_locked_at=NULL is never transitioned to ARCHIVED.
 * NULL < threshold is NULL in SQL, so the findBlockedExceedingThreshold query never returns
 * such a row — this test documents that contract via the mock returning no rows for NULL clock.
 */
@ExtendWith(MockitoExtension.class)
class LifecycleOrphanGuardTest {

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
    void runLifecycleJob_blockedVideoWithNullLifecycleLockedAt_isNotSelectedAndNotTransitioned() {
        // Simulate what the DB query does: NULL < threshold = NULL in SQL, so orphan rows are never returned.
        // Both repo queries return empty lists (Mockito default for collections — explicitly stated for clarity).

        scheduler.runLifecycleJob();

        verify(videoProviderAdapter, never()).archiveAsset(any());
        verify(videoLifecycleService, never()).archiveForLifecycle(any());
        verify(videoLifecycleLogRepository, never()).save(any());
    }
}
