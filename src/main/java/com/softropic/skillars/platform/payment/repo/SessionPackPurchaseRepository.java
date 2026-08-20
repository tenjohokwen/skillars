package com.softropic.skillars.platform.payment.repo;

import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SessionPackPurchaseRepository extends JpaRepository<SessionPackPurchase, UUID> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "5000"))
    @Query("SELECT p FROM SessionPackPurchase p WHERE p.purchaseId = :id")
    Optional<SessionPackPurchase> findByIdForUpdate(@Param("id") UUID id);

    // expiryWarnedAt IS NULL (Deferred-15 AC6): the notifier runs daily over a 14-day window, so
    // without this predicate one pack is re-selected on up to 14 consecutive mornings. Mirrors
    // findExpiredNotYetNotified below.
    @Query("SELECT p FROM SessionPackPurchase p WHERE p.expiresAt BETWEEN :from AND :to AND p.extendedAt IS NULL AND p.remainingSessions > 0 AND p.expiryWarnedAt IS NULL")
    List<SessionPackPurchase> findExpiringWithinWindowAndSessionsRemaining(@Param("from") Instant from, @Param("to") Instant to);

    List<SessionPackPurchase> findByParentIdOrderByCreatedAtDesc(Long parentId);

    List<SessionPackPurchase> findByParentIdAndCoachIdOrderByCreatedAtDesc(Long parentId, UUID coachId);

    @Query("SELECT p FROM SessionPackPurchase p WHERE p.expiresAt < :now AND p.expiredNotifiedAt IS NULL AND p.remainingSessions > 0")
    List<SessionPackPurchase> findExpiredNotYetNotified(@Param("now") Instant now);

    @Query("""
        SELECT p FROM SessionPackPurchase p
        WHERE p.playerId = :playerId AND p.coachId = :coachId
          AND p.remainingSessions > 0 AND p.expiresAt > :now
          AND (p.pausedUntil IS NULL OR p.pausedUntil <= :now)
        ORDER BY p.createdAt ASC
        """)
    List<SessionPackPurchase> findActivePacks(@Param("playerId") Long playerId,
                                              @Param("coachId") UUID coachId,
                                              @Param("now") Instant now);

    Optional<SessionPackPurchase> findTopByPlayerIdAndCoachIdOrderByCreatedAtDesc(Long playerId, UUID coachId);
}
