package com.softropic.skillars.platform.video.api;

import com.softropic.skillars.infrastructure.security.SecurityConstants;
import com.softropic.skillars.infrastructure.security.SecurityError;
import com.softropic.skillars.platform.security.contract.exception.OperationNotAllowedException;
import com.softropic.skillars.platform.video.contract.AccessState;
import com.softropic.skillars.platform.video.contract.OperationalState;
import com.softropic.skillars.platform.video.repo.Video;
import com.softropic.skillars.platform.video.service.VideoAccessGuard;
import com.softropic.skillars.platform.video.service.VideoService;
import com.softropic.skillars.platform.video.service.VideoSseService;
import io.micrometer.observation.annotation.Observed;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.UUID;

@Observed(name = "video.events")
@RestController
@RequestMapping("/api/video")
@RequiredArgsConstructor
public class VideoEventResource {

    private final VideoService videoService;
    private final VideoSseService videoSseService;
    private final VideoAccessGuard videoAccessGuard;

    @GetMapping("/{id}/events")
    @PreAuthorize(SecurityConstants.IS_AUTHENTICATED)
    public ResponseEntity<SseEmitter> subscribeToEvents(@PathVariable UUID id) {
        Video video = findAndVerifyOwnership(id);
        SseEmitter emitter = videoSseService.subscribe(id, video.getOperationalState().name());
        return ResponseEntity.ok()
            .contentType(MediaType.TEXT_EVENT_STREAM)
            .body(emitter);
    }

    @GetMapping("/{id}/status")
    @PreAuthorize(SecurityConstants.IS_AUTHENTICATED)
    public ResponseEntity<VideoStatusResponse> getStatus(@PathVariable UUID id) {
        Video video = findAndVerifyOwnership(id);
        String displayState = computeDisplayState(video);
        return ResponseEntity.ok(new VideoStatusResponse(id, video.getOperationalState().name(), displayState));
    }

    private String computeDisplayState(Video video) {
        if (video.getAccessState() == AccessState.BLOCKED)  return "SUBSCRIPTION_LOCKED";
        if (video.getAccessState() == AccessState.ARCHIVED) return "ARCHIVED";
        // PURGED is an internal pre-physical-deletion state (see VideoDeletionService); the SSE
        // stream never surfaces it as-is (VideoSseService.onVideoPurged translates it to DELETED
        // before pushing), so the polling fallback must apply the same translation for parity.
        if (video.getOperationalState() == OperationalState.PURGED) return OperationalState.DELETED.name();
        return video.getOperationalState().name();
    }

    private Video findAndVerifyOwnership(UUID videoId) {
        Video video = videoService.findById(videoId);
        // VideoAccessGuard owns the one owner check (coach-profile UUID or PlayerProfile id resolved
        // from the caller's user id). The old local copy compared a coach video's UUID ownerId to the
        // username (an email), so a coach got 403 on /events and /status for their own upload.
        if (!videoAccessGuard.isCurrentUserOwner(video)) {
            throw new OperationNotAllowedException(
                "Not the owner of this video", SecurityError.MISSING_RIGHTS);
        }
        return video;
    }

    public record VideoStatusResponse(UUID videoId, String operationalState, String displayState) {}
}
