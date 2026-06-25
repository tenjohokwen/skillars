package com.softropic.skillars.platform.payment.repo;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SessionPackPurchaseRepository extends JpaRepository<SessionPackPurchase, UUID> {

    @Transactional
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT p FROM SessionPackPurchase p WHERE p.purchaseId = :id")
    Optional<SessionPackPurchase> findByIdForUpdate(@Param("id") UUID id);

    List<SessionPackPurchase> findByCoachIdAndExpiresAtBetweenAndExtendedAtIsNullAndRemainingSessionsGreaterThan(
        UUID coachId, Instant from, Instant to, int minSessions);

    @Query("SELECT p FROM SessionPackPurchase p WHERE p.expiresAt BETWEEN :from AND :to AND p.extendedAt IS NULL AND p.remainingSessions > 0")
    List<SessionPackPurchase> findExpiringWithinWindowAndSessionsRemaining(@Param("from") Instant from, @Param("to") Instant to);
}
