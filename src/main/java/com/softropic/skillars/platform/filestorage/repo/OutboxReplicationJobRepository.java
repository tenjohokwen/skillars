package com.softropic.skillars.platform.filestorage.repo;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

public interface OutboxReplicationJobRepository extends JpaRepository<OutboxReplicationJob, Long> {

    @Transactional
    @Query(value = "SELECT * FROM main.outbox_replication_jobs WHERE status = 'PENDING' ORDER BY created_at ASC LIMIT :limit FOR UPDATE SKIP LOCKED",
           nativeQuery = true)
    List<OutboxReplicationJob> pollPending(@Param("limit") int limit);

    @Modifying(clearAutomatically = true)
    @Transactional
    @Query("UPDATE OutboxReplicationJob j SET " +
           "j.status = com.softropic.skillars.platform.filestorage.repo.OutboxReplicationJob.ReplicationJobStatus.PROCESSING, " +
           "j.lastAttemptedAt = :ts WHERE j.id = :id")
    void markAsProcessing(@Param("id") Long id, @Param("ts") Instant ts);

    @Modifying(clearAutomatically = true)
    @Transactional
    @Query("UPDATE OutboxReplicationJob j SET " +
           "j.status = com.softropic.skillars.platform.filestorage.repo.OutboxReplicationJob.ReplicationJobStatus.COMPLETED " +
           "WHERE j.id = :id")
    void markAsCompleted(@Param("id") Long id);

    @Modifying(clearAutomatically = true)
    @Transactional
    @Query("UPDATE OutboxReplicationJob j SET " +
           "j.status = com.softropic.skillars.platform.filestorage.repo.OutboxReplicationJob.ReplicationJobStatus.PENDING, " +
           "j.attemptCount = :attempts, j.lastAttemptedAt = :ts, j.errorMessage = :error WHERE j.id = :id")
    void markAsPendingForRetry(@Param("id") Long id, @Param("attempts") int attempts,
                               @Param("ts") Instant ts, @Param("error") String error);

    @Modifying(clearAutomatically = true)
    @Transactional
    @Query("UPDATE OutboxReplicationJob j SET " +
           "j.status = com.softropic.skillars.platform.filestorage.repo.OutboxReplicationJob.ReplicationJobStatus.FAILED, " +
           "j.attemptCount = :attempts, j.lastAttemptedAt = :ts, j.errorMessage = :error WHERE j.id = :id")
    void markAsFailed(@Param("id") Long id, @Param("attempts") int attempts,
                      @Param("ts") Instant ts, @Param("error") String error);

    @Query("SELECT COUNT(j) FROM OutboxReplicationJob j WHERE j.status = :status")
    long countByStatus(@Param("status") OutboxReplicationJob.ReplicationJobStatus status);
}
