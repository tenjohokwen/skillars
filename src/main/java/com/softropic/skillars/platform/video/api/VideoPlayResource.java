package com.softropic.skillars.platform.video.api;

import com.softropic.skillars.infrastructure.security.SecurityConstants;
import com.softropic.skillars.infrastructure.security.SecurityError;
import com.softropic.skillars.platform.security.contract.exception.OperationNotAllowedException;
import com.softropic.skillars.platform.security.service.SecurityUtil;
import com.softropic.skillars.platform.video.contract.PlaybackAuthorizationResponse;
import com.softropic.skillars.platform.video.contract.PlaybackResponse;
import com.softropic.skillars.platform.video.repo.Video;
import com.softropic.skillars.platform.video.service.PlaybackService;
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

    @GetMapping("/{id}/play")
    @PreAuthorize(SecurityConstants.IS_AUTHENTICATED)
    public ResponseEntity<PlaybackResponse> play(@PathVariable UUID id, HttpServletRequest request) {
        String currentUser = securityUtil.getCurrentUserName();

        // Story 6.5 will open access to coaches + admins via @videoAccessGuard
        Video video = videoService.findById(id);
        if (!currentUser.equals(video.getOwnerId())) {
            throw new OperationNotAllowedException("Access denied: not the video owner", SecurityError.MISSING_RIGHTS);
        }

        PlaybackAuthorizationResponse auth = playbackService.authorizePlayback(id, currentUser, extractClientIp(request));

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
