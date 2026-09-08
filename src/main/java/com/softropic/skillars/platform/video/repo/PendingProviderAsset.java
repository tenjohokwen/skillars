package com.softropic.skillars.platform.video.repo;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/**
 * skillars-deferred-100 AC2: one just-created video-provider asset, written in its own committed
 * transaction ({@code PendingProviderAssetTracker}) the instant
 * {@code VideoService.initializeUpload} creates it — <em>before</em> a caller's transaction can roll
 * back and take the local {@code Video} / {@code UploadSession} rows with it.
 *
 * <p>A successful upload deletes its own row (step 8 of {@code initializeUpload}, in the same
 * transaction as the {@code Video} persist). {@code ReconciliationWorkerScheduler.sweepOrphanedProviderAssets()}
 * purges anything left older than {@code app.video.orphan-asset.ttl} with no matching
 * {@code videos.provider_asset_id}.
 */
@Entity
@Table(schema = "main", name = "pending_provider_asset")
@Getter
@Setter
@NoArgsConstructor
public class PendingProviderAsset {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "provider_asset_id", nullable = false, unique = true)
    private String providerAssetId;

    @Column(nullable = false)
    private String provider;

    /**
     * skillars-deferred-100 code review (2026-09-08): failed-purge counter. The sweeper orders by
     * this ascending so a row whose {@code deleteAsset} keeps failing (a bad provider API key, a
     * persistent 5xx) sinks below fresh work instead of blocking the head of every batch, and a
     * {@code video.orphan_asset.stuck} gauge alerts once it crosses a threshold.
     */
    @Column(nullable = false)
    private int attempts = 0;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    public PendingProviderAsset(String providerAssetId, String provider) {
        this.providerAssetId = providerAssetId;
        this.provider = provider;
    }
}
