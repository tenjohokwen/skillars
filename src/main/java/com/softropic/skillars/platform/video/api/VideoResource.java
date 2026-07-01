package com.softropic.skillars.platform.video.api;

import com.softropic.skillars.infrastructure.exception.ResourceNotFoundException;
import com.softropic.skillars.infrastructure.security.SecurityConstants;
import com.softropic.skillars.platform.marketplace.service.CoachProfileService;
import com.softropic.skillars.platform.security.repo.PlayerProfile;
import com.softropic.skillars.platform.security.repo.PlayerProfileRepository;
import com.softropic.skillars.platform.security.service.SecurityUtil;
import com.softropic.skillars.platform.video.contract.InitializeUploadRequest;
import com.softropic.skillars.platform.video.contract.InitializeUploadResponse;
import com.softropic.skillars.platform.video.contract.OperationalState;
import com.softropic.skillars.platform.video.contract.VideoQuotaResponse;
import com.softropic.skillars.platform.video.contract.VideoSummaryResponse;
import com.softropic.skillars.platform.video.contract.VideoType;
import com.softropic.skillars.platform.video.contract.VideoUploadInitiateRequest;
import com.softropic.skillars.platform.video.contract.exception.VideoValidationException;
import com.softropic.skillars.platform.video.repo.Video;
import com.softropic.skillars.platform.video.repo.VideoQuota;
import com.softropic.skillars.platform.video.repo.VideoQuotaRepository;
import com.softropic.skillars.platform.video.repo.VideoRepository;
import com.softropic.skillars.platform.video.service.QuotaConfigService;
import com.softropic.skillars.platform.video.service.VideoDeletionService;
import com.softropic.skillars.platform.video.service.VideoService;
import io.micrometer.observation.annotation.Observed;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
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
    private final PlayerProfileRepository playerProfileRepository;
    private final VideoRepository videoRepository;
    private final VideoQuotaRepository videoQuotaRepository;
    private final QuotaConfigService quotaConfigService;

    // Coaches may only upload coach-scoped types; HOMEWORK is a player-only type
    private static final Set<VideoType> COACH_ALLOWED_VIDEO_TYPES =
        Set.of(VideoType.DRILL_DEMO, VideoType.COACH_REVIEW);

    // States that should not appear in a player's video list
    private static final Set<OperationalState> EXCLUDED_STATES =
        Set.of(OperationalState.DELETED, OperationalState.PURGED, OperationalState.FAILED);

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

    // ownerId for player HOMEWORK videos: PlayerProfile.id (Long TSID) as String — see Story 6.6 Task 0
    // Minor players are allowed to upload; the minor safety gate (Layer 3 moderation) handles
    // parental approval after content moderation passes. No server-side 403 for minor uploads.
    @PostMapping("/player/uploads/initiate")
    @PreAuthorize("hasAnyRole('ROLE_USER')")
    @Observed(name = "video.player.upload.initiate")
    public ResponseEntity<InitializeUploadResponse> initiatePlayerUpload(
            @Valid @RequestBody VideoUploadInitiateRequest req) {
        if (req.videoType() != VideoType.HOMEWORK) {
            throw new VideoValidationException("Players may only upload HOMEWORK videos");
        }
        Long playerId = currentPlayerProfileId();
        PlayerProfile profile = playerProfileRepository.findById(playerId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN,
                "Player profile not found — complete account registration before uploading"));
        String ownerId = String.valueOf(profile.getId());
        InitializeUploadResponse resp = videoService.initializeUpload(
            new InitializeUploadRequest(
                ownerId, req.fileName(), req.fileSizeBytes(),
                req.mimeType(), req.videoType()));
        return ResponseEntity.status(HttpStatus.CREATED).body(resp);
    }

    @GetMapping("/quotas/me")
    @PreAuthorize("hasAnyRole('ROLE_USER', 'ROLE_COACH')")
    @Observed(name = "video.quota.query")
    public ResponseEntity<VideoQuotaResponse> getMyQuota() {
        String ownerId = resolveCurrentOwnerId();
        // If no quota row exists yet (user has never uploaded), return zeros with tier limits
        VideoQuota quota = videoQuotaRepository.findById(ownerId).orElse(null);
        long storageUsed = quota != null ? quota.getStorageUsedBytes() : 0L;
        long bandwidthUsed = quota != null ? quota.getBandwidthUsedBytes() : 0L;
        long storageLimit = quotaConfigService.getStorageQuotaBytes(ownerId);
        long bandwidthLimit = quotaConfigService.getBandwidthQuotaBytesMonthly(ownerId);
        return ResponseEntity.ok(new VideoQuotaResponse(
            storageUsed, storageLimit, bandwidthUsed, bandwidthLimit, null));
    }

    @GetMapping("/my")
    @PreAuthorize("hasAnyRole('ROLE_USER', 'ROLE_COACH')")
    @Observed(name = "video.list.my")
    public ResponseEntity<List<VideoSummaryResponse>> getMyVideos() {
        String ownerId = resolveCurrentOwnerId();
        List<Video> videos = videoRepository
            .findByOwnerIdAndOperationalStateNotInOrderByCreatedAtDesc(ownerId, EXCLUDED_STATES);
        List<VideoSummaryResponse> responses = videos.stream()
            .map(v -> new VideoSummaryResponse(
                v.getId(),
                v.getTitle(),
                v.getOperationalState(),
                v.getAccessState(),
                v.getOwnerId(),
                v.getVisibility(),
                v.getVideoType() != null ? v.getVideoType().name() : null,
                v.getCreatedAt(),
                v.getUpdatedAt()))
            .toList();
        return ResponseEntity.ok(responses);
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("@videoAccessGuard.canDelete(authentication, #id)")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Observed(name = "video.delete")
    public void deleteVideo(@PathVariable UUID id) {
        String currentUser = securityUtil.getCurrentUserName();
        videoDeletionService.deleteByUser(id, currentUser);
    }

    /**
     * Resolves the current user's ownerId for video storage queries.
     * Coaches (ROLE_COACH): businessId = user Long ID → look up coach profile UUID → return UUID string.
     * Players (ROLE_USER): businessId = PlayerProfile.id Long → return as String.
     */
    private String resolveCurrentOwnerId() {
        if (securityUtil.isCurrentUserInRole(SecurityConstants.ROLE_COACH)) {
            try {
                Long coachUserId = securityUtil.getCurrentCoachUserId();
                UUID coachId = coachProfileService.getCoachIdByUserId(coachUserId);
                return coachId.toString();
            } catch (ResourceNotFoundException e) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Coach profile not found");
            }
        }
        // Player path: businessId = PlayerProfile.id (Long TSID) as String
        return String.valueOf(securityUtil.requireCurrentUserId());
    }

    private Long currentPlayerProfileId() {
        return securityUtil.requireCurrentUserId();
    }
}
