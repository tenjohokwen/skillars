package com.softropic.skillars.platform.video.api;

import com.softropic.skillars.infrastructure.exception.ResourceNotFoundException;
import com.softropic.skillars.infrastructure.security.SecurityConstants;
import com.softropic.skillars.platform.booking.contract.PlannableBookingSummary;
import com.softropic.skillars.platform.marketplace.service.CoachProfileService;
import com.softropic.skillars.platform.security.repo.PlayerProfile;
import com.softropic.skillars.platform.security.repo.PlayerProfileRepository;
import com.softropic.skillars.platform.security.service.SecurityUtil;
import com.softropic.skillars.platform.video.contract.InitializeUploadRequest;
import com.softropic.skillars.platform.video.contract.InitializeUploadResponse;
import com.softropic.skillars.platform.video.contract.OperationalState;
import com.softropic.skillars.platform.video.contract.RetryUploadRequest;
import com.softropic.skillars.platform.video.contract.VideoQuotaResponse;
import com.softropic.skillars.platform.video.contract.VideoSummaryResponse;
import com.softropic.skillars.platform.video.contract.VideoType;
import com.softropic.skillars.platform.video.contract.VideoRetryUploadRequest;
import com.softropic.skillars.platform.video.contract.VideoUploadInitiateRequest;
import com.softropic.skillars.platform.video.contract.exception.VideoSubjectInvalidException;
import com.softropic.skillars.platform.video.contract.exception.VideoValidationException;
import com.softropic.skillars.platform.video.repo.Video;
import com.softropic.skillars.platform.video.repo.VideoQuota;
import com.softropic.skillars.platform.video.repo.VideoQuotaRepository;
import com.softropic.skillars.platform.video.repo.VideoRepository;
import com.softropic.skillars.platform.video.service.MinorUploadPolicy;
import com.softropic.skillars.platform.video.service.QuotaConfigService;
import com.softropic.skillars.platform.video.service.VideoDeletionService;
import com.softropic.skillars.platform.video.service.VideoService;
import com.softropic.skillars.platform.video.service.VideoSubjectService;
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
    private final MinorUploadPolicy minorUploadPolicy;
    private final VideoSubjectService videoSubjectService;

    // Coaches may only upload coach-scoped types; HOMEWORK is a player-only type
    private static final Set<VideoType> COACH_ALLOWED_VIDEO_TYPES =
        Set.of(VideoType.DRILL_DEMO, VideoType.COACH_REVIEW);

    // States that should not appear in a video list. FAILED is deliberately listed: the owner needs to
    // see a failed upload to retry it (POST /{id}/retry), and a parent to see/delete it.
    private static final Set<OperationalState> EXCLUDED_STATES =
        Set.of(OperationalState.DELETED, OperationalState.PURGED);

    // A subject viewer (parent / player of a coach video) has nothing to do with a failed, held or
    // locked upload — those stay between the coach and moderation.
    private static final Set<OperationalState> SUBJECT_EXCLUDED_STATES =
        Set.of(OperationalState.DELETED, OperationalState.PURGED, OperationalState.FAILED,
            OperationalState.LOCKED, OperationalState.HIDDEN, OperationalState.REJECTED);

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
        // V184: the session (and so the child) the video is about — required for COACH_REVIEW, optional
        // for DRILL_DEMO. The player is taken from the validated booking, never from the client.
        Long subjectPlayerId = videoSubjectService.resolveSubjectPlayer(coachId, req.videoType(), req.subjectBookingId());
        InitializeUploadResponse resp = videoService.initializeUpload(
            new InitializeUploadRequest(
                coachId.toString(), req.fileName(), req.fileSizeBytes(),
                req.mimeType(), req.videoType(),
                subjectPlayerId != null ? req.subjectBookingId() : null, subjectPlayerId));
        return ResponseEntity.status(HttpStatus.CREATED).body(resp);
    }

    /**
     * V184: the coach's held sessions (last 90 days, most recent first) a COACH_REVIEW / DRILL_DEMO
     * upload can be linked to — the upload dialog's booking picker.
     */
    @GetMapping("/coach/subject-bookings")
    @PreAuthorize(SecurityConstants.HAS_COACH_ROLE)
    @Observed(name = "video.coach.subject_bookings")
    public ResponseEntity<List<PlannableBookingSummary>> listSubjectBookings() {
        return ResponseEntity.ok(videoSubjectService.listSubjectBookings(currentCoachId()));
    }

    // ownerId for player HOMEWORK videos: PlayerProfile.id (Long TSID) as String — see Story 6.6 Task 0
    // ADR "Minors never upload" (2026-10-28): a minor / managed / DOB-less profile is refused here with
    // 403 VIDEO_MINOR_UPLOAD_REFUSED (+ admin alert), before any quota reservation or provider asset.
    // ROLE_PLAYER is admitted alongside the legacy ROLE_USER: self-registered players only carry
    // ROLE_PLAYER (PlayerRegistrationService), so ROLE_USER alone 403'd the whole /player/videos page.
    @PostMapping("/player/uploads/initiate")
    @PreAuthorize("hasAnyRole('ROLE_USER', 'ROLE_PLAYER')")
    @Observed(name = "video.player.upload.initiate")
    public ResponseEntity<InitializeUploadResponse> initiatePlayerUpload(
            @Valid @RequestBody VideoUploadInitiateRequest req) {
        if (req.videoType() != VideoType.HOMEWORK) {
            throw new VideoValidationException("Players may only upload HOMEWORK videos");
        }
        if (req.subjectBookingId() != null) {
            throw new VideoSubjectInvalidException("a player upload carries no subject booking");
        }
        PlayerProfile profile = currentPlayerProfile();
        minorUploadPolicy.assertMayUpload(profile);
        String ownerId = String.valueOf(profile.getId());
        InitializeUploadResponse resp = videoService.initializeUpload(
            new InitializeUploadRequest(
                ownerId, req.fileName(), req.fileSizeBytes(),
                req.mimeType(), req.videoType()));
        return ResponseEntity.status(HttpStatus.CREATED).body(resp);
    }

    @GetMapping("/quotas/me")
    @PreAuthorize("hasAnyRole('ROLE_USER', 'ROLE_PLAYER', 'ROLE_COACH')")
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
    @PreAuthorize("hasAnyRole('ROLE_USER', 'ROLE_PLAYER', 'ROLE_COACH')")
    @Observed(name = "video.list.my")
    public ResponseEntity<List<VideoSummaryResponse>> getMyVideos() {
        String ownerId = resolveCurrentOwnerId();
        return ResponseEntity.ok(listByOwner(ownerId));
    }

    /**
     * A linked player's videos, for their parent (or the self-registered player themselves) — the
     * ownership guard admits exactly those two. Player video ownerIds are PlayerProfile ids (Story 6.6),
     * which is the id space of {@code playerId} here. Deletion goes through {@code DELETE /{id}}, whose
     * {@code videoAccessGuard.canDelete} already admits the parent via ParentPlayerLink.
     */
    @GetMapping("/players/{playerId}/videos")
    @PreAuthorize("@playerOwnershipGuard.check(authentication, #playerId)")
    @Observed(name = "video.list.player")
    public ResponseEntity<List<VideoSummaryResponse>> getPlayerVideos(@PathVariable Long playerId) {
        return ResponseEntity.ok(listByOwner(String.valueOf(playerId)));
    }

    /**
     * V184: coach videos whose SUBJECT is this player (a coach review of one of their sessions), for
     * their parent or the self-registered player — the same ownership guard as the list above. Play
     * goes through {@code GET /{id}/play}, where {@code videoAccessGuard.canPlay} admits exactly these
     * viewers; there is no delete (the coach owns the video) and no download.
     */
    @GetMapping("/players/{playerId}/coach-videos")
    @PreAuthorize("@playerOwnershipGuard.check(authentication, #playerId)")
    @Observed(name = "video.list.player.coach")
    public ResponseEntity<List<VideoSummaryResponse>> getCoachVideosAboutPlayer(@PathVariable Long playerId) {
        return ResponseEntity.ok(listBySubject(playerId));
    }

    /** V184: the self-registered player's own view of the coach videos about them. */
    @GetMapping("/my/coach-videos")
    @PreAuthorize("hasAnyRole('ROLE_USER', 'ROLE_PLAYER')")
    @Observed(name = "video.list.my.coach")
    public ResponseEntity<List<VideoSummaryResponse>> getMyCoachVideos() {
        return ResponseEntity.ok(listBySubject(currentPlayerProfile().getId()));
    }

    /**
     * Restarts a FAILED upload on the same video record (video-module Story 3.1 / FR-12): new upload
     * session + fresh TUS credentials, same videoId. Owner-only — {@code VideoService.retryUpload} 404s
     * a video the caller does not own and claims FAILED→UPLOADING atomically (409 for a concurrent loser,
     * 422 when the video is not FAILED).
     */
    @PostMapping("/{id}/retry")
    @PreAuthorize("hasAnyRole('ROLE_USER', 'ROLE_PLAYER', 'ROLE_COACH')")
    @Observed(name = "video.upload.retry.request")
    public ResponseEntity<InitializeUploadResponse> retryUpload(
            @PathVariable UUID id, @Valid @RequestBody VideoRetryUploadRequest req) {
        String ownerId;
        if (securityUtil.isCurrentUserInRole(SecurityConstants.ROLE_COACH)) {
            ownerId = resolveCurrentOwnerId();
        } else {
            // A retry is a fresh upload of the footage: the same minors-never-upload refusal applies.
            PlayerProfile profile = currentPlayerProfile();
            minorUploadPolicy.assertMayUpload(profile);
            ownerId = String.valueOf(profile.getId());
        }
        return ResponseEntity.ok(videoService.retryUpload(
            new RetryUploadRequest(id, ownerId, req.fileSizeBytes())));
    }

    private List<VideoSummaryResponse> listByOwner(String ownerId) {
        return videoRepository
            .findByOwnerIdAndOperationalStateNotInOrderByCreatedAtDesc(ownerId, EXCLUDED_STATES)
            .stream()
            .map(VideoResource::toSummary)
            .toList();
    }

    /** Subject viewers only ever see playable-or-pending coach videos — not FAILED / HIDDEN ones. */
    private List<VideoSummaryResponse> listBySubject(Long playerId) {
        return videoRepository
            .findBySubjectPlayerIdAndOperationalStateNotInOrderByCreatedAtDesc(playerId, SUBJECT_EXCLUDED_STATES)
            .stream()
            .map(VideoResource::toSummary)
            .toList();
    }

    private static VideoSummaryResponse toSummary(Video v) {
        return new VideoSummaryResponse(
            v.getId(),
            v.getTitle(),
            v.getOperationalState(),
            v.getAccessState(),
            v.getOwnerId(),
            v.getVisibility(),
            v.getVideoType() != null ? v.getVideoType().name() : null,
            v.getCreatedAt(),
            v.getUpdatedAt(),
            v.getSubjectBookingId(),
            v.getSubjectPlayerId());
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
     * Players (ROLE_PLAYER / legacy ROLE_USER): businessId = the caller's USER id → resolve the
     * PlayerProfile they own (PlayerProfile.userId) → return its id as String. Player video ownerIds
     * are PlayerProfile ids everywhere downstream (minor refusal, quota tier, subscription lifecycle).
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
        return String.valueOf(currentPlayerProfileId());
    }

    /**
     * The caller's own PlayerProfile id. Principal.businessId is the USER id for every role, never a
     * PlayerProfile id, so it must be resolved through PlayerProfile.userId (a self-registered
     * player's own profile). A caller without one — e.g. a parent, whose children's profiles are
     * not "theirs" to upload into — is refused rather than given a user-id-keyed video space.
     */
    private Long currentPlayerProfileId() {
        return currentPlayerProfile().getId();
    }

    private PlayerProfile currentPlayerProfile() {
        Long userId = securityUtil.requireCurrentUserId();
        return playerProfileRepository.findByUserId(userId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN,
                "Player profile not found — complete account registration first"));
    }

    private UUID currentCoachId() {
        try {
            return coachProfileService.getCoachIdByUserId(securityUtil.getCurrentCoachUserId());
        } catch (ResourceNotFoundException e) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Coach profile not found");
        }
    }
}
