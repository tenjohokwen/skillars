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

    // skillars-deferred-132 AC1 Fix 3: backs SubscriptionService.reconcileMarketplaceTiers' full scan
    // of every coach subscription still eligible to have a marketplace-visible tier. Pass the same
    // List.of("ACTIVE", "TRIALLING") status set countActiveByTier's own JPQL literal above encodes, so
    // the two sets are defined side by side rather than drifting apart unnoticed — mirrors this
    // codebase's established literal-pin convention (see ConfigBoundsEnumCoverageTest's own javadoc)
    // rather than introducing a shared constant for a two-string list.
    List<PaymentCoachSubscription> findAllByStatusIn(List<String> statuses);
}
