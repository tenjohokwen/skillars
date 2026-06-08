package com.softropic.skillars.platform.video.repo;

import com.softropic.skillars.platform.video.contract.VideoWebhookStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface VideoWebhookEventRepository extends JpaRepository<VideoWebhookEvent, UUID> {

    boolean existsByEventId(String eventId);

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
