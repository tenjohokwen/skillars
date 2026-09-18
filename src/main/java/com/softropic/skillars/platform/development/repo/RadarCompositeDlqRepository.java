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
    // skillars-deferred-123 AC3: claimed_at stamped/scoped/keyed identically — see
    // VideoDeletionOutboxRepository's own comments on each of the three queries below for the
    // full rationale (RadarCompositeDlqEntry.claimedAt's Javadoc points back here too).
    @Modifying
    @Transactional
    @Query(value = """
        UPDATE development.radar_composite_dlq
        SET status = 'CLAIMED', claimed_at = :now
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
        WHERE status = 'CLAIMED' AND claimed_at = :claimedAt
        ORDER BY next_retry_at ASC
        LIMIT :batchSize
        """, nativeQuery = true)
    List<RadarCompositeDlqEntry> findClaimedBatch(@Param("claimedAt") Instant claimedAt, @Param("batchSize") int batchSize);

    // skillars-deferred-123 code review 2026-09-18 (Patch): matches claimed_at IS NULL too, not just
    // claimed_at IS NOT NULL — see VideoDeletionOutboxRepository.resetStaleClaimed's comment for the
    // full rolling-deploy-stranding rationale. Also clears claimed_at back to NULL on this transition,
    // matching the field's documented invariant.
    @Modifying
    @Transactional
    @Query(value = """
        UPDATE development.radar_composite_dlq
        SET status = 'PENDING', claimed_at = NULL
        WHERE status = 'CLAIMED' AND (claimed_at IS NULL OR claimed_at < :deadline)
        """, nativeQuery = true)
    int resetStaleClaimed(@Param("deadline") Instant deadline);

    // skillars-deferred-123 code review 2026-09-18 (Decision 3): mirrors
    // VideoDeletionOutboxRepository.releaseClaimed — see that method's comment for the full rationale.
    @Modifying
    @Transactional
    @Query(value = """
        UPDATE development.radar_composite_dlq
        SET status = 'PENDING', claimed_at = NULL
        WHERE status = 'CLAIMED' AND claimed_at = :claimedAt
        """, nativeQuery = true)
    int releaseClaimed(@Param("claimedAt") Instant claimedAt);

    // skillars-deferred-123 code review 2026-09-18 (Decision 5): mirrors
    // VideoDeletionOutboxRepository.completeClaimed/failClaimed — see those methods' comment for the
    // full rationale (detached-entity merge, no @Version on this entity either, lost updates in both
    // directions). Note this class's own header comment claims "@SchedulerLock below closes this by
    // preventing the concurrent invocation in the first place"; that only holds while the lock is
    // held, and lockAtMostFor expiry is the documented end of that guarantee, so these predicates are
    // what actually close it.
    @Modifying
    @Transactional
    @Query(value = """
        UPDATE development.radar_composite_dlq
        SET status = 'COMPLETED', claimed_at = NULL
        WHERE id = :id AND status = 'CLAIMED' AND claimed_at = :claimedAt
        """, nativeQuery = true)
    int completeClaimed(@Param("id") UUID id, @Param("claimedAt") Instant claimedAt);

    @Modifying
    @Transactional
    @Query(value = """
        UPDATE development.radar_composite_dlq
        SET status = :status, attempts = :attempts, last_error = :lastError,
            next_retry_at = :nextRetryAt, claimed_at = NULL
        WHERE id = :id AND status = 'CLAIMED' AND claimed_at = :claimedAt
        """, nativeQuery = true)
    int failClaimed(@Param("id") UUID id, @Param("claimedAt") Instant claimedAt,
                    @Param("status") String status, @Param("attempts") int attempts,
                    @Param("lastError") String lastError, @Param("nextRetryAt") Instant nextRetryAt);
}
