package com.softropic.skillars.platform.video.service;

import com.softropic.skillars.platform.filestorage.service.FileStorageService;
import com.softropic.skillars.infrastructure.video.VideoProviderAdapter;
import com.softropic.skillars.platform.security.contract.AccountRole;
import com.softropic.skillars.platform.security.contract.event.AccountDeletionRequestedEvent;
import com.softropic.skillars.platform.video.BaseVideoIT;
import com.softropic.skillars.platform.video.contract.AccessState;
import com.softropic.skillars.platform.video.contract.OperationalState;
import com.softropic.skillars.platform.video.contract.Visibility;
import com.softropic.skillars.platform.video.repo.Video;
import com.softropic.skillars.platform.video.repo.VideoApprovalRequest;
import com.softropic.skillars.platform.video.repo.VideoApprovalRequestRepository;
import com.softropic.skillars.platform.video.repo.VideoRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;

class AccountDeletionCascadeIT extends BaseVideoIT {

    @MockitoBean VideoProviderAdapter videoProviderAdapter;

    // skillars-deferred-160 AC8: present so this class shares the {VideoProviderAdapter, FileStorageService}
    // mock set (and Spring context) with its peers. Keep this exact field name; it is part of the cache key.
    @MockitoBean FileStorageService fileStorageService;

    @Autowired AccountDeletionCascadeListener listener;
    @Autowired VideoDeletionService videoDeletionService;
    @Autowired VideoRepository videoRepository;
    // videoDeletionOutboxRepository: a spy inherited from AbstractIntegrationTest (skillars-deferred-160 AC7).
    @Autowired VideoApprovalRequestRepository approvalRequestRepository;
    @Autowired ApplicationEventPublisher eventPublisher;
    @Autowired TransactionTemplate transactionTemplate;

    @BeforeEach
    void setUp() {
        doNothing().when(videoProviderAdapter).deleteAsset(org.mockito.ArgumentMatchers.anyString());
        videoDeletionOutboxRepository.deleteAll();
        approvalRequestRepository.deleteAll();
        videoRepository.deleteAll();
    }

    @Test
    void onAccountDeleted_coachWithVideos_allVideosPurgedAndOutboxEnqueued() {
        String coachOwnerId = "coach-uuid-" + java.util.UUID.randomUUID();
        Video v1 = seedVideo(coachOwnerId, "asset-c1");
        Video v2 = seedVideo(coachOwnerId, "asset-c2");

        AccountDeletionRequestedEvent event =
            new AccountDeletionRequestedEvent(coachOwnerId, AccountRole.COACH, List.of());
        listener.onAccountDeleted(event);

        assertThat(videoRepository.findById(v1.getId()).orElseThrow().getOperationalState())
            .isEqualTo(OperationalState.PURGED);
        assertThat(videoRepository.findById(v2.getId()).orElseThrow().getOperationalState())
            .isEqualTo(OperationalState.PURGED);
        assertThat(videoDeletionOutboxRepository.findAll()).hasSize(2);
    }

    @Test
    void cascadeDeleteAccount_cancelsPendingApprovals() {
        // Story deferred-6, AC4: pending VideoApprovalRequest rows for a deleted user's videos
        // must be cancelled once the account-deletion cascade completes.
        String parentOwnerId = "parent-uuid-" + java.util.UUID.randomUUID();
        Video v1 = seedVideo(parentOwnerId, "asset-a1");
        Video v2 = seedVideo(parentOwnerId, "asset-a2");
        VideoApprovalRequest req1 = seedPendingApproval(v1.getId(), 9340001L, 9340002L);
        VideoApprovalRequest req2 = seedPendingApproval(v2.getId(), 9340003L, 9340002L);

        AccountDeletionRequestedEvent event =
            new AccountDeletionRequestedEvent(parentOwnerId, AccountRole.PARENT, List.of());
        listener.onAccountDeleted(event);

        assertThat(approvalRequestRepository.findById(req1.getId()).orElseThrow().getStatus())
            .isEqualTo("CANCELLED");
        assertThat(approvalRequestRepository.findById(req2.getId()).orElseThrow().getStatus())
            .isEqualTo("CANCELLED");
    }

    /**
     * B5 regression: linkedPlayerIds are PlayerProfile ids — the same id space as the children's video
     * owner_id. The listener used to look each one up as a main."user" id and cascade on that user's
     * id instead, so a parent's erasure purged none of the children's videos.
     */
    @Test
    void onAccountDeleted_parentWithLinkedChildren_childVideosPurgedByProfileId() {
        long childProfileId = 987_654_321L; // no main."user" row with this id is needed (or wanted)
        Video child1 = seedVideo(String.valueOf(childProfileId), "asset-child-1");
        Video child2 = seedVideo(String.valueOf(childProfileId), "asset-child-2");

        listener.onAccountDeleted(new AccountDeletionRequestedEvent(
            "parent-" + java.util.UUID.randomUUID(), AccountRole.PARENT, List.of(childProfileId)));

        assertThat(videoRepository.findById(child1.getId()).orElseThrow().getOperationalState())
            .isEqualTo(OperationalState.PURGED);
        assertThat(videoRepository.findById(child2.getId()).orElseThrow().getOperationalState())
            .isEqualTo(OperationalState.PURGED);
    }

    @Test
    void onAccountDeleted_playerOwner_videosPurged() {
        String playerOwnerId = "55";  // Long as string
        seedVideo(playerOwnerId, "asset-p1");
        seedVideo(playerOwnerId, "asset-p2");

        AccountDeletionRequestedEvent event =
            new AccountDeletionRequestedEvent(playerOwnerId, AccountRole.PLAYER, List.of());
        listener.onAccountDeleted(event);

        long purgedCount = videoRepository.findAll().stream()
            .filter(v -> v.getOwnerId().equals(playerOwnerId))
            .filter(v -> v.getOperationalState() == OperationalState.PURGED)
            .count();
        assertThat(purgedCount).isEqualTo(2);
    }

    @Test
    void onAccountDeleted_alreadyPurgedVideos_idempotent() {
        String ownerId = "coach-idempotent-" + java.util.UUID.randomUUID();
        Video v = seedVideo(ownerId, "asset-already-purged");
        transactionTemplate.execute(status -> {
            Video loaded = videoRepository.findById(v.getId()).orElseThrow();
            loaded.setOperationalState(OperationalState.PURGED);
            videoRepository.save(loaded);
            return null;
        });

        AccountDeletionRequestedEvent event =
            new AccountDeletionRequestedEvent(ownerId, AccountRole.COACH, List.of());
        listener.onAccountDeleted(event);

        // Already PURGED: should not add a second outbox entry
        assertThat(videoDeletionOutboxRepository.existsByVideoIdAndStatus(v.getId(), "PENDING")).isFalse();
    }

    @Test
    void onAccountDeleted_playerRoleWithLinkedPlayerIds_linkedCascadeAborted() {
        // Invariant guard: PLAYER role must never have linkedPlayerIds
        String playerOwnerId = "77";
        seedVideo(playerOwnerId, "asset-inv1");

        AccountDeletionRequestedEvent event =
            new AccountDeletionRequestedEvent(playerOwnerId, AccountRole.PLAYER, List.of(100L, 200L));
        // Should still cascade for the primary account, but NOT for linkedPlayerIds
        listener.onAccountDeleted(event);

        // Primary account purged
        assertThat(videoRepository.findAll().stream()
            .filter(v -> v.getOwnerId().equals(playerOwnerId))
            .filter(v -> v.getOperationalState() == OperationalState.PURGED)
            .count()).isEqualTo(1);
    }

    @Test
    void onAccountDeleted_hiddenVideo_purged() {
        String ownerId = "coach-hidden-" + java.util.UUID.randomUUID();
        Video v = new Video();
        v.setOwnerId(ownerId);
        v.setProvider("bunny");
        v.setProviderAssetId("asset-hidden");
        v.setTitle("hidden-video.mp4");
        v.setOperationalState(OperationalState.HIDDEN);
        v.setAccessState(AccessState.ACTIVE);
        v.setVisibility(Visibility.PRIVATE);
        videoRepository.save(v);

        listener.onAccountDeleted(new AccountDeletionRequestedEvent(ownerId, AccountRole.COACH, List.of()));

        assertThat(videoRepository.findById(v.getId()).orElseThrow().getOperationalState())
            .isEqualTo(OperationalState.PURGED);
        assertThat(videoDeletionOutboxRepository.findAll()).hasSize(1);
    }

    @Test
    void onAccountDeleted_processingVideoWithNullBunnyId_purgedWithNullOutboxEntry() {
        String ownerId = "player-processing-" + java.util.UUID.randomUUID();
        Video v = new Video();
        v.setOwnerId(ownerId);
        v.setProvider("bunny");
        v.setProviderAssetId(null); // video never reached Bunny encoding
        v.setTitle("processing-video.mp4");
        v.setOperationalState(OperationalState.PROCESSING);
        v.setAccessState(AccessState.ACTIVE);
        v.setVisibility(Visibility.PRIVATE);
        videoRepository.save(v);

        listener.onAccountDeleted(new AccountDeletionRequestedEvent(ownerId, AccountRole.PLAYER, List.of()));

        assertThat(videoRepository.findById(v.getId()).orElseThrow().getOperationalState())
            .isEqualTo(OperationalState.PURGED);
        assertThat(videoDeletionOutboxRepository.findAll())
            .hasSize(1)
            .first()
            .satisfies(row -> assertThat(row.getBunnyVideoId()).isNull());
    }

    @Test
    void onAccountDeleted_secondVideoFailsMidCascade_firstVideoStaysCommittedSecondRollsBack() {
        String coachOwnerId = "coach-uuid-" + java.util.UUID.randomUUID();
        Video videoA = seedVideo(coachOwnerId, "asset-partial-a");
        Video videoB = seedVideo(coachOwnerId, "asset-partial-b");

        doThrow(new RuntimeException("simulated outbox failure"))
            .when(videoDeletionOutboxRepository)
            .save(argThat(outbox -> outbox.getVideoId().equals(videoB.getId())));

        AccountDeletionRequestedEvent event =
            new AccountDeletionRequestedEvent(coachOwnerId, AccountRole.COACH, List.of());
        listener.onAccountDeleted(event);

        assertThat(videoRepository.findById(videoA.getId()).orElseThrow().getOperationalState())
            .isEqualTo(OperationalState.PURGED);
        assertThat(videoDeletionOutboxRepository.existsByVideoIdAndStatus(videoA.getId(), "PENDING")).isTrue();

        assertThat(videoRepository.findById(videoB.getId()).orElseThrow().getOperationalState())
            .isEqualTo(OperationalState.READY);
        assertThat(videoDeletionOutboxRepository.existsByVideoIdAndStatus(videoB.getId(), "PENDING")).isFalse();
    }

    private VideoApprovalRequest seedPendingApproval(java.util.UUID videoId, long playerId, long parentId) {
        VideoApprovalRequest req = new VideoApprovalRequest();
        req.setVideoId(videoId);
        req.setPlayerId(playerId);
        req.setParentId(parentId);
        req.setStatus("PENDING");
        return approvalRequestRepository.save(req);
    }

    private Video seedVideo(String ownerId, String providerAssetId) {
        Video v = new Video();
        v.setOwnerId(ownerId);
        v.setProvider("bunny");
        v.setProviderAssetId(providerAssetId);
        v.setTitle("cascade-test.mp4");
        v.setOperationalState(OperationalState.READY);
        v.setAccessState(AccessState.ACTIVE);
        v.setVisibility(Visibility.PRIVATE);
        return videoRepository.save(v);
    }
}
