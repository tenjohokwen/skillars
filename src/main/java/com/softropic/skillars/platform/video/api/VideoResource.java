package com.softropic.skillars.platform.video.api;

import com.softropic.skillars.infrastructure.exception.ResourceNotFoundException;
import com.softropic.skillars.infrastructure.security.SecurityConstants;
import com.softropic.skillars.platform.marketplace.service.CoachProfileService;
import com.softropic.skillars.platform.security.service.SecurityUtil;
import com.softropic.skillars.platform.video.contract.InitializeUploadRequest;
import com.softropic.skillars.platform.video.contract.InitializeUploadResponse;
import com.softropic.skillars.platform.video.contract.VideoType;
import com.softropic.skillars.platform.video.contract.VideoUploadInitiateRequest;
import com.softropic.skillars.platform.video.contract.exception.VideoValidationException;
import com.softropic.skillars.platform.video.service.VideoDeletionService;
import com.softropic.skillars.platform.video.service.VideoService;
import io.micrometer.observation.annotation.Observed;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.Set;
import java.util.UUID;

@Observed(name = "video.upload")
@RestController
@RequestMapping("/api/video")
@RequiredArgsConstructor
public class VideoResource {

    private final VideoService videoService;
    private final SecurityUtil securityUtil;
    private final CoachProfileService coachProfileService;
    private final VideoDeletionService videoDeletionService;

    // Coaches may only upload coach-scoped types; HOMEWORK is a player-only type
    private static final Set<VideoType> COACH_ALLOWED_VIDEO_TYPES =
        Set.of(VideoType.DRILL_DEMO, VideoType.COACH_REVIEW);

    @PostMapping("/uploads/initiate")
    @PreAuthorize(SecurityConstants.HAS_COACH_ROLE)
    @Observed(name = "video.upload.initiate")
    public ResponseEntity<InitializeUploadResponse> initiateUpload(
            @Valid @RequestBody VideoUploadInitiateRequest req) {
        if (!COACH_ALLOWED_VIDEO_TYPES.contains(req.videoType())) {
            throw new VideoValidationException(
                "Coaches may only upload DRILL_DEMO or COACH_REVIEW videos");
        }
        Long coachUserId = securityUtil.getCurrentCoachUserId();
        UUID coachId;
        try {
            coachId = coachProfileService.getCoachIdByUserId(coachUserId);
        } catch (ResourceNotFoundException e) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                "Coach profile not found — complete account registration before uploading");
        }
        InitializeUploadResponse resp = videoService.initializeUpload(
            new InitializeUploadRequest(
                coachId.toString(), req.fileName(), req.fileSizeBytes(),
                req.mimeType(), req.videoType()));
        return ResponseEntity.status(HttpStatus.CREATED).body(resp);
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("@videoAccessGuard.canDelete(authentication, #id)")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Observed(name = "video.delete")
    public void deleteVideo(@PathVariable UUID id) {
        String currentUser = securityUtil.getCurrentUserName();
        videoDeletionService.deleteByUser(id, currentUser);
    }
}
