package com.softropic.skillars.platform.booking.repo;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface SessionPackPurchasedRepository extends JpaRepository<SessionPackPurchased, UUID> {

    List<SessionPackPurchased> findByParentIdAndPlayerId(Long parentId, Long playerId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
        SELECT s FROM SessionPackPurchased s
        WHERE s.playerId = :playerId
          AND s.coachId = :coachId
          AND s.status = 'ACTIVE'
          AND s.creditsRemaining > 0
          AND (s.pausedUntil IS NULL OR s.pausedUntil <= :now)
        ORDER BY s.purchasedAt ASC, s.id ASC
        """)
    List<SessionPackPurchased> findActivePacksForDeduction(
        @Param("playerId") Long playerId,
        @Param("coachId") UUID coachId,
        @Param("now") Instant now
    );

    @Query("""
        SELECT COALESCE(SUM(s.creditsRemaining), 0) FROM SessionPackPurchased s
        WHERE s.playerId = :playerId AND s.coachId = :coachId AND s.status = 'ACTIVE'
          AND (s.pausedUntil IS NULL OR s.pausedUntil <= :now)
        """)
    int sumActiveCredits(@Param("playerId") Long playerId, @Param("coachId") UUID coachId,
                         @Param("now") Instant now);

    @Query("""
        SELECT COUNT(s) > 0 FROM SessionPackPurchased s
        WHERE s.playerId = :playerId AND s.coachId = :coachId
          AND s.status = 'ACTIVE' AND s.creditsRemaining > 0
          AND (s.pausedUntil IS NULL OR s.pausedUntil <= :now)
        """)
    boolean hasActiveCredits(@Param("playerId") Long playerId, @Param("coachId") UUID coachId,
                             @Param("now") Instant now);

    @Query("""
        SELECT s FROM SessionPackPurchased s
        WHERE s.status = 'ACTIVE' AND s.expiresAt <= :now AND s.creditsRemaining > 0
        """)
    List<SessionPackPurchased> findExpiredActivePacks(@Param("now") Instant now);

    @Query("""
        SELECT s FROM SessionPackPurchased s
        WHERE s.status = 'ACTIVE' AND s.expiresAt > :threshold7d AND s.expiresAt <= :threshold30d AND s.warning30dSentAt IS NULL
        """)
    List<SessionPackPurchased> findPacksNeedingWarning30d(@Param("threshold7d") Instant threshold7d, @Param("threshold30d") Instant threshold30d);

    @Query("""
        SELECT s FROM SessionPackPurchased s
        WHERE s.status = 'ACTIVE' AND s.expiresAt > :now AND s.expiresAt <= :threshold7d AND s.warning7dSentAt IS NULL
        """)
    List<SessionPackPurchased> findPacksNeedingWarning7d(@Param("now") Instant now, @Param("threshold7d") Instant threshold7d);

    @Query("""
        SELECT s FROM SessionPackPurchased s
        WHERE s.playerId = :playerId AND s.coachId = :coachId
          AND s.status = 'ACTIVE' AND s.creditsRemaining > 0
          AND (s.pausedUntil IS NULL OR s.pausedUntil <= :now)
        ORDER BY s.purchasedAt ASC
        """)
    List<SessionPackPurchased> findActivePacks(@Param("playerId") Long playerId,
                                               @Param("coachId") UUID coachId,
                                               @Param("now") Instant now);

    java.util.Optional<SessionPackPurchased> findTopByPlayerIdAndCoachIdOrderByPurchasedAtDesc(Long playerId, UUID coachId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT s FROM SessionPackPurchased s WHERE s.id = :id")
    java.util.Optional<SessionPackPurchased> findByIdForUpdate(@Param("id") UUID id);
}
