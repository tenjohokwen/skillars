package com.softropic.skillars.platform.video.repo;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface VideoRepository extends JpaRepository<Video, UUID> {

    Optional<Video> findByProviderAssetId(String providerAssetId);

    @Query(value = """
        SELECT * FROM main.videos
        WHERE operational_state IN ('UPLOADING', 'PROCESSING')
        ORDER BY updated_at ASC
        LIMIT :limit
        FOR UPDATE SKIP LOCKED
        """, nativeQuery = true)
    List<Video> findNonTerminalForUpdate(@Param("limit") int limit);
}
