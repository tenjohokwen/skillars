package com.softropic.skillars.platform.development.service;

import com.softropic.skillars.infrastructure.security.SecurityError;
import com.softropic.skillars.platform.booking.repo.BookingRepository;
import com.softropic.skillars.platform.marketplace.repo.CoachProfile;
import com.softropic.skillars.platform.marketplace.repo.CoachProfileRepository;
import com.softropic.skillars.platform.security.contract.exception.OperationNotAllowedException;
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

    private final BookingRepository bookingRepository;
    private final CoachProfileRepository coachProfileRepository;

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
}
