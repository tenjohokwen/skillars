package com.softropic.skillars.platform.video.repo;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.UUID;

public interface PlaybackTokenRepository extends JpaRepository<PlaybackToken, UUID> {

    @Modifying
    @Query("UPDATE PlaybackToken t SET t.revokedAt = :revokedAt " +
           "WHERE t.viewerId = :viewerId AND t.revokedAt IS NULL AND t.expiresAt > :now")
    int revokeActiveTokensForViewer(@Param("viewerId") String viewerId,
                                    @Param("revokedAt") Instant revokedAt,
                                    @Param("now") Instant now);

    @Query("SELECT COUNT(t) > 0 FROM PlaybackToken t " +
           "WHERE t.viewerId = :viewerId AND t.revokedAt IS NOT NULL AND t.revokedAt > :windowStart")
    boolean hasRecentRevocation(@Param("viewerId") String viewerId,
                                @Param("windowStart") Instant windowStart);
}
