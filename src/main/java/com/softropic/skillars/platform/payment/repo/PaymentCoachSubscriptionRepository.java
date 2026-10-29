package com.softropic.skillars.platform.payment.repo;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PaymentCoachSubscriptionRepository extends JpaRepository<PaymentCoachSubscription, UUID> {

    Optional<PaymentCoachSubscription> findByCoachId(UUID coachId);

    Optional<PaymentCoachSubscription> findByStripeSubscriptionId(String stripeSubscriptionId);

    // fix/payment-subscription-payment-method-sync: the grace sweep covers every delinquent status
    // (PAST_DUE, UNPAID, PAUSED), not just PAST_DUE.
    List<PaymentCoachSubscription> findByStatusInAndPastDueSinceBefore(Collection<String> statuses, Instant cutoff);

    /** Delinquent rows whose grace clock never started (pastDueSince is only stamped on entry). */
    List<PaymentCoachSubscription> findByStatusInAndPastDueSinceIsNull(Collection<String> statuses);

    @Query("SELECT p.tier, COUNT(p) FROM PaymentCoachSubscription p WHERE p.status IN ('ACTIVE', 'TRIALLING') GROUP BY p.tier")
    List<Object[]> countActiveByTier();

    // skillars-deferred-132 AC1 Fix 3: backs SubscriptionService.reconcileMarketplaceTiers' full scan
    // of every coach subscription still eligible to have a marketplace-visible tier. Pass the same
    // List.of("ACTIVE", "TRIALLING") status set countActiveByTier's own JPQL literal above encodes, so
    // the two sets are defined side by side rather than drifting apart unnoticed — mirrors this
    // codebase's established literal-pin convention (see ConfigBoundsEnumCoverageTest's own javadoc)
    // rather than introducing a shared constant for a two-string list.
    List<PaymentCoachSubscription> findAllByStatusIn(List<String> statuses);

    // fix/payment-subscription-entitlements: every persist path (API, webhook, scheduler) re-reads the
    // row under this lock inside its own transaction instead of saving a copy loaded before the Stripe
    // call, so concurrent writers serialize rather than overwrite each other (@Version is the backstop).
    // Blocking FOR UPDATE, not NOWAIT: holders never call Stripe while holding it, so waits are short.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT s FROM PaymentCoachSubscription s WHERE s.coachId = :coachId")
    Optional<PaymentCoachSubscription> findByCoachIdForUpdate(@Param("coachId") UUID coachId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT s FROM PaymentCoachSubscription s WHERE s.stripeSubscriptionId = :stripeSubscriptionId")
    Optional<PaymentCoachSubscription> findByStripeSubscriptionIdForUpdate(@Param("stripeSubscriptionId") String stripeSubscriptionId);
}
