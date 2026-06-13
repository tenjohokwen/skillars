package com.softropic.skillars.platform.booking.repo;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

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
        ORDER BY s.purchasedAt ASC, s.id ASC
        """)
    List<SessionPackPurchased> findActivePacksForDeduction(
        @Param("playerId") Long playerId,
        @Param("coachId") UUID coachId
    );

    @Query("""
        SELECT COALESCE(SUM(s.creditsRemaining), 0) FROM SessionPackPurchased s
        WHERE s.playerId = :playerId AND s.coachId = :coachId AND s.status = 'ACTIVE'
        """)
    int sumActiveCredits(@Param("playerId") Long playerId, @Param("coachId") UUID coachId);

    @Query("""
        SELECT COUNT(s) > 0 FROM SessionPackPurchased s
        WHERE s.playerId = :playerId AND s.coachId = :coachId
          AND s.status = 'ACTIVE' AND s.creditsRemaining > 0
        """)
    boolean hasActiveCredits(@Param("playerId") Long playerId, @Param("coachId") UUID coachId);
}
