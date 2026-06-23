package com.softropic.skillars.platform.video.repo;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.UUID;

public interface SubscriptionLifecycleOutboxRepository extends JpaRepository<SubscriptionLifecycleOutbox, UUID> {

    @Query(value = """
        SELECT * FROM main.subscription_lifecycle_outbox
        WHERE status = 'PENDING'
        ORDER BY created_at ASC
        FOR UPDATE SKIP LOCKED
        """, nativeQuery = true)
    List<SubscriptionLifecycleOutbox> findPendingForProcessing();
}
