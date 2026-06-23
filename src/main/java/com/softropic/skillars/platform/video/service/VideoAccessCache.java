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
 * canPlay() populates both the parent decision and the loaded video entity so the controller
 * can reuse them without a second DB round-trip.
 */
@Component
@RequestScope
public class VideoAccessCache {

    private final Map<UUID, Boolean> parentDecisions = new HashMap<>();
    private final Map<UUID, Video> videoCache = new HashMap<>();

    public void setParentDecision(UUID videoId, boolean isParent) {
        parentDecisions.put(videoId, isParent);
    }

    public Optional<Boolean> getParentDecision(UUID videoId) {
        return Optional.ofNullable(parentDecisions.get(videoId));
    }

    public void setVideo(UUID videoId, Video video) {
        videoCache.put(videoId, video);
    }

    public Optional<Video> getVideo(UUID videoId) {
        return Optional.ofNullable(videoCache.get(videoId));
    }
}
