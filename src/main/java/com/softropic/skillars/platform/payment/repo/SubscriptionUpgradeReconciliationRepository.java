package com.softropic.skillars.platform.payment.repo;

import org.springframework.data.jpa.repository.JpaRepository;

public interface SubscriptionUpgradeReconciliationRepository extends JpaRepository<SubscriptionUpgradeReconciliation, Long> {

    /**
     * skillars-deferred-175 AC3: an open row (no {@code resolved_at}) already records this invoice for this subscription's target
     * tier. A null invoice id matches a null one (derived queries bind null as IS NULL).
     */
    boolean existsByStripeSubscriptionIdAndTargetTierAndLatestInvoiceIdAndResolvedAtIsNull(String stripeSubscriptionId,
                                                                                          String targetTier, String latestInvoiceId);
}
