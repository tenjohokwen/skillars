package com.softropic.skillars.platform.video.repo;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface VideoDeletionOutboxRepository extends JpaRepository<VideoDeletionOutbox, UUID> {

    // Phase 1: atomically claim a batch by updating status to CLAIMED within a single transaction.
    // FOR UPDATE SKIP LOCKED in the subquery prevents concurrent processors from double-claiming.
    @Modifying
    @Transactional
    @Query(value = """
        UPDATE main.video_deletion_outbox
        SET status = 'CLAIMED'
        WHERE id = ANY(
            SELECT id FROM main.video_deletion_outbox
            WHERE status = 'PENDING' AND next_retry_at <= :now
            ORDER BY next_retry_at ASC
            LIMIT :batchSize
            FOR UPDATE SKIP LOCKED
        )
        """, nativeQuery = true)
    int claimPendingBatch(@Param("now") Instant now, @Param("batchSize") int batchSize);

    // Phase 2: fetch the rows this processor just claimed.
    @Query(value = """
        SELECT * FROM main.video_deletion_outbox
        WHERE status = 'CLAIMED'
        ORDER BY next_retry_at ASC
        """, nativeQuery = true)
    List<VideoDeletionOutbox> findClaimedBatch();

    // Recover rows stuck in CLAIMED state from a prior crashed run (next_retry_at older than deadline).
    @Modifying
    @Transactional
    @Query(value = """
        UPDATE main.video_deletion_outbox
        SET status = 'PENDING'
        WHERE status = 'CLAIMED' AND next_retry_at < :deadline
        """, nativeQuery = true)
    int resetStaleClaimed(@Param("deadline") Instant deadline);

    boolean existsByVideoIdAndStatus(UUID videoId, String status);
}
