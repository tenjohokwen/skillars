package com.softropic.skillars.platform.session.repo;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface DrillVideoRefRepository extends JpaRepository<DrillVideoRef, UUID> {

    Optional<DrillVideoRef> findByDrillId(UUID drillId);

    List<DrillVideoRef> findByDrillIdIn(Collection<UUID> drillIds);

    @Modifying(clearAutomatically = true)
    @Transactional
    @Query("UPDATE DrillVideoRef d SET d.refCount = d.refCount + 1 WHERE d.drillId = :drillId")
    void incrementRefCount(@Param("drillId") UUID drillId);

    @Modifying(clearAutomatically = true)
    @Transactional
    @Query("UPDATE DrillVideoRef d SET d.videoId = :videoId WHERE d.drillId = :drillId")
    void setVideoId(@Param("drillId") UUID drillId, @Param("videoId") UUID videoId);

    @Modifying(clearAutomatically = true)
    @Transactional
    @Query("UPDATE DrillVideoRef d SET d.videoId = null WHERE d.drillId = :drillId")
    void clearVideoId(@Param("drillId") UUID drillId);

    @Query("SELECT CASE WHEN COUNT(d) > 0 THEN true ELSE false END FROM DrillVideoRef d WHERE d.videoId = :videoId")
    boolean existsByVideoId(@Param("videoId") UUID videoId);

    @Modifying(clearAutomatically = true)
    @Transactional
    @Query(value = "INSERT INTO session.drill_video_refs (drill_id, video_id, ref_count) VALUES (:drillId, :videoId, 1) ON CONFLICT (drill_id) DO UPDATE SET video_id = :videoId", nativeQuery = true)
    void upsertVideoId(@Param("drillId") UUID drillId, @Param("videoId") UUID videoId);
}
