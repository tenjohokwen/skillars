package com.softropic.skillars.platform.payment.service;

import com.softropic.skillars.infrastructure.exception.ResourceNotFoundException;
import com.softropic.skillars.platform.config.service.ConfigService;
import com.softropic.skillars.platform.marketplace.contract.CoachProfileStatus;
import com.softropic.skillars.platform.marketplace.repo.CoachProfile;
import com.softropic.skillars.platform.marketplace.repo.CoachProfileRepository;
import com.softropic.skillars.platform.marketplace.repo.CoachReliabilityStrike;
import com.softropic.skillars.platform.marketplace.repo.CoachReliabilityStrikeRepository;
import com.softropic.skillars.platform.payment.contract.event.CoachVisibilityReducedEvent;
import com.softropic.skillars.platform.payment.contract.event.StrikeThresholdReachedEvent;
import com.softropic.skillars.platform.security.contract.exception.OperationNotAllowedException;
import com.softropic.skillars.infrastructure.security.SecurityError;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class ReliabilityStrikeService {

    private final CoachReliabilityStrikeRepository strikeRepository;
    private final CoachProfileRepository coachProfileRepository;
    private final ConfigService configService;
    private final ApplicationEventPublisher eventPublisher;

    @Transactional
    public void issue(UUID coachId, UUID bookingId, String reason) {
        CoachReliabilityStrike strike = new CoachReliabilityStrike();
        strike.setCoachId(coachId);
        strike.setBookingId(bookingId);
        strike.setReason(reason);
        strike.setAcknowledged(false);
        strikeRepository.save(strike);

        long count = strikeRepository.countByCoachIdAndCreatedAtAfter(coachId, OffsetDateTime.now().minusDays(30));
        long suspensionThreshold = Long.parseLong(configService.getString("reliability.strike.suspensionThreshold"));
        long visibilityThreshold = Long.parseLong(configService.getString("reliability.strike.visibilityThreshold"));

        CoachProfile coach = coachProfileRepository.findById(coachId)
            .orElseThrow(() -> new ResourceNotFoundException("Coach not found", "coach_profile"));

        // Check PENDING_REVIEW threshold first (mutually exclusive per AC 9)
        if (count >= suspensionThreshold) {
            if (coach.getStatus() != CoachProfileStatus.PENDING_REVIEW) {
                coach.setStatus(CoachProfileStatus.PENDING_REVIEW);
                coachProfileRepository.save(coach);
                eventPublisher.publishEvent(new StrikeThresholdReachedEvent(this, coachId, bookingId, count));
                log.warn("Coach suspended for review: coachId={} rollingCount={}", coachId, count);
            }
        } else if (count >= visibilityThreshold) {
            if (coach.getStatus() != CoachProfileStatus.REDUCED && coach.getStatus() != CoachProfileStatus.PENDING_REVIEW) {
                coach.setStatus(CoachProfileStatus.REDUCED);
                coachProfileRepository.save(coach);
                eventPublisher.publishEvent(new CoachVisibilityReducedEvent(this, coachId, count));
                log.info("Coach visibility reduced: coachId={} rollingCount={}", coachId, count);
            }
        }
    }

    @Transactional
    public void acknowledge(UUID strikeId, Long coachUserId) {
        CoachReliabilityStrike strike = strikeRepository.findById(strikeId)
            .orElseThrow(() -> new ResourceNotFoundException("Strike not found", "coach_reliability_strike"));

        CoachProfile coach = coachProfileRepository.findByUserId(coachUserId)
            .orElseThrow(() -> new ResourceNotFoundException("Coach profile not found", "coach_profile"));

        if (!strike.getCoachId().equals(coach.getId())) {
            throw new OperationNotAllowedException("Strike does not belong to this coach", SecurityError.MISSING_RIGHTS);
        }

        strike.setAcknowledged(true);
        strikeRepository.save(strike);
        log.info("Strike acknowledged: strikeId={} coachId={}", strikeId, coach.getId());
    }

    @Transactional(readOnly = true)
    public List<CoachReliabilityStrike> getCoachStrikes(Long coachUserId) {
        CoachProfile coach = coachProfileRepository.findByUserId(coachUserId)
            .orElseThrow(() -> new ResourceNotFoundException("Coach profile not found", "coach_profile"));
        return strikeRepository.findByCoachIdOrderByCreatedAtDesc(coach.getId());
    }
}
