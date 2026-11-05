package com.softropic.skillars.platform.payment.repo;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

public interface StripePendingSubscriptionEventRepository extends JpaRepository<StripePendingSubscriptionEvent, Long> {

    /** ON CONFLICT DO NOTHING on {@code event_id}: an outer rollback after a committed park leads to redelivery and a re-park. */
    @Modifying
    @Transactional
    @Query(value = "INSERT INTO payment.stripe_pending_subscription_events(event_id, stripe_subscription_id, event_type, "
        + "event_created, status, current_period_end, cancel_at_period_end) "
        + "VALUES (:eventId, :subId, :eventType, :eventCreated, :status, :periodEnd, :cancelAtPeriodEnd) "
        + "ON CONFLICT (event_id) DO NOTHING", nativeQuery = true)
    int insertIfAbsent(@Param("eventId") String eventId, @Param("subId") String subId, @Param("eventType") String eventType,
                       @Param("eventCreated") Long eventCreated, @Param("status") String status,
                       @Param("periodEnd") Long periodEnd, @Param("cancelAtPeriodEnd") Boolean cancelAtPeriodEnd);

    /** Oldest {@code event_created} first (null last), then insertion order. */
    @Query("SELECT e FROM StripePendingSubscriptionEvent e WHERE e.stripeSubscriptionId = :subId "
        + "ORDER BY e.eventCreated ASC NULLS LAST, e.id ASC")
    List<StripePendingSubscriptionEvent> findBySubscriptionOrdered(@Param("subId") String subId);

    @Query("SELECT DISTINCT e.stripeSubscriptionId FROM StripePendingSubscriptionEvent e")
    List<String> findDistinctSubscriptionIds();

    /**
     * The claim: a single-row DELETE. Run in the same transaction as the apply, so a failed apply restores the row; of two concurrent
     * claimers the second blocks on the row lock and then sees a count of 0. Each event is therefore applied exactly once.
     */
    @Modifying
    @Transactional
    @Query("DELETE FROM StripePendingSubscriptionEvent e WHERE e.id = :id")
    int claim(@Param("id") Long id);

    @Modifying
    @Transactional
    @Query(value = "DELETE FROM payment.stripe_pending_subscription_events WHERE created_at < :cutoff", nativeQuery = true)
    int purgeOlderThan(@Param("cutoff") Instant cutoff);
}
