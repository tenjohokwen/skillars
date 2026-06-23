package com.softropic.skillars.platform.video.service;

import com.softropic.skillars.platform.marketplace.service.CoachProfileService;
import com.softropic.skillars.platform.security.contract.Principal;
import com.softropic.skillars.platform.security.repo.ParentPlayerLink;
import com.softropic.skillars.platform.security.repo.ParentPlayerLinkRepository;
import com.softropic.skillars.platform.security.service.SecurityUtil;
import com.softropic.skillars.platform.video.contract.OperationalState;
import com.softropic.skillars.platform.video.contract.exception.VideoDeletionNotAuthorisedException;
import com.softropic.skillars.platform.video.contract.exception.VideoNotFoundException;
import com.softropic.skillars.platform.video.contract.exception.PlaybackDeniedException;
import com.softropic.skillars.platform.booking.repo.BookingRepository;
import com.softropic.skillars.platform.config.service.ConfigService;
import com.softropic.skillars.platform.video.repo.Video;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * SpEL-accessible RBAC guard for video play and delete endpoints.
 * Bean name "videoAccessGuard" is used in @PreAuthorize annotations.
 *
 * ownerId format (confirmed Task 0 investigation):
 *   - Coach videos: coach profile UUID string (from coachProfileService.getCoachIdByUserId())
 *   - Player videos: Long user ID as string (Story 6.6 path)
 * getCurrentUserName() returns email — does NOT match either ownerId format.
 * Owner check must use UUID parse to detect coach videos, then Long.toString for players.
 * See Story 6.5 Task 0.
 */
@Slf4j
@Component("videoAccessGuard")
@RequiredArgsConstructor
public class VideoAccessGuard {

    private final VideoService videoService;
    private final SecurityUtil securityUtil;
    private final ParentPlayerLinkRepository parentPlayerLinkRepository;
    private final BookingRepository bookingRepository;
    private final CoachProfileService coachProfileService;
    private final ConfigService configService;
    private final VideoAccessCache videoAccessCache;

    /**
     * Guards GET /api/video/{id}/play.
     * Checks in order: PURGED gate → admin → owner → parent (HIDDEN bypass) → coach → deny.
     */
    public boolean canPlay(Authentication auth, UUID videoId) {
        Video video = videoService.findById(videoId);
        videoAccessCache.setVideo(videoId, video); // cache for controller reuse (eliminates second DB load)

        // Step 0: PURGED gate — returns 404 for ALL callers including admin
        if (video.getOperationalState() == OperationalState.PURGED) {
            throw new VideoNotFoundException(videoId);
        }

        // Step 1: Admin bypass
        if (securityUtil.isAdmin()) {
            return true;
        }

        // Step 2: Owner check
        if (isOwner(video)) {
            return true;
        }

        // Step 3: Parent of owner (HIDDEN bypass for parental approval flow AC 2)
        if (isPlayerOwnedVideo(video)) {
            Long playerId = Long.parseLong(video.getOwnerId());
            Long currentUserLongId = getCurrentUserLongId();
            if (currentUserLongId != null) {
                List<ParentPlayerLink> links = parentPlayerLinkRepository.findAllByPlayerId(playerId);
                boolean isParent = links.stream().anyMatch(link -> link.getParentId().equals(currentUserLongId));
                videoAccessCache.setParentDecision(videoId, isParent);
                if (isParent) {
                    return true;
                }
            }
        }

        // Step 4: Coach with active relationship (player videos only; not HIDDEN bypass for coaches)
        if (isPlayerOwnedVideo(video)) {
            try {
                Long coachUserId = securityUtil.getCurrentCoachUserId();
                UUID coachId = coachProfileService.getCoachIdByUserId(coachUserId);
                Long playerId = Long.parseLong(video.getOwnerId());
                int windowDays = Math.toIntExact(configService.getLong("platform.video.access.coach_window_days", 90L));
                Instant windowStart = Instant.now().minus(windowDays, ChronoUnit.DAYS);
                if (bookingRepository.existsRecentCompletedBooking(coachId, playerId, windowStart)) {
                    return true;
                }
            } catch (Exception e) {
                // Not a coach or no coach profile — fall through to deny
            }
        }

        // Step 5: Deny
        throw new PlaybackDeniedException(videoId, securityUtil.getCurrentUserName());
    }

    /**
     * Returns true if the current user is a verified parent of the video owner.
     * Reads from VideoAccessCache first (populated by canPlay() during @PreAuthorize) to avoid a
     * second DB round-trip. Falls back to a live DB query only on cache miss.
     */
    public boolean isParentOf(String currentUserId, String videoOwnerId, UUID videoId) {
        if (!isLongIdString(videoOwnerId)) {
            return false; // coach-owned video — no parent relationship
        }
        Optional<Boolean> cached = videoAccessCache.getParentDecision(videoId);
        if (cached.isPresent()) {
            return cached.get();
        }
        try {
            Long playerId = Long.parseLong(videoOwnerId);
            Long currentUserLongId = getCurrentUserLongId();
            if (currentUserLongId == null) return false;
            List<ParentPlayerLink> links = parentPlayerLinkRepository.findAllByPlayerId(playerId);
            return links.stream().anyMatch(link -> link.getParentId().equals(currentUserLongId));
        } catch (NumberFormatException e) {
            return false;
        }
    }

    /**
     * Guards DELETE /api/video/{id}.
     * Checks: PURGED gate → admin → owner → parent → deny.
     * Coaches are explicitly NOT allowed to delete player videos (canPlay ≠ canDelete).
     */
    public boolean canDelete(Authentication auth, UUID videoId) {
        Video video = videoService.findById(videoId);

        if (video.getOperationalState() == OperationalState.PURGED) {
            throw new VideoNotFoundException(videoId);
        }

        if (securityUtil.isAdmin()) {
            return true;
        }

        if (isOwner(video)) {
            return true;
        }

        if (isPlayerOwnedVideo(video)) {
            Long playerId = Long.parseLong(video.getOwnerId());
            Long currentUserLongId = getCurrentUserLongId();
            if (currentUserLongId != null) {
                List<ParentPlayerLink> links = parentPlayerLinkRepository.findAllByPlayerId(playerId);
                if (links.stream().anyMatch(link -> link.getParentId().equals(currentUserLongId))) {
                    return true;
                }
            }
        }

        String requesterId = securityUtil.getCurrentUserName();
        throw new VideoDeletionNotAuthorisedException(videoId, requesterId != null ? requesterId : "unknown");
    }

    private boolean isOwner(Video video) {
        if (isPlayerOwnedVideo(video)) {
            // Player video: ownerId = Long user ID string
            Long currentUserLongId = getCurrentUserLongId();
            return currentUserLongId != null && String.valueOf(currentUserLongId).equals(video.getOwnerId());
        } else {
            // Coach video: ownerId = coach profile UUID string
            try {
                Long coachUserId = securityUtil.getCurrentCoachUserId();
                UUID coachId = coachProfileService.getCoachIdByUserId(coachUserId);
                return coachId.toString().equals(video.getOwnerId());
            } catch (Exception e) {
                log.warn("[OWNER_CHECK_INFRA_FAILURE videoId={}] Infrastructure error checking coach ownership — denying access", video.getId(), e);
                return false;
            }
        }
    }

    private boolean isPlayerOwnedVideo(Video video) {
        return isLongIdString(video.getOwnerId());
    }

    private boolean isLongIdString(String value) {
        if (value == null || value.isBlank()) return false;
        try {
            UUID.fromString(value);
            return false; // valid UUID → coach-owned
        } catch (IllegalArgumentException e) {
            // Not a UUID — verify it is also parseable as a Long before treating as a player video
            try {
                Long.parseLong(value);
                return true;
            } catch (NumberFormatException nfe) {
                log.warn("[OWNER_ID_FORMAT_UNEXPECTED value={}] ownerId is neither a UUID nor a Long — treating as coach-owned", value);
                return false;
            }
        }
    }

    private Long getCurrentUserLongId() {
        try {
            org.springframework.security.core.userdetails.User user = securityUtil.getCurrentUser();
            if (user instanceof Principal principal) {
                String businessId = principal.getBusinessId();
                if (businessId != null && !businessId.isBlank()) {
                    return Long.parseLong(businessId);
                }
            }
        } catch (Exception e) {
            log.debug("Could not resolve current user Long ID", e);
        }
        return null;
    }
}
