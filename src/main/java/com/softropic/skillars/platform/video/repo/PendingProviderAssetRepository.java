package com.softropic.skillars.platform.video.repo;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;

public interface PendingProviderAssetRepository extends JpaRepository<PendingProviderAsset, Long> {

    /**
     * Sweeper batch: records older than the orphan-asset TTL. Fewest-attempts first, then oldest,
     * so a permanently-failing row (skillars-deferred-100 code review 2026-09-08) sinks below fresh
     * work rather than occupying the head of every batch.
     */
    List<PendingProviderAsset> findByCreatedAtBeforeOrderByAttemptsAscCreatedAtAsc(Instant cutoff, Pageable pageable);

    void deleteByProviderAssetId(String providerAssetId);

    /** Rows the sweeper has repeatedly failed to purge — surfaced as a gauge + ERROR log. */
    long countByAttemptsGreaterThanEqual(int attempts);

    @Modifying
    @Query("UPDATE PendingProviderAsset p SET p.attempts = p.attempts + 1 WHERE p.id = :id")
    void incrementAttempts(@Param("id") Long id);

    /**
     * skillars-deferred-100 code review (2026-09-08): idempotent insert. {@code ON CONFLICT DO
     * NOTHING} never raises a constraint violation, so a retry of the same {@code initializeUpload}
     * (or a concurrent one) is a genuine no-op — unlike {@code save()} + catch, which would mark the
     * caller's {@code REQUIRES_NEW} transaction rollback-only. Returns 1 if inserted, 0 if the id
     * was already tracked.
     */
    @Modifying
    @Query(nativeQuery = true, value =
        "INSERT INTO main.pending_provider_asset (provider_asset_id, provider, attempts, created_at) "
        + "VALUES (:providerAssetId, :provider, 0, now()) "
        + "ON CONFLICT (provider_asset_id) DO NOTHING")
    int insertIfAbsent(@Param("providerAssetId") String providerAssetId, @Param("provider") String provider);
}
