package com.softropic.skillars.platform.video.api;

import com.softropic.skillars.infrastructure.security.SecurityConstants;
import com.softropic.skillars.platform.video.contract.*;
import com.softropic.skillars.platform.video.repo.UploadSession;
import com.softropic.skillars.platform.video.repo.Video;
import com.softropic.skillars.platform.video.service.AdminVideoService;
import io.micrometer.observation.annotation.Observed;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/video/admin")
@RequiredArgsConstructor
public class AdminVideoResource {

    private final AdminVideoService adminVideoService;

    /**
     * Admin video review queue (story 6.3 / FR-ADM-002). {@code filter}: ATTENTION (default), LOCKED,
     * FAILED or BLOCKED — an unknown value is a 400 via the standard type-mismatch handler.
     */
    @PreAuthorize(SecurityConstants.HAS_ADMIN_ROLE)
    @Observed(name = "video.admin.reviewQueue")
    @GetMapping("/videos")
    public ResponseEntity<Page<AdminVideoQueueItemDto>> reviewQueue(
            @RequestParam(defaultValue = "ATTENTION") AdminVideoQueueFilter filter,
            @RequestParam(defaultValue = "0") int page) {
        return ResponseEntity.ok(adminVideoService.getReviewQueue(filter, page));
    }

    @PreAuthorize(SecurityConstants.HAS_ADMIN_ROLE)
    @Observed(name = "video.admin.getVideo")
    @GetMapping("/videos/{videoId}")
    public ResponseEntity<AdminVideoQueueItemDto> getVideo(@PathVariable UUID videoId) {
        return ResponseEntity.ok(adminVideoService.getVideo(videoId));
    }

    @PreAuthorize(SecurityConstants.HAS_ADMIN_ROLE)
    @Observed(name = "video.admin.setAccessState")
    @PatchMapping("/videos/{videoId}/access-state")
    public ResponseEntity<VideoSummaryResponse> setAccessState(
            @PathVariable UUID videoId,
            @Valid @RequestBody SetAccessStateRequest request) {
        Video updated = adminVideoService.setVideoAccessState(videoId, request.newAccessState());
        return ResponseEntity.ok(toSummary(updated));
    }

    @PreAuthorize(SecurityConstants.HAS_ADMIN_ROLE)
    @Observed(name = "video.admin.deleteVideo")
    @DeleteMapping("/videos/{videoId}")
    public ResponseEntity<Void> deleteVideo(@PathVariable UUID videoId) {
        adminVideoService.deleteVideo(videoId);
        return ResponseEntity.noContent().build();
    }

    @PreAuthorize(SecurityConstants.HAS_ADMIN_ROLE)
    @Observed(name = "video.admin.listSessions")
    @GetMapping("/videos/{videoId}/sessions")
    public ResponseEntity<List<UploadSessionDto>> listSessions(@PathVariable UUID videoId) {
        List<UploadSession> sessions = adminVideoService.listVideoSessions(videoId);
        List<UploadSessionDto> dtos = sessions.stream().map(this::toSessionDto).toList();
        return ResponseEntity.ok(dtos);
    }

    @PreAuthorize(SecurityConstants.HAS_ADMIN_ROLE)
    @Observed(name = "video.admin.triggerReconciliation")
    @PostMapping("/videos/{videoId}/reconcile")
    public ResponseEntity<ReconcileResponse> reconcile(@PathVariable UUID videoId) {
        return ResponseEntity.ok(adminVideoService.triggerReconciliation(videoId));
    }

    private VideoSummaryResponse toSummary(Video v) {
        return new VideoSummaryResponse(v.getId(), v.getTitle(), v.getOperationalState(),
                v.getAccessState(), v.getOwnerId(), v.getVisibility(),
                v.getVideoType() != null ? v.getVideoType().name() : null,
                v.getCreatedAt(), v.getUpdatedAt());
    }

    private UploadSessionDto toSessionDto(UploadSession s) {
        return new UploadSessionDto(s.getId(), s.getVideoId(), s.getStatus(),
                s.getReservedBytes(), s.getExpiresAt(), s.getCreatedAt());
    }
}
