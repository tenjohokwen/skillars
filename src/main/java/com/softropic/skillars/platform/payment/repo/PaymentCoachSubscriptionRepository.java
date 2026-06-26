package com.softropic.skillars.platform.payment.repo;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PaymentCoachSubscriptionRepository extends JpaRepository<PaymentCoachSubscription, UUID> {

    Optional<PaymentCoachSubscription> findByCoachId(UUID coachId);

    Optional<PaymentCoachSubscription> findByStripeSubscriptionId(String stripeSubscriptionId);

    List<PaymentCoachSubscription> findByStatusAndPastDueSinceBefore(String status, Instant cutoff);

    @Query("SELECT p.tier, COUNT(p) FROM PaymentCoachSubscription p WHERE p.status IN ('ACTIVE', 'TRIALLING') GROUP BY p.tier")
    List<Object[]> countActiveByTier();
}
