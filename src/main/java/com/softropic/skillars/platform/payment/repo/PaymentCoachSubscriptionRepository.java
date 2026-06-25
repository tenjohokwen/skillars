package com.softropic.skillars.platform.payment.repo;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PaymentCoachSubscriptionRepository extends JpaRepository<PaymentCoachSubscription, UUID> {

    Optional<PaymentCoachSubscription> findByCoachId(UUID coachId);

    Optional<PaymentCoachSubscription> findByStripeSubscriptionId(String stripeSubscriptionId);

    List<PaymentCoachSubscription> findByStatusAndPastDueSinceBefore(String status, Instant cutoff);
}
