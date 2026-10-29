package com.softropic.skillars.platform.payment.repo;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CoachSubscriptionChangeRepository extends JpaRepository<CoachSubscriptionChange, UUID> {

    @Query(value = """
        SELECT * FROM payment.coach_subscription_changes
        WHERE applied = false
          AND voided_at IS NULL
          AND effective_at <= :cutoff
        ORDER BY effective_at ASC
        FOR UPDATE SKIP LOCKED
        """, nativeQuery = true)
    List<CoachSubscriptionChange> findPendingForScheduler(@Param("cutoff") Instant cutoff);

    @Query(value = """
        SELECT * FROM payment.coach_subscription_changes
        WHERE coach_id = :coachId
          AND applied = false
          AND voided_at IS NULL
        ORDER BY effective_at DESC
        LIMIT 1
        FOR UPDATE SKIP LOCKED
        """, nativeQuery = true)
    Optional<CoachSubscriptionChange> findPendingForCoach(@Param("coachId") UUID coachId);

    boolean existsByCoachIdAndAppliedFalseAndVoidedAtIsNull(UUID coachId);

    // fix/payment-subscription-entitlements: voids EVERY pending change (findPendingForCoach only ever
    // returned one, and its SKIP LOCKED silently missed a row the applicator was holding). Callers
    // lock the owning subscription row first, matching the applicator's subscription -> change order.
    @Modifying
    @Query("UPDATE CoachSubscriptionChange c SET c.applied = true, c.voidedAt = :now "
        + "WHERE c.coachId = :coachId AND c.applied = false AND c.voidedAt IS NULL")
    int voidAllPendingForCoach(@Param("coachId") UUID coachId, @Param("now") Instant now);
}
