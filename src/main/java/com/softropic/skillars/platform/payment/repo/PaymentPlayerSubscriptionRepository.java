package com.softropic.skillars.platform.payment.repo;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PaymentPlayerSubscriptionRepository extends JpaRepository<PaymentPlayerSubscription, UUID> {

    Optional<PaymentPlayerSubscription> findByPlayerId(Long playerId);

    Optional<PaymentPlayerSubscription> findByStripeSubscriptionId(String stripeSubscriptionId);

    List<PaymentPlayerSubscription> findByStatusAndPastDueSinceBefore(String status, Instant cutoff);

    @Query("SELECT p.tier, COUNT(p) FROM PaymentPlayerSubscription p WHERE p.status IN ('ACTIVE', 'TRIALLING') GROUP BY p.tier")
    List<Object[]> countActiveByTier();
}
