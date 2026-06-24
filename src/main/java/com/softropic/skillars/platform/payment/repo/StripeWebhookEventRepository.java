package com.softropic.skillars.platform.payment.repo;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface StripeWebhookEventRepository extends JpaRepository<StripeWebhookEvent, String> {

    /**
     * Atomically inserts an idempotency record. Returns 1 if inserted, 0 if the event_id already exists.
     * Using ON CONFLICT DO NOTHING avoids the check-then-insert race condition under concurrent Stripe retries.
     */
    @Modifying
    @Query(value = "INSERT INTO payment.stripe_webhook_events(event_id, event_type, processed_at) " +
                   "VALUES (:eventId, :eventType, now()) ON CONFLICT (event_id) DO NOTHING",
           nativeQuery = true)
    int insertIfAbsent(@Param("eventId") String eventId, @Param("eventType") String eventType);
}
