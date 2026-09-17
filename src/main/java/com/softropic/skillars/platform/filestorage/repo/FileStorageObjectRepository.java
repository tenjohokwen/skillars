package com.softropic.skillars.platform.filestorage.repo;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface FileStorageObjectRepository extends JpaRepository<FileStorageObject, Long> {

    @Query("SELECT COALESCE(SUM(f.sizeBytes), 0) FROM FileStorageObject f WHERE f.ownerId = :ownerId")
    long sumSizeBytesByOwnerId(@Param("ownerId") String ownerId);

    Optional<FileStorageObject> findByKey(String key);

    Optional<FileStorageObject> findByKeyAndDeletedAtIsNull(String key);

    @Modifying
    @Transactional
    @Query("UPDATE FileStorageObject f SET f.deletedAt = :deletedAt WHERE f.key = :key AND f.deletedAt IS NULL")
    int softDeleteByKey(@Param("key") String key, @Param("deletedAt") Instant deletedAt);

    @Transactional
    @Query(value = "SELECT * FROM main.file_storage_objects WHERE deleted_at < :cutoff AND physical_deleted_at IS NULL ORDER BY deleted_at ASC LIMIT :limit FOR UPDATE SKIP LOCKED",
           nativeQuery = true)
    List<FileStorageObject> findEligibleForPhysicalDeletion(@Param("cutoff") Instant cutoff, @Param("limit") int limit);

    // skillars-deferred-119 AC1: conditional WHERE clause (mirrors softDeleteByKey above) so the
    // caller can detect a losing race — see DeletionSchedulerService.processDeletions, which only
    // enqueues an OutboxReplicationJob when this returns 1.
    @Modifying
    @Transactional
    @Query("UPDATE FileStorageObject f SET f.physicalDeletedAt = :ts WHERE f.id = :id AND f.physicalDeletedAt IS NULL")
    int markPhysicallyDeleted(@Param("id") Long id, @Param("ts") Instant ts);

    List<FileStorageObject> findAllByOwnerIdAndDeletedAtIsNull(String ownerId);
}
