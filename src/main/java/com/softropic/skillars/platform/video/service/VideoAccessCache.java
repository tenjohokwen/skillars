package com.softropic.skillars.platform.video.service;

import com.softropic.skillars.platform.video.repo.Video;
import org.springframework.stereotype.Component;
import org.springframework.web.context.annotation.RequestScope;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Request-scoped cache for guard-evaluated data.
 * canPlay() populates the owner decision and the loaded video entity so the controller can reuse
 * them without a second DB round-trip.
 */
@Component
@RequestScope
public class VideoAccessCache {

    private final Map<UUID, Boolean> ownerDecisions = new HashMap<>();
    private final Map<UUID, Video> videoCache = new HashMap<>();

    public void setOwnerDecision(UUID videoId, boolean isOwner) {
        ownerDecisions.put(videoId, isOwner);
    }

    public Optional<Boolean> getOwnerDecision(UUID videoId) {
        return Optional.ofNullable(ownerDecisions.get(videoId));
    }

    public void setVideo(UUID videoId, Video video) {
        videoCache.put(videoId, video);
    }

    public Optional<Video> getVideo(UUID videoId) {
        return Optional.ofNullable(videoCache.get(videoId));
    }
}
