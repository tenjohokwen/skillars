package com.softropic.skillars.platform.payment.repo;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PlayerSubscriptionChangeRepository extends JpaRepository<PlayerSubscriptionChange, UUID> {

    @Query(value = """
        SELECT * FROM payment.player_subscription_changes
        WHERE applied = false
          AND voided_at IS NULL
          AND effective_at <= :cutoff
        ORDER BY effective_at ASC
        FOR UPDATE SKIP LOCKED
        """, nativeQuery = true)
    List<PlayerSubscriptionChange> findPendingForScheduler(@Param("cutoff") Instant cutoff);

    @Query(value = """
        SELECT * FROM payment.player_subscription_changes
        WHERE player_id = :playerId
          AND applied = false
          AND voided_at IS NULL
        ORDER BY effective_at DESC
        LIMIT 1
        FOR UPDATE SKIP LOCKED
        """, nativeQuery = true)
    Optional<PlayerSubscriptionChange> findPendingForPlayer(@Param("playerId") Long playerId);

    boolean existsByPlayerIdAndAppliedFalseAndVoidedAtIsNull(Long playerId);

    // fix/payment-subscription-entitlements: voids EVERY pending change (findPendingForPlayer only ever
    // returned one, and its SKIP LOCKED silently missed a row the applicator was holding). Callers
    // lock the owning subscription row first, matching the applicator's subscription -> change order.
    @Modifying
    @Query("UPDATE PlayerSubscriptionChange c SET c.applied = true, c.voidedAt = :now "
        + "WHERE c.playerId = :playerId AND c.applied = false AND c.voidedAt IS NULL")
    int voidAllPendingForPlayer(@Param("playerId") Long playerId, @Param("now") Instant now);
}
