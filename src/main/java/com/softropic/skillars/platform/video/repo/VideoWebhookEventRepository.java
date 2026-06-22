package com.softropic.skillars.platform.video.repo;

import com.softropic.skillars.platform.video.contract.VideoWebhookStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

public interface VideoWebhookEventRepository extends JpaRepository<VideoWebhookEvent, UUID> {

    boolean existsByEventId(String eventId);

    @Modifying
    @Transactional
    @Query(value = """
        INSERT INTO main.video_webhook_events
            (id, event_id, event_type, provider_asset_id, raw_payload, status)
        VALUES
            (gen_random_uuid(), :eventId, :eventType, :providerAssetId, :rawPayload, :status)
        ON CONFLICT (event_id) DO NOTHING
        """, nativeQuery = true)
    int insertIfAbsent(
        @Param("eventId") String eventId,
        @Param("eventType") String eventType,
        @Param("providerAssetId") String providerAssetId,
        @Param("rawPayload") String rawPayload,
        @Param("status") String status
    );

    long countByStatus(VideoWebhookStatus status);

    @Query(value = """
        SELECT * FROM main.video_webhook_events
        WHERE status = 'PENDING'
        ORDER BY created_at ASC
        LIMIT :limit
        FOR UPDATE SKIP LOCKED
        """, nativeQuery = true)
    List<VideoWebhookEvent> findPendingForUpdate(@Param("limit") int limit);
}
