package com.softropic.skillars.platform.video.service;

import com.softropic.skillars.platform.filestorage.service.FileStorageService;
import com.softropic.skillars.infrastructure.video.AssetStatus;
import com.softropic.skillars.infrastructure.video.VideoProviderAdapter;
import com.softropic.skillars.platform.video.BaseVideoIT;
import com.softropic.skillars.platform.video.contract.*;
import com.softropic.skillars.platform.video.contract.exception.TerminalStateViolationException;
import com.softropic.skillars.platform.video.repo.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class AdminVideoIT extends BaseVideoIT {

    @MockitoBean
    VideoProviderAdapter videoProviderAdapter;

    // skillars-deferred-160 AC8: present so this class shares the {VideoProviderAdapter, FileStorageService}
    // mock set (and Spring context) with its peers. Keep this exact field name; it is part of the cache key.
    @MockitoBean FileStorageService fileStorageService;

    @Autowired AdminVideoService adminVideoService;
    @Autowired VideoRepository videoRepository;
    @Autowired UploadSessionRepository uploadSessionRepository;
    @Autowired PlaybackTokenRepository playbackTokenRepository;
    @Autowired VideoWebhookEventRepository webhookEventRepository;
    @Autowired ReconciliationIncidentRepository incidentRepository;
    @Autowired VideoModerationScanRepository moderationScanRepository;

    @BeforeEach
    void setUp() {
        transactionTemplate.execute(status -> {
            jdbcTemplate.update("DELETE FROM main.video_deletion_outbox");
            jdbcTemplate.update("DELETE FROM main.video_deletion_log");
            return null;
        });
        moderationScanRepository.deleteAll();
        incidentRepository.deleteAll();
        playbackTokenRepository.deleteAll();
        uploadSessionRepository.deleteAll();
        webhookEventRepository.deleteAll();
        videoRepository.deleteAll();
    }

    @Test
    void setVideoAccessState_blocksReadyVideo_updatesAccessState() {
        Video video = seedVideo(OperationalState.READY, AccessState.ACTIVE, "asset-admin-1");

        Video updated = adminVideoService.setVideoAccessState(video.getId(), AccessState.BLOCKED);

        assertThat(updated.getAccessState()).isEqualTo(AccessState.BLOCKED);
        assertThat(videoRepository.findById(video.getId()).orElseThrow().getAccessState())
                .isEqualTo(AccessState.BLOCKED);
    }

    @Test
    void setVideoAccessState_deletedVideo_throwsTerminalStateViolation() {
        Video video = seedVideo(OperationalState.DELETED, AccessState.ACTIVE, "asset-admin-2");

        assertThatThrownBy(() -> adminVideoService.setVideoAccessState(video.getId(), AccessState.BLOCKED))
                .isInstanceOf(TerminalStateViolationException.class);
    }

    /**
     * B5: an admin delete gets the same bookkeeping as a user delete — PURGED, committed storage bytes
     * decremented from the owner's quota, an ADMIN_DELETION deletion-log row (V182 admits the value),
     * and a deletion-outbox row (whose processor honours the DrillVideoRef ref-count) instead of an
     * inline Bunny delete. A still-PENDING upload session is expired.
     */
    @Test
    void deleteVideo_readyVideo_purgesDecrementsQuotaLogsAndDefersAssetDeletionToOutbox() {
        Video video = seedVideo(OperationalState.READY, AccessState.ACTIVE, "asset-admin-delete");
        video.setStorageBytes(40_000L);
        video = videoRepository.save(video);
        UploadSession pendingSession = seedPendingSession(video, UUID.randomUUID().toString());
        transactionTemplate.execute(status -> jdbcTemplate.update(
            "INSERT INTO main.video_quotas (user_id, storage_used_bytes, bandwidth_used_bytes, bandwidth_period_start) "
                + "VALUES ('owner-admin-it', 100000, 0, now()) "
                + "ON CONFLICT (user_id) DO UPDATE SET storage_used_bytes = 100000"));

        adminVideoService.deleteVideo(video.getId());

        assertThat(videoRepository.findById(video.getId()).orElseThrow().getOperationalState())
                .isEqualTo(OperationalState.PURGED);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT storage_used_bytes FROM main.video_quotas WHERE user_id = 'owner-admin-it'", Long.class))
                .isEqualTo(60_000L);
        assertThat(jdbcTemplate.queryForList(
                "SELECT triggered_by FROM main.video_deletion_log WHERE video_id = ?", String.class, video.getId()))
                .containsExactly("ADMIN_DELETION");
        assertThat(jdbcTemplate.queryForList(
                "SELECT bunny_video_id FROM main.video_deletion_outbox WHERE video_id = ? AND triggered_by = 'ADMIN_DELETION'",
                String.class, video.getId()))
                .containsExactly("asset-admin-delete");
        verify(videoProviderAdapter, never()).deleteAsset(any());
        UploadSession refreshed = uploadSessionRepository.findById(pendingSession.getId()).orElseThrow();
        assertThat(refreshed.getStatus()).isEqualTo(UploadSessionStatus.EXPIRED);
    }

    @Test
    void deleteVideo_processingVideo_noPendingSession_purges_andRepeatIsIdempotent() {
        Video video = seedVideo(OperationalState.PROCESSING, AccessState.ACTIVE, "asset-admin-del-no-sess");

        adminVideoService.deleteVideo(video.getId());
        adminVideoService.deleteVideo(video.getId());

        assertThat(videoRepository.findById(video.getId()).orElseThrow().getOperationalState())
                .isEqualTo(OperationalState.PURGED);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM main.video_deletion_log WHERE video_id = ?", Long.class, video.getId()))
                .isEqualTo(1L);
    }

    @Test
    void getUploadSession_returnsSession() {
        Video video = seedVideo(OperationalState.PROCESSING, AccessState.ACTIVE, "asset-admin-sess");
        UploadSession session = seedPendingSession(video, "handle-get-session");

        UploadSession found = adminVideoService.getUploadSession(session.getId());

        assertThat(found.getId()).isEqualTo(session.getId());
        assertThat(found.getVideoId()).isEqualTo(video.getId());
    }

    @Test
    void listVideoSessions_returnsAllSessionsOrderedByCreatedAtDesc() {
        Video video = seedVideo(OperationalState.PROCESSING, AccessState.ACTIVE, "asset-admin-sessions");
        UploadSession s1 = seedPendingSession(video, "handle-1");
        UploadSession s2 = seedPendingSession(video, "handle-2");

        List<UploadSession> sessions = adminVideoService.listVideoSessions(video.getId());

        assertThat(sessions).hasSizeGreaterThanOrEqualTo(2);
        assertThat(sessions).extracting(UploadSession::getId).contains(s1.getId(), s2.getId());
    }

    @Test
    void triggerReconciliation_processingVideoReadyAtProvider_correctedToReady() {
        Video video = seedVideo(OperationalState.PROCESSING, AccessState.ACTIVE, "asset-admin-recon-1");
        when(videoProviderAdapter.getAssetStatus("asset-admin-recon-1")).thenReturn(AssetStatus.READY);

        ReconcileResponse response = adminVideoService.triggerReconciliation(video.getId());

        assertThat(response.operationalState()).isEqualTo(OperationalState.READY);
        assertThat(response.incident()).isNotNull();
        assertThat(response.incident().incidentType()).isEqualTo(ReconciliationIncidentType.STATE_CORRECTED);
    }

    @Test
    void triggerReconciliation_processingVideoMissingAtProvider_markedFailed() {
        Video video = seedVideo(OperationalState.PROCESSING, AccessState.ACTIVE, "asset-admin-recon-2");
        when(videoProviderAdapter.getAssetStatus("asset-admin-recon-2")).thenReturn(AssetStatus.DELETED);

        ReconcileResponse response = adminVideoService.triggerReconciliation(video.getId());

        assertThat(response.operationalState()).isEqualTo(OperationalState.FAILED);
        assertThat(response.incident()).isNotNull();
        assertThat(response.incident().incidentType()).isEqualTo(ReconciliationIncidentType.MISSING_ASSET);
    }

    @Test
    void triggerReconciliation_deletedVideo_returnsCurrentStateNoAction() {
        Video video = seedVideo(OperationalState.DELETED, AccessState.ACTIVE, "asset-admin-recon-3");

        ReconcileResponse response = adminVideoService.triggerReconciliation(video.getId());

        assertThat(response.operationalState()).isEqualTo(OperationalState.DELETED);
        assertThat(response.incident()).isNull();
        verify(videoProviderAdapter, never()).getAssetStatus(any());
    }

    // ── helpers ────────────────────────────────────────────────────────────

    @Test
    void setVideoAccessState_reblockAfterRestore_startsAFreshLifecycleClock() {
        Video video = seedVideo(OperationalState.READY, AccessState.ACTIVE, "asset-admin-reblock");
        adminVideoService.setVideoAccessState(video.getId(), AccessState.BLOCKED);
        transactionTemplate.execute(status -> {
            jdbcTemplate.update("UPDATE main.videos SET lifecycle_locked_at = now() - interval '90 days' WHERE id = ?",
                    video.getId());
            return null;
        });
        adminVideoService.setVideoAccessState(video.getId(), AccessState.ACTIVE);

        Video reblocked = adminVideoService.setVideoAccessState(video.getId(), AccessState.BLOCKED);

        assertThat(reblocked.getLifecycleLockedAt()).isAfter(Instant.now().minusSeconds(60));
    }

    @Test
    void setVideoAccessState_repeatedBlock_keepsTheExistingClock() {
        Video video = seedVideo(OperationalState.READY, AccessState.ACTIVE, "asset-admin-block-twice");
        adminVideoService.setVideoAccessState(video.getId(), AccessState.BLOCKED);
        transactionTemplate.execute(status -> {
            jdbcTemplate.update("UPDATE main.videos SET lifecycle_locked_at = now() - interval '10 days' WHERE id = ?",
                    video.getId());
            return null;
        });

        Video again = adminVideoService.setVideoAccessState(video.getId(), AccessState.BLOCKED);

        assertThat(again.getLifecycleLockedAt()).isBefore(Instant.now().minus(java.time.Duration.ofDays(9)));
    }

    // ---- admin video review queue (story 6.3 / FR-ADM-002) ----

    @Test
    void getReviewQueue_attention_returnsLockedFailedAndBlocked_excludingHealthyAndDeleted() {
        Video locked = seedVideo(OperationalState.LOCKED, AccessState.ACTIVE, "asset-q-locked");
        Video failed = seedVideo(OperationalState.FAILED, AccessState.ACTIVE, "asset-q-failed");
        Video blocked = seedBlockedVideo("asset-q-blocked");
        seedVideo(OperationalState.READY, AccessState.ACTIVE, "asset-q-ready");
        seedVideo(OperationalState.DELETED, AccessState.ACTIVE, "asset-q-deleted");
        moderationScanRepository.upsertScan(locked.getId(), "VIDEOINTEL", "FLAGGED", 0.97, "violence");
        moderationScanRepository.upsertScan(locked.getId(), "ARACHNID", "PASSED", null, null);

        var page = adminVideoService.getReviewQueue(AdminVideoQueueFilter.ATTENTION, 0);

        assertThat(page.getTotalElements()).isEqualTo(3);
        assertThat(page.getContent()).extracting(AdminVideoQueueItemDto::id)
                .containsExactlyInAnyOrder(locked.getId(), failed.getId(), blocked.getId());
        AdminVideoQueueItemDto lockedRow = page.getContent().stream()
                .filter(r -> r.id().equals(locked.getId())).findFirst().orElseThrow();
        assertThat(lockedRow.flaggedLayers()).containsExactly("VIDEOINTEL");
        AdminVideoQueueItemDto blockedRow = page.getContent().stream()
                .filter(r -> r.id().equals(blocked.getId())).findFirst().orElseThrow();
        assertThat(blockedRow.lifecycleLockedAt()).isNotNull();
        assertThat(blockedRow.flaggedLayers()).isEmpty();
    }

    @Test
    void getReviewQueue_singleFilters_returnOnlyTheirCondition() {
        Video locked = seedVideo(OperationalState.LOCKED, AccessState.ACTIVE, "asset-qf-locked");
        Video failed = seedVideo(OperationalState.FAILED, AccessState.ACTIVE, "asset-qf-failed");
        Video blocked = seedBlockedVideo("asset-qf-blocked");

        assertThat(adminVideoService.getReviewQueue(AdminVideoQueueFilter.LOCKED, 0).getContent())
                .extracting(AdminVideoQueueItemDto::id).containsExactly(locked.getId());
        assertThat(adminVideoService.getReviewQueue(AdminVideoQueueFilter.FAILED, 0).getContent())
                .extracting(AdminVideoQueueItemDto::id).containsExactly(failed.getId());
        assertThat(adminVideoService.getReviewQueue(AdminVideoQueueFilter.BLOCKED, 0).getContent())
                .extracting(AdminVideoQueueItemDto::id).containsExactly(blocked.getId());
    }

    @Test
    void getReviewQueue_orderedByMostRecentlyUpdatedFirst() {
        Video older = seedVideo(OperationalState.FAILED, AccessState.ACTIVE, "asset-qo-older");
        Video newer = seedVideo(OperationalState.FAILED, AccessState.ACTIVE, "asset-qo-newer");
        transactionTemplate.execute(status -> {
            jdbcTemplate.update("UPDATE main.videos SET updated_at = now() - interval '2 days' WHERE id = ?", older.getId());
            jdbcTemplate.update("UPDATE main.videos SET updated_at = now() - interval '1 day' WHERE id = ?", newer.getId());
            return null;
        });

        assertThat(adminVideoService.getReviewQueue(AdminVideoQueueFilter.FAILED, 0).getContent())
                .extracting(AdminVideoQueueItemDto::id).containsExactly(newer.getId(), older.getId());
    }

    private Video seedBlockedVideo(String providerAssetId) {
        Video v = seedVideo(OperationalState.READY, AccessState.ACTIVE, providerAssetId);
        return adminVideoService.setVideoAccessState(v.getId(), AccessState.BLOCKED);
    }

    private Video seedVideo(OperationalState opState, AccessState accessState, String providerAssetId) {
        Video v = new Video();
        v.setOwnerId("owner-admin-it");
        v.setProvider("bunny");
        v.setProviderAssetId(providerAssetId);
        v.setTitle("test-admin.mp4");
        v.setOperationalState(opState);
        v.setAccessState(accessState);
        v.setVisibility(Visibility.PRIVATE);
        return videoRepository.save(v);
    }

    private UploadSession seedPendingSession(Video video, String reservationHandle) {
        UploadSession s = new UploadSession();
        s.setVideoId(video.getId());
        s.setProviderUploadId("provider-upload-" + reservationHandle);
        s.setStatus(UploadSessionStatus.PENDING);
        s.setReservedBytes(100_000_000L);
        s.setReservationHandle(reservationHandle);
        s.setExpiresAt(Instant.now().plusSeconds(3600));
        return uploadSessionRepository.save(s);
    }
}
