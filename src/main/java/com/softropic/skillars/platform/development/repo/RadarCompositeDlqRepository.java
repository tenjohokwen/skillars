package com.softropic.skillars.platform.development.repo;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface RadarCompositeDlqRepository extends JpaRepository<RadarCompositeDlqEntry, UUID> {

    // Mirrors VideoDeletionOutboxRepository's claim/reset shape (skillars-deferred-77 AC10 Phase 2).
    @Modifying
    @Transactional
    @Query(value = """
        UPDATE development.radar_composite_dlq
        SET status = 'CLAIMED'
        WHERE id = ANY(
            SELECT id FROM development.radar_composite_dlq
            WHERE status = 'PENDING' AND next_retry_at <= :now
            ORDER BY next_retry_at ASC
            LIMIT :batchSize
            FOR UPDATE SKIP LOCKED
        )
        """, nativeQuery = true)
    int claimPendingBatch(@Param("now") Instant now, @Param("batchSize") int batchSize);

    @Query(value = """
        SELECT * FROM development.radar_composite_dlq
        WHERE status = 'CLAIMED'
        ORDER BY next_retry_at ASC
        """, nativeQuery = true)
    List<RadarCompositeDlqEntry> findClaimedBatch();

    @Modifying
    @Transactional
    @Query(value = """
        UPDATE development.radar_composite_dlq
        SET status = 'PENDING'
        WHERE status = 'CLAIMED' AND next_retry_at < :deadline
        """, nativeQuery = true)
    int resetStaleClaimed(@Param("deadline") Instant deadline);
}
