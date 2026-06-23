package com.softropic.skillars.platform.video.api;

import com.softropic.skillars.platform.security.service.SecurityUtil;
import com.softropic.skillars.platform.video.contract.PlaybackAuthorizationResponse;
import com.softropic.skillars.platform.video.contract.PlaybackResponse;
import com.softropic.skillars.platform.video.repo.Video;
import com.softropic.skillars.platform.video.service.PlaybackService;
import com.softropic.skillars.platform.video.service.VideoAccessCache;
import com.softropic.skillars.platform.video.service.VideoAccessGuard;
import com.softropic.skillars.platform.video.service.VideoService;
import io.micrometer.observation.annotation.Observed;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@Slf4j
@Observed(name = "video.playback")
@RestController
@RequestMapping("/api/video")
@RequiredArgsConstructor
public class VideoPlayResource {

    private final PlaybackService playbackService;
    private final VideoService videoService;
    private final SecurityUtil securityUtil;
    private final VideoAccessGuard videoAccessGuard;
    private final VideoAccessCache videoAccessCache;

    @GetMapping("/{id}/play")
    @PreAuthorize("@videoAccessGuard.canPlay(authentication, #id)")
    public ResponseEntity<PlaybackResponse> play(@PathVariable UUID id, HttpServletRequest request) {
        String currentUser = securityUtil.getCurrentUserName();
        Video video = videoAccessCache.getVideo(id).orElseGet(() -> videoService.findById(id));

        // Determine parent access for HIDDEN bypass (AC 2 — parental approval path)
        boolean skipHiddenCheck = videoAccessGuard.isParentOf(currentUser, video.getOwnerId(), id);

        PlaybackAuthorizationResponse auth =
            playbackService.authorizePlayback(id, currentUser, extractClientIp(request), skipHiddenCheck);

        return ResponseEntity.ok(new PlaybackResponse(auth.playbackUrl(), auth.expiresAt(), auth.downloadUrl()));
    }

    /**
     * Extracts the real client IP from X-Forwarded-For.
     * The platform runs behind a single trusted reverse proxy that APPENDS to XFF —
     * so the LAST entry is the IP the proxy observed (the actual client IP).
     * DO NOT take the first entry — it is client-supplied and trivially spoofable.
     * See deployment runbook for proxy topology requirements.
     */
    private String extractClientIp(HttpServletRequest request) {
        String xff = request.getHeader("X-Forwarded-For");
        if (StringUtils.hasText(xff)) {
            String[] parts = xff.split(",");
            return parts[parts.length - 1].trim();
        }
        return request.getRemoteAddr();
    }
}
