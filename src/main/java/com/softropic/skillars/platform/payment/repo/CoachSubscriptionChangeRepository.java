package com.softropic.skillars.platform.payment.repo;

import org.springframework.data.jpa.repository.JpaRepository;
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
}
