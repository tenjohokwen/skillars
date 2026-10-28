package com.softropic.skillars.platform.video.service;

import com.softropic.skillars.platform.booking.contract.BookingSnapshot;
import com.softropic.skillars.platform.booking.contract.PlannableBookingSummary;
import com.softropic.skillars.platform.booking.service.BookingQueryService;
import com.softropic.skillars.platform.security.repo.PlayerProfile;
import com.softropic.skillars.platform.security.repo.PlayerProfileRepository;
import com.softropic.skillars.platform.video.contract.VideoType;
import com.softropic.skillars.platform.video.contract.exception.VideoSubjectInvalidException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * V184 — the subject of a coach video: the booking (and so the child) the footage is about.
 *
 * <p>A COACH_REVIEW must name one; a DRILL_DEMO may. The booking must be the uploading coach's own and
 * a session that actually took place (or is taking place), so a coach cannot attach footage to another
 * coach's player or to a request that never became a session. The subject player is taken from the
 * booking — never from the client — so the link can only ever point at the booking's own player.
 *
 * <p>Both fields checked here ({@code coachId}, {@code playerId}) are {@code updatable = false} on
 * {@code Booking}, so validating them on this read is validating the row the video will reference. The
 * remaining TOCTOU — the child's GDPR erasure committing between this check and the video insert — is
 * closed inside {@link VideoService#initializeUpload}'s insert transaction (FOR SHARE on the profile).
 */
@Service
@RequiredArgsConstructor
public class VideoSubjectService {

    /**
     * Booking statuses whose session took place or is under way (a review needs footage to exist).
     * DISPUTED is included: a dispute is raised on a completed session, whose footage still exists.
     * REFUND_PENDING / REFUNDED are not — they are also reached from cancellations that never happened.
     */
    static final List<String> SUBJECT_ELIGIBLE_STATUSES =
        List.of("IN_PROGRESS", "PAUSED", "COMPLETED_PENDING_CONFIRMATION", "COMPLETED", "DISPUTED");

    /** How far back the upload picker offers sessions. Validation itself has no age limit. */
    static final Duration PICKER_LOOKBACK = Duration.ofDays(90);

    private final BookingQueryService bookingQueryService;
    private final PlayerProfileRepository playerProfileRepository;

    /**
     * @return the subject PlayerProfile id, or {@code null} for an upload with no subject
     * @throws VideoSubjectInvalidException when the subject is missing for a COACH_REVIEW, or the
     *     booking is unknown, not this coach's, not a held session, or has no player
     */
    public Long resolveSubjectPlayer(UUID coachId, VideoType videoType, UUID subjectBookingId) {
        if (subjectBookingId == null) {
            if (videoType == VideoType.COACH_REVIEW) {
                throw new VideoSubjectInvalidException("COACH_REVIEW requires the session (booking) it reviews");
            }
            return null;
        }
        if (videoType != VideoType.COACH_REVIEW && videoType != VideoType.DRILL_DEMO) {
            throw new VideoSubjectInvalidException("only coach videos carry a subject booking");
        }
        // Unknown and not-yours collapse into one answer so booking ids cannot be probed.
        BookingSnapshot booking = bookingQueryService.getBookingSnapshot(subjectBookingId)
            .filter(b -> coachId.equals(b.coachId()))
            .orElseThrow(() -> new VideoSubjectInvalidException("booking not found for this coach"));
        if (!SUBJECT_ELIGIBLE_STATUSES.contains(booking.status())) {
            throw new VideoSubjectInvalidException("booking status " + booking.status() + " is not a held session");
        }
        if (booking.playerId() == null) {
            throw new VideoSubjectInvalidException("booking has no player");
        }
        // Early, friendly refusal for an erased child; the authoritative (locked) re-check is the
        // FOR SHARE in VideoService.initializeUpload's insert transaction.
        if (!isLive(playerProfileRepository.findById(booking.playerId()).orElse(null))) {
            throw new VideoSubjectInvalidException("the session's player no longer exists or was erased");
        }
        return booking.playerId();
    }

    /** The coach's held sessions of the last {@link #PICKER_LOOKBACK}, most recent first, for the picker. */
    public List<PlannableBookingSummary> listSubjectBookings(UUID coachId) {
        Instant now = Instant.now();
        List<PlannableBookingSummary> candidates = bookingQueryService.findCoachBookings(coachId,
                SUBJECT_ELIGIBLE_STATUSES, now.minus(PICKER_LOOKBACK), now.plus(Duration.ofDays(1)))
            .stream()
            .filter(b -> b.playerId() != null)
            .toList();
        if (candidates.isEmpty()) {
            return List.of();
        }
        // Never offer a session whose child's data was GDPR-erased — one batched lookup.
        Set<Long> livePlayers = playerProfileRepository.findAllById(
                candidates.stream().map(PlannableBookingSummary::playerId).collect(Collectors.toSet()))
            .stream()
            .filter(VideoSubjectService::isLive)
            .map(PlayerProfile::getId)
            .collect(Collectors.toSet());
        return candidates.stream()
            .filter(b -> livePlayers.contains(b.playerId()))
            .sorted(Comparator.comparing(PlannableBookingSummary::requestedStartTime).reversed())
            .toList();
    }

    private static boolean isLive(PlayerProfile profile) {
        return profile != null && profile.getDevelopmentDataErasedAt() == null;
    }
}
