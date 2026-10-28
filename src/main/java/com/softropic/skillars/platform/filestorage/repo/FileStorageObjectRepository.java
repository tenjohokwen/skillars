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

    // Soft-deleted objects no longer count against the owner's quota: the user cannot reach them any more, and
    // the bytes are physically removed after app.storage.deletion.retention-days anyway.
    @Query("SELECT COALESCE(SUM(f.sizeBytes), 0) FROM FileStorageObject f WHERE f.ownerId = :ownerId AND f.deletedAt IS NULL")
    long sumSizeBytesByOwnerId(@Param("ownerId") String ownerId);

    /**
     * C5: per-owner serialisation of quota-consuming confirms. {@code FileStorageService.confirmUpload}
     * takes this as the first statement of its insert transaction and only then re-reads
     * {@link #sumSizeBytesByOwnerId}, so two parallel confirms by one owner can no longer both read the
     * pre-insert usage and together overshoot the quota (READ COMMITTED: the second sees the first's
     * committed row once it gets the lock). A transaction-scoped advisory lock rather than a row lock
     * because there is no per-owner quota row to lock; released automatically at commit/rollback, and
     * held only for the insert itself (the S3 HEAD runs before the transaction). The two-int form
     * {@code (namespace, hashtext(owner))} keeps it out of the single-bigint key space other advisory
     * locks use ({@code EnvelopeEntityRepository.acquireSendIdLock}). Not {@code @Modifying}: a SELECT of a
     * void function, same shape as that method.
     */
    @Query(value = "SELECT pg_advisory_xact_lock(" + QUOTA_LOCK_NAMESPACE + ", hashtext(:ownerId))", nativeQuery = true)
    void acquireOwnerQuotaLock(@Param("ownerId") String ownerId);

    /** {@code pg_advisory_xact_lock(int, int)} namespace for {@link #acquireOwnerQuotaLock} ("FSOQ"). */
    int QUOTA_LOCK_NAMESPACE = 0x46534F51;

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
