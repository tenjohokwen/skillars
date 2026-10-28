package com.softropic.skillars.platform.payment.repo;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

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

    // fix/payment-subscription-entitlements: every persist path (API, webhook, scheduler) re-reads the
    // row under this lock inside its own transaction instead of saving a copy loaded before the Stripe
    // call, so concurrent writers serialize rather than overwrite each other (@Version is the backstop).
    // Blocking FOR UPDATE, not NOWAIT: holders never call Stripe while holding it, so waits are short.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT s FROM PaymentPlayerSubscription s WHERE s.playerId = :playerId")
    Optional<PaymentPlayerSubscription> findByPlayerIdForUpdate(@Param("playerId") Long playerId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT s FROM PaymentPlayerSubscription s WHERE s.stripeSubscriptionId = :stripeSubscriptionId")
    Optional<PaymentPlayerSubscription> findByStripeSubscriptionIdForUpdate(@Param("stripeSubscriptionId") String stripeSubscriptionId);
}
