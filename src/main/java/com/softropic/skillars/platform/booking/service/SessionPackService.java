package com.softropic.skillars.platform.booking.service;

import com.softropic.skillars.infrastructure.exception.ResourceNotFoundException;
import com.softropic.skillars.infrastructure.security.SecurityError;
import com.softropic.skillars.platform.booking.contract.PaymentGateway;
import com.softropic.skillars.platform.booking.contract.SessionPackExhaustedEvent;
import com.softropic.skillars.platform.booking.contract.SessionPackMapper;
import com.softropic.skillars.platform.booking.contract.SessionPackPurchasedResponse;
import com.softropic.skillars.platform.booking.repo.SessionPackPurchased;
import com.softropic.skillars.platform.booking.repo.SessionPackPurchasedRepository;
import com.softropic.skillars.platform.marketplace.repo.CoachProfile;
import com.softropic.skillars.platform.marketplace.repo.CoachProfileRepository;
import com.softropic.skillars.platform.marketplace.repo.CoachPricing;
import com.softropic.skillars.platform.marketplace.repo.CoachPricingRepository;
import com.softropic.skillars.platform.marketplace.repo.SessionPack;
import com.softropic.skillars.platform.marketplace.repo.SessionPackRepository;
import com.softropic.skillars.platform.security.contract.exception.OperationNotAllowedException;
import com.softropic.skillars.platform.security.repo.PlayerProfile;
import com.softropic.skillars.platform.security.repo.PlayerProfileRepository;
import com.softropic.skillars.platform.booking.repo.BookingRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class SessionPackService {

    private static final String STATUS_ACTIVE    = "ACTIVE";
    private static final String STATUS_EXHAUSTED = "EXHAUSTED";

    private final SessionPackPurchasedRepository repository;
    private final SessionPackRepository sessionPackRepository;
    private final CoachProfileRepository coachProfileRepository;
    private final CoachPricingRepository coachPricingRepository;
    private final PlayerProfileRepository playerProfileRepository;
    private final PaymentGateway paymentGateway;
    private final ApplicationEventPublisher eventPublisher;
    private final SessionPackMapper mapper;
    private final BookingRepository bookingRepository;

    @Transactional(readOnly = true)
    public List<SessionPackPurchasedResponse> getPacksForPlayer(Long parentId, Long playerId, UUID coachId) {
        verifyPlayerOwnership(parentId, playerId);
        List<SessionPackPurchased> packs = repository.findByParentIdAndPlayerId(parentId, playerId)
            .stream()
            .filter(p -> coachId == null || coachId.equals(p.getCoachId()))
            .sorted(Comparator.comparing(SessionPackPurchased::getStatus)
                .thenComparing(Comparator.comparing(SessionPackPurchased::getPurchasedAt).reversed()))
            .toList();

        Set<UUID> coachIds = packs.stream().map(SessionPackPurchased::getCoachId).collect(Collectors.toSet());
        Map<UUID, String> coachNames = coachProfileRepository.findAllById(coachIds).stream()
            .collect(Collectors.toMap(CoachProfile::getId, CoachProfile::getDisplayName));

        return packs.stream()
            .map(p -> mapper.toResponse(p, coachNames.getOrDefault(p.getCoachId(), "Unknown Coach")))
            .toList();
    }

    @Transactional
    public SessionPackPurchasedResponse purchasePack(Long parentId, Long playerId, UUID coachId, UUID sessionPackId) {
        verifyPlayerOwnership(parentId, playerId);

        SessionPack offered = sessionPackRepository.findById(sessionPackId)
            .orElseThrow(() -> new ResourceNotFoundException("Session pack not found", "session_pack"));
        if (!Objects.equals(offered.getCoachId(), coachId)) {
            throw new OperationNotAllowedException(
                "Pack does not belong to this coach", SecurityError.MISSING_RIGHTS);
        }

        CoachProfile coach = coachProfileRepository.findById(coachId)
            .orElseThrow(() -> new ResourceNotFoundException("Coach not found", "coach_profile"));

        CoachPricing pricing = coachPricingRepository.findByCoachId(coachId)
            .orElseThrow(() -> new ResourceNotFoundException("Coach pricing not found", "coach_pricing"));
        // TODO(7.1): Add idempotency key to prevent duplicate charges on rapid retry or double-submit
        paymentGateway.capturePayment(offered.getTotalPrice(), pricing.getCurrency());

        SessionPackPurchased pack = new SessionPackPurchased();
        pack.setParentId(parentId);
        pack.setPlayerId(playerId);
        pack.setCoachId(coachId);
        pack.setSessionCount(offered.getSessionCount());
        pack.setCreditsRemaining(offered.getSessionCount());
        return mapper.toResponse(repository.save(pack), coach.getDisplayName());
    }

    @Transactional
    public SessionPackPurchasedResponse purchaseSingleSession(Long parentId, Long playerId, UUID coachId) {
        verifyPlayerOwnership(parentId, playerId);

        CoachProfile coach = coachProfileRepository.findById(coachId)
            .orElseThrow(() -> new ResourceNotFoundException("Coach not found", "coach_profile"));
        CoachPricing pricing = coachPricingRepository.findByCoachId(coachId)
            .orElseThrow(() -> new ResourceNotFoundException("Coach pricing not found", "coach_pricing"));

        // TODO(7.1): Add idempotency key to prevent duplicate charges on rapid retry or double-submit
        paymentGateway.capturePayment(pricing.getPerSessionPrice(), pricing.getCurrency());

        SessionPackPurchased pack = new SessionPackPurchased();
        pack.setParentId(parentId);
        pack.setPlayerId(playerId);
        pack.setCoachId(coachId);
        pack.setSessionCount(1);
        pack.setCreditsRemaining(1);
        return mapper.toResponse(repository.save(pack), coach.getDisplayName());
    }

    @Transactional(readOnly = true)
    public boolean hasCredits(Long playerId, UUID coachId) {
        int creditsRemaining = repository.sumActiveCredits(playerId, coachId);
        long inFlight = bookingRepository.countInFlightBookings(playerId, coachId);
        return (creditsRemaining - inFlight) > 0;
    }

    @Transactional(readOnly = true)
    public int getCreditsRemaining(Long playerId, UUID coachId) {
        return repository.sumActiveCredits(playerId, coachId);
    }

    // TODO(3.3): BookingService must only call deductCredit with playerId sourced from a verified booking
    // entity — never from raw user input — as this method does not re-verify parent ownership by design.
    @Transactional
    public void deductCredit(Long playerId, UUID coachId) {
        List<SessionPackPurchased> packs = repository.findActivePacksForDeduction(playerId, coachId);
        if (packs.isEmpty()) {
            throw new OperationNotAllowedException("booking.creditsExhausted", SecurityError.MISSING_RIGHTS);
        }
        SessionPackPurchased pack = packs.get(0);
        pack.setCreditsRemaining(pack.getCreditsRemaining() - 1);
        if (pack.getCreditsRemaining() == 0) {
            pack.setStatus(STATUS_EXHAUSTED);
            repository.save(pack);
            eventPublisher.publishEvent(
                new SessionPackExhaustedEvent(this, pack.getId(), playerId, coachId));
        } else {
            repository.save(pack);
        }
    }

    private void verifyPlayerOwnership(Long parentId, Long playerId) {
        PlayerProfile player = playerProfileRepository.findById(playerId)
            .orElseThrow(() -> new ResourceNotFoundException("Player not found", "player_profile"));
        if (!Objects.equals(player.getParentId(), parentId)) {
            throw new OperationNotAllowedException(
                "Forbidden: player profile not owned by this parent", SecurityError.MISSING_RIGHTS);
        }
    }

}
