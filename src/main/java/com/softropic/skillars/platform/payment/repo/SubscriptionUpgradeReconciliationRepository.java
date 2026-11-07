package com.softropic.skillars.platform.payment.repo;

import org.springframework.data.jpa.repository.JpaRepository;

public interface SubscriptionUpgradeReconciliationRepository extends JpaRepository<SubscriptionUpgradeReconciliation, Long> {

    /** skillars-deferred-175 AC3: an open row (no {@code resolved_at}) already records this subscription's target tier. */
    boolean existsByStripeSubscriptionIdAndTargetTierAndResolvedAtIsNull(String stripeSubscriptionId, String targetTier);
}
