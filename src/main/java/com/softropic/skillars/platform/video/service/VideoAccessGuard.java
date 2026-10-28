package com.softropic.skillars.platform.video.service;

import com.softropic.skillars.infrastructure.exception.ResourceNotFoundException;
import com.softropic.skillars.platform.config.service.ConfigBounds;
import com.softropic.skillars.platform.marketplace.service.CoachProfileService;
import com.softropic.skillars.platform.security.contract.Principal;
import com.softropic.skillars.platform.security.repo.ParentPlayerLink;
import com.softropic.skillars.platform.security.repo.ParentPlayerLinkRepository;
import com.softropic.skillars.platform.security.repo.PlayerProfileRepository;
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
 *   - Player videos: PlayerProfile.id (Long) as string (Story 6.6 path) — NOT the user id; the
 *     owner check resolves the caller's user id through PlayerProfile.userId
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
    private final PlayerProfileRepository playerProfileRepository;

    /**
     * Guards GET /api/video/{id}/play.
     * Checks in order: PURGED gate → admin → owner → parent of owner → subject (V184: parent of the
     * child a coach video is about, or that child themself) → coach → deny.
     * Admission here is not playability: {@code PlaybackService} still refuses any video that is not
     * READY/ACTIVE (HIDDEN included, for every caller).
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

        // Step 2: Owner check (cached so VideoPlayResource's download-URL decision reuses it)
        boolean owner = isCurrentUserOwner(video);
        videoAccessCache.setOwnerDecision(videoId, owner);
        if (owner) {
            return true;
        }

        // Step 3: Parent of owner
        if (isPlayerOwnedVideo(video)) {
            Long playerId = Long.parseLong(video.getOwnerId());
            Long currentUserLongId = getCurrentUserLongId();
            if (currentUserLongId != null && isLinkedParent(playerId, currentUserLongId)) {
                return true;
            }
        }

        // Step 3b (V184): a coach video about a child — that child's parent(s), and the child themself
        // if self-registered, may PLAY it (never download: downloads stay owner-only in PlaybackService).
        if (isSubjectViewer(video)) {
            return true;
        }

        // Step 4: Coach with active relationship (player videos only)
        if (isPlayerOwnedVideo(video)) {
            try {
                Long coachUserId = securityUtil.getCurrentCoachUserId();
                UUID coachId = coachProfileService.getCoachIdByUserId(coachUserId);
                Long playerId = Long.parseLong(video.getOwnerId());
                // skillars-deferred-107 AC2: 0/neg → a coach with a recent completed booking can no
                // longer view player videos. Upper bound is a *business* cap (3650d ≈ 10y), chosen
                // well under the Math.toIntExact ceiling; widen it (still < Integer.MAX_VALUE) rather
                // than let a legitimate >10y window clamp. Range and default come from ConfigBounds.VIDEO_ACCESS_COACH_WINDOW_DAYS.
                int windowDays = Math.toIntExact(
                    configService.getBoundedLong(ConfigBounds.VIDEO_ACCESS_COACH_WINDOW_DAYS));
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
     * V184: whether the caller may play {@code video} as its SUBJECT — a linked parent of the player the
     * coach video is about (ParentPlayerLink), or that player themself (PlayerProfile.userId). False for
     * a video with no subject.
     */
    public boolean isSubjectViewer(Video video) {
        Long subjectPlayerId = video.getSubjectPlayerId();
        if (subjectPlayerId == null) {
            return false;
        }
        Long currentUserLongId = getCurrentUserLongId();
        if (currentUserLongId == null) {
            return false;
        }
        return isLinkedParent(subjectPlayerId, currentUserLongId)
            || playerProfileRepository.existsByIdAndUserId(subjectPlayerId, currentUserLongId);
    }

    private boolean isLinkedParent(Long playerId, Long userId) {
        List<ParentPlayerLink> links = parentPlayerLinkRepository.findAllByPlayerId(playerId);
        return links.stream().anyMatch(link -> link.getParentId().equals(userId));
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

        if (isCurrentUserOwner(video)) {
            return true;
        }

        // A subject viewer (V184) may play a coach video about their child but NOT delete it — the coach
        // owns it; only GDPR erasure of the child purges it.
        if (isPlayerOwnedVideo(video)) {
            Long playerId = Long.parseLong(video.getOwnerId());
            Long currentUserLongId = getCurrentUserLongId();
            if (currentUserLongId != null && isLinkedParent(playerId, currentUserLongId)) {
                return true;
            }
        }

        String requesterId = securityUtil.getCurrentUserName();
        throw new VideoDeletionNotAuthorisedException(videoId, requesterId != null ? requesterId : "unknown");
    }

    /**
     * Whether the authenticated caller owns {@code video}'s storage space — the single owner check for
     * the whole video module. {@code video.ownerId} is a coach-profile UUID or a PlayerProfile id,
     * never a username/email, so callers must not compare it to {@code getCurrentUserName()} (that
     * comparison never matched: owner downloads were never issued and coaches 403'd on their own
     * upload's /events and /status).
     */
    public boolean isCurrentUserOwner(Video video) {
        if (video.getId() != null) {
            Optional<Boolean> cached = videoAccessCache.getOwnerDecision(video.getId());
            if (cached.isPresent()) {
                return cached.get();
            }
        }
        if (isPlayerOwnedVideo(video)) {
            // Player video: ownerId = PlayerProfile.id string; the caller owns it when that profile is
            // their own (PlayerProfile.userId == caller's user id). Comparing the user id directly to
            // the profile id never matched, so a player could not play or delete their own upload.
            Long currentUserLongId = getCurrentUserLongId();
            return currentUserLongId != null
                && playerProfileRepository.existsByIdAndUserId(Long.parseLong(video.getOwnerId()), currentUserLongId);
        } else {
            // Coach video: ownerId = coach profile UUID string
            try {
                Long coachUserId = securityUtil.getCurrentCoachUserId();
                UUID coachId = coachProfileService.getCoachIdByUserId(coachUserId);
                return coachId != null && coachId.toString().equals(video.getOwnerId());
            } catch (ResourceNotFoundException e) {
                // The caller has no coach profile (a parent or player — e.g. a V184 subject viewer of a
                // coach video): simply not the owner, not an infrastructure failure worth a WARN.
                return false;
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
