package com.softropic.skillars.platform.video.repo;

import com.softropic.skillars.platform.video.contract.OperationalState;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;


public interface VideoRepository extends JpaRepository<Video, UUID> {

    Optional<Video> findByProviderAssetId(String providerAssetId);

    @Query(value = """
        SELECT * FROM main.videos
        WHERE operational_state IN ('UPLOADING', 'PROCESSING')
        ORDER BY updated_at ASC
        LIMIT :limit
        FOR UPDATE SKIP LOCKED
        """, nativeQuery = true)
    List<Video> findNonTerminalForUpdate(@Param("limit") int limit);

    @Query("SELECT v FROM Video v WHERE v.id IN :ids AND v.operationalState = com.softropic.skillars.platform.video.contract.OperationalState.READY AND v.accessState = com.softropic.skillars.platform.video.contract.AccessState.ACTIVE")
    List<Video> findReadyAndActiveByIds(@Param("ids") List<UUID> ids);

    @Query(value = """
        SELECT * FROM main.videos
        WHERE operational_state = 'SCANNING'
          AND scanning_started_at < :threshold
          AND (moderation_lock_until IS NULL OR moderation_lock_until < :now)
        ORDER BY scanning_started_at ASC
        LIMIT :batchSize
        FOR UPDATE SKIP LOCKED
        """, nativeQuery = true)
    @Transactional
    List<Video> findScanningOlderThan(@Param("threshold") Instant threshold, @Param("now") Instant now, @Param("batchSize") int batchSize);

    @Query(value = """
        SELECT * FROM main.videos
        WHERE access_state = 'BLOCKED'
          AND lifecycle_locked_at < :threshold
        ORDER BY lifecycle_locked_at ASC
        LIMIT :batchSize
        FOR UPDATE SKIP LOCKED
        """, nativeQuery = true)
    List<Video> findBlockedExceedingThreshold(@Param("threshold") Instant threshold, @Param("batchSize") int batchSize);

    @Query(value = """
        SELECT * FROM main.videos
        WHERE access_state = 'ARCHIVED'
          AND archived_at < :threshold
        ORDER BY archived_at ASC
        LIMIT :batchSize
        FOR UPDATE SKIP LOCKED
        """, nativeQuery = true)
    List<Video> findArchivedExceedingThreshold(@Param("threshold") Instant threshold, @Param("batchSize") int batchSize);

    @Query(value = """
        SELECT * FROM main.videos
        WHERE owner_id = :ownerId
          AND operational_state = 'READY'
          AND access_state = 'ACTIVE'
        ORDER BY created_at ASC
        LIMIT :batchSize
        FOR UPDATE SKIP LOCKED
        """, nativeQuery = true)
    List<Video> findActiveReadyByOwner(@Param("ownerId") String ownerId, @Param("batchSize") int batchSize);

    @Query(value = """
        SELECT * FROM main.videos
        WHERE owner_id = :ownerId
          AND operational_state = 'READY'
          AND access_state = 'BLOCKED'
        ORDER BY lifecycle_locked_at ASC NULLS LAST
        LIMIT :batchSize
        FOR UPDATE SKIP LOCKED
        """, nativeQuery = true)
    List<Video> findBlockedReadyByOwner(@Param("ownerId") String ownerId, @Param("batchSize") int batchSize);

    Page<Video> findByOwnerIdAndOperationalStateNot(String ownerId, OperationalState state, Pageable pageable);
}
