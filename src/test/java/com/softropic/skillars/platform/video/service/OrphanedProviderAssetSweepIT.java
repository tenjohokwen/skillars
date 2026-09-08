package com.softropic.skillars.platform.video.service;

import com.softropic.skillars.infrastructure.video.VideoProviderAdapter;
import com.softropic.skillars.platform.video.BaseVideoIT;
import com.softropic.skillars.platform.video.contract.AccessState;
import com.softropic.skillars.platform.video.contract.OperationalState;
import com.softropic.skillars.platform.video.contract.ReconciliationIncidentType;
import com.softropic.skillars.platform.video.contract.Visibility;
import com.softropic.skillars.platform.video.contract.exception.VideoProviderException;
import com.softropic.skillars.platform.video.repo.PendingProviderAssetRepository;
import com.softropic.skillars.platform.video.repo.ReconciliationIncidentRepository;
import com.softropic.skillars.platform.video.repo.Video;
import com.softropic.skillars.platform.video.repo.VideoRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * skillars-deferred-100 AC2: {@code ReconciliationWorkerScheduler.sweepOrphanedProviderAssets()}
 * purges a video-provider asset whose caller transaction rolled back after
 * {@code VideoService.initializeUpload} created it — the case {@code reconcile()} cannot see
 * because it only iterates rows that have a {@code Video}.
 */
class OrphanedProviderAssetSweepIT extends BaseVideoIT {

    @MockitoBean VideoProviderAdapter videoProviderAdapter;

    @Autowired ReconciliationWorkerScheduler scheduler;
    @Autowired PendingProviderAssetTracker pendingProviderAssetTracker;
    @Autowired PendingProviderAssetRepository pendingProviderAssetRepository;
    @Autowired ReconciliationIncidentRepository incidentRepository;
    @Autowired VideoRepository videoRepository;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired TransactionTemplate transactionTemplate;

    @BeforeEach
    void setUp() {
        transactionTemplate.executeWithoutResult(s -> {
            jdbcTemplate.update("DELETE FROM main.reconciliation_incidents");
            jdbcTemplate.update("DELETE FROM main.pending_provider_asset");
            jdbcTemplate.update("DELETE FROM main.videos");
        });
    }

    @Test
    void oldTrackingRow_withNoVideo_purgesAssetAndRecordsIncident() {
        String assetId = "asset-orphan-" + System.nanoTime();
        seedTrackingRow(assetId, Instant.now().minusSeconds(3600)); // > 30 min TTL

        scheduler.sweepOrphanedProviderAssets();

        verify(videoProviderAdapter).deleteAsset(assetId);
        assertThat(pendingProviderAssetRepository.findAll()).isEmpty();
        assertThat(incidentRepository.findAll())
            .singleElement()
            .satisfies(i -> {
                assertThat(i.getIncidentType()).isEqualTo(ReconciliationIncidentType.ORPHANED_ASSET);
                assertThat(i.getProviderAssetId()).isEqualTo(assetId);
                assertThat(i.getVideoId()).isNull();
            });
    }

    @Test
    void oldTrackingRow_withCommittedVideo_isCleanedUpWithoutPurgeOrIncident() {
        String assetId = "asset-committed-" + System.nanoTime();
        seedTrackingRow(assetId, Instant.now().minusSeconds(3600));
        seedVideoWithAsset(assetId);

        scheduler.sweepOrphanedProviderAssets();

        verify(videoProviderAdapter, never()).deleteAsset(any());
        assertThat(pendingProviderAssetRepository.findAll()).isEmpty();
        assertThat(incidentRepository.findAll()).isEmpty();
    }

    @Test
    void recentTrackingRow_withinTtl_isLeftAlone() {
        String assetId = "asset-inflight-" + System.nanoTime();
        seedTrackingRow(assetId, Instant.now()); // fresh — still inside the initialize->confirm window

        scheduler.sweepOrphanedProviderAssets();

        verify(videoProviderAdapter, never()).deleteAsset(any());
        assertThat(pendingProviderAssetRepository.findAll()).hasSize(1);
        assertThat(incidentRepository.findAll()).isEmpty();
    }

    @Test
    void transientProviderError_leavesTheRowForTheNextCycle() {
        String assetId = "asset-transient-" + System.nanoTime();
        seedTrackingRow(assetId, Instant.now().minusSeconds(3600));
        doThrow(new VideoProviderException("deleteAsset", new RuntimeException("timeout")))
            .when(videoProviderAdapter).deleteAsset(assetId);

        scheduler.sweepOrphanedProviderAssets();

        assertThat(pendingProviderAssetRepository.findAll()).hasSize(1);
        assertThat(incidentRepository.findAll()).isEmpty();
    }

    @Test
    void trackerRecord_survivesTheCallersTransactionRollback() {
        String assetId = "asset-rollback-" + System.nanoTime();

        try {
            transactionTemplate.executeWithoutResult(s -> {
                // The caller does its own work then the transaction rolls back — the REQUIRES_NEW
                // record() write must NOT roll back with it.
                pendingProviderAssetTracker.record(assetId, "bunny");
                throw new RuntimeException("caller blew up after the provider asset was created");
            });
        } catch (RuntimeException expected) {
            // ignored — that is the rollback
        }

        assertThat(pendingProviderAssetRepository.findAll())
            .as("the tracking row is committed independently and survives the caller rollback")
            .singleElement()
            .satisfies(r -> assertThat(r.getProviderAssetId()).isEqualTo(assetId));
    }

    private void seedTrackingRow(String assetId, Instant createdAt) {
        transactionTemplate.executeWithoutResult(s -> jdbcTemplate.update(
            "INSERT INTO main.pending_provider_asset (provider_asset_id, provider, created_at) VALUES (?, 'bunny', ?)",
            assetId, Timestamp.from(createdAt)));
    }

    private void seedVideoWithAsset(String assetId) {
        Video v = new Video();
        v.setOwnerId("owner-orphan-sweep");
        v.setProvider("bunny");
        v.setProviderAssetId(assetId);
        v.setTitle("committed.mp4");
        v.setOperationalState(OperationalState.UPLOADING);
        v.setAccessState(AccessState.ACTIVE);
        v.setVisibility(Visibility.PRIVATE);
        transactionTemplate.executeWithoutResult(s -> videoRepository.save(v));
    }
}
