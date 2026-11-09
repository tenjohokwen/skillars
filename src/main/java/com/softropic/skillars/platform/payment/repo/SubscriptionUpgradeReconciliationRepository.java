package com.softropic.skillars.platform.payment.repo;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;

public interface SubscriptionUpgradeReconciliationRepository extends JpaRepository<SubscriptionUpgradeReconciliation, Long> {

    /**
     * skillars-deferred-175 AC3: an open row (no {@code resolved_at}) already records this invoice for this subscription's target
     * tier. A null invoice id matches a null one (derived queries bind null as IS NULL).
     */
    boolean existsByStripeSubscriptionIdAndTargetTierAndLatestInvoiceIdAndResolvedAtIsNull(String stripeSubscriptionId,
                                                                                          String targetTier, String latestInvoiceId);

    /** skillars-deferred-176 AC5 (RS07c): resolves every open row for this subscription's target tier, once it is granted. */
    @Modifying
    @Query("UPDATE SubscriptionUpgradeReconciliation r SET r.resolvedAt = :now "
        + "WHERE r.stripeSubscriptionId = :stripeSubscriptionId AND r.targetTier = :targetTier AND r.resolvedAt IS NULL")
    int resolveOpen(@Param("stripeSubscriptionId") String stripeSubscriptionId, @Param("targetTier") String targetTier,
                    @Param("now") Instant now);
}
