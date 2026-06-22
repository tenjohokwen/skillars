package com.softropic.skillars.platform.video.repo;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface VideoModerationScanRepository extends JpaRepository<VideoModerationScan, UUID> {
    List<VideoModerationScan> findByVideoId(UUID videoId);
    Optional<VideoModerationScan> findByVideoIdAndLayer(UUID videoId, String layer);

    @Transactional
    @Modifying
    @Query(value = "INSERT INTO main.video_moderation_scans (video_id, layer, outcome, confidence, details) " +
                   "VALUES (:videoId, :layer, :outcome, :confidence, :details) " +
                   "ON CONFLICT (video_id, layer) DO UPDATE SET " +
                   "outcome = EXCLUDED.outcome, confidence = EXCLUDED.confidence, details = EXCLUDED.details",
           nativeQuery = true)
    void upsertScan(@Param("videoId") UUID videoId, @Param("layer") String layer,
                    @Param("outcome") String outcome, @Param("confidence") Double confidence,
                    @Param("details") String details);
}
