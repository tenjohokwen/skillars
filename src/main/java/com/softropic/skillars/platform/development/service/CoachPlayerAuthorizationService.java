package com.softropic.skillars.platform.development.service;

import com.softropic.skillars.infrastructure.security.SecurityError;
import com.softropic.skillars.platform.booking.repo.BookingRepository;
import com.softropic.skillars.platform.marketplace.repo.CoachProfile;
import com.softropic.skillars.platform.marketplace.repo.CoachProfileRepository;
import com.softropic.skillars.platform.security.contract.exception.OperationNotAllowedException;
import com.softropic.skillars.platform.security.contract.util.AuthoritiesConstants;
import com.softropic.skillars.platform.security.repo.PlayerProfileRepository;
import com.softropic.skillars.platform.security.service.SecurityUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class CoachPlayerAuthorizationService {

    private static final List<String> ACTIVE_RELATIONSHIP_STATUSES =
        List.of("ACCEPTED", "CONFIRMED", "COMPLETED", "UPCOMING");

    // skillars-deferred-153 AC5: deliberately the SAME 12-value list as
    // CoachRosterService.ROSTER_MEMBERSHIP_STATUSES (that service now references this constant
    // directly instead of maintaining its own copy) — widens read/detail-endpoint authorization to
    // match the roster's own membership width, so a player in one of the 8 extra statuses who
    // already shows on the roster does not 403 on a detail-view read endpoint. Deliberately NOT
    // used by ACTIVE_RELATIONSHIP_STATUSES's write call sites (RadarAssessmentService.submitAssessment,
    // ReportGenerationService.generateReport, SluTargetService.setTargets) — widening those would
    // let a coach submit assessments/generate reports/set targets for e.g. a DISPUTED-status player,
    // a different and larger decision than this AC's scope.
    public static final List<String> READ_RELATIONSHIP_STATUSES = List.of(
        "ACCEPTED", "PAYMENT_PENDING", "CONFIRMED", "UPCOMING", "IN_PROGRESS", "PAUSED",
        "COMPLETED_PENDING_CONFIRMATION", "COMPLETED", "NO_SHOW_PLAYER", "NO_SHOW_COACH",
        "DISPUTED", "REFUND_PENDING");

    private final BookingRepository bookingRepository;
    private final CoachProfileRepository coachProfileRepository;
    private final SecurityUtil securityUtil;
    private final PlayerProfileRepository playerProfileRepository;

    /**
     * Throws OperationNotAllowedException if the current coach has no booking
     * relationship with the given player.
     */
    public void requireCoachPlayerRelationship(Long coachUserId, Long playerId) {
        UUID coachProfileId = coachProfileRepository.findByUserId(coachUserId)
            .map(CoachProfile::getId)
            .orElseThrow(() -> new OperationNotAllowedException(
                "Coach profile not found", SecurityError.MISSING_RIGHTS));

        requireCoachPlayerRelationship(coachProfileId, playerId);
    }

    /**
     * Overload for call sites that already resolved the coach profile UUID,
     * avoiding a redundant coachProfileRepository lookup.
     */
    public void requireCoachPlayerRelationship(UUID coachProfileId, Long playerId) {
        if (!bookingRepository.existsByCoachIdAndPlayerIdAndStatusIn(
                coachProfileId, playerId, ACTIVE_RELATIONSHIP_STATUSES)) {
            throw new OperationNotAllowedException(
                "Coach has no booking relationship with this player", SecurityError.MISSING_RIGHTS);
        }
    }

    /**
     * skillars-deferred-153 AC5: widened read/detail-view variant of
     * {@link #requireCoachPlayerRelationship(Long, Long)}, using {@link #READ_RELATIONSHIP_STATUSES}
     * instead of the narrower {@link #ACTIVE_RELATIONSHIP_STATUSES}.
     */
    public void requireCoachPlayerRelationshipForRead(Long coachUserId, Long playerId) {
        UUID coachProfileId = coachProfileRepository.findByUserId(coachUserId)
            .map(CoachProfile::getId)
            .orElseThrow(() -> new OperationNotAllowedException(
                "Coach profile not found", SecurityError.MISSING_RIGHTS));

        requireCoachPlayerRelationshipForRead(coachProfileId, playerId);
    }

    /**
     * Overload for call sites that already resolved the coach profile UUID.
     */
    public void requireCoachPlayerRelationshipForRead(UUID coachProfileId, Long playerId) {
        if (!bookingRepository.existsByCoachIdAndPlayerIdAndStatusIn(
                coachProfileId, playerId, READ_RELATIONSHIP_STATUSES)) {
            throw new OperationNotAllowedException(
                "Coach has no booking relationship with this player", SecurityError.MISSING_RIGHTS);
        }
    }

    /**
     * skillars-deferred-153 AC6: the 5-copy incomplete relationship predicate (RadarDisplayService,
     * SluDashboardService, SluContributionService, SluTargetService, ReportGenerationService.listReports),
     * consolidated and corrected with {@link com.softropic.skillars.platform.security.service.PlayerOwnershipGuard}'s
     * own self-owner arm ({@code existsByIdAndUserId}), which all five copies were missing. Calls the
     * widened {@link #requireCoachPlayerRelationshipForRead} rather than the narrow check the five
     * copies called today. Zero-behavior-change-risk for the self-owner-arm fix today — no production
     * path grants a user both ROLE_COACH and a self-owned PlayerProfile — but is free defense-in-depth
     * once centralized here.
     */
    public void requireCoachPlayerRelationshipIfCoach(Long playerId) {
        if (securityUtil.isCurrentUserInRole(AuthoritiesConstants.COACH)
                && !playerProfileRepository.existsByIdAndParentId(playerId, securityUtil.requireCurrentUserId())
                && !playerProfileRepository.existsByIdAndUserId(playerId, securityUtil.requireCurrentUserId())) {
            requireCoachPlayerRelationshipForRead(securityUtil.getCurrentCoachUserId(), playerId);
        }
    }
}
