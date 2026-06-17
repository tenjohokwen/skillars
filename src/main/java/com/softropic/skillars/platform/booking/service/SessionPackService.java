package com.softropic.skillars.platform.booking.service;

import com.softropic.skillars.infrastructure.exception.ResourceNotFoundException;
import com.softropic.skillars.infrastructure.security.SecurityError;
import com.softropic.skillars.platform.booking.contract.BatchRuleViolationException;
import com.softropic.skillars.platform.booking.contract.BookingCancelledDueToPauseEvent;
import com.softropic.skillars.platform.booking.contract.ConflictingBookingItem;
import com.softropic.skillars.platform.booking.contract.PackPausedEvent;
import com.softropic.skillars.platform.booking.contract.PauseConflictResponse;
import com.softropic.skillars.platform.booking.contract.PausePackRequest;
import com.softropic.skillars.platform.booking.contract.PaymentGateway;
import com.softropic.skillars.platform.booking.contract.SessionPackExhaustedEvent;
import com.softropic.skillars.platform.booking.contract.SessionPackMapper;
import com.softropic.skillars.platform.booking.contract.SessionPackPurchasedResponse;
import com.softropic.skillars.platform.booking.repo.Booking;
import com.softropic.skillars.platform.booking.repo.BookingRepository;
import com.softropic.skillars.platform.booking.repo.SessionPackPurchased;
import com.softropic.skillars.platform.booking.repo.SessionPackPurchasedRepository;
import com.softropic.skillars.platform.config.service.ConfigService;
import com.softropic.skillars.platform.marketplace.repo.CoachProfile;
import com.softropic.skillars.platform.marketplace.repo.CoachProfileRepository;
import com.softropic.skillars.platform.marketplace.repo.CoachPricing;
import com.softropic.skillars.platform.marketplace.repo.CoachPricingRepository;
import com.softropic.skillars.platform.marketplace.repo.SessionPack;
import com.softropic.skillars.platform.marketplace.repo.SessionPackRepository;
import com.softropic.skillars.platform.security.contract.exception.OperationNotAllowedException;
import com.softropic.skillars.platform.security.repo.PlayerProfile;
import com.softropic.skillars.platform.security.repo.PlayerProfileRepository;
import com.softropic.skillars.platform.security.repo.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
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
    private final ConfigService configService;
    private final UserRepository userRepository;

    // Lazy to break circular dependency: BookingService → SessionPackService → BookingService
    @Autowired
    @Lazy
    private BookingService bookingService;

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
        pack.setExpiresAt(computeExpiresAt(offered.getSessionCount(), Instant.now()));
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
        pack.setExpiresAt(computeExpiresAt(1, Instant.now()));
        return mapper.toResponse(repository.save(pack), coach.getDisplayName());
    }

    @Transactional(readOnly = true)
    public boolean hasCredits(Long playerId, UUID coachId) {
        int creditsRemaining = repository.sumActiveCredits(playerId, coachId, Instant.now());
        long inFlight = bookingRepository.countInFlightBookings(playerId, coachId);
        return (creditsRemaining - inFlight) > 0;
    }

    @Transactional(readOnly = true)
    public int getCreditsRemaining(Long playerId, UUID coachId) {
        return repository.sumActiveCredits(playerId, coachId, Instant.now());
    }

    // TODO(3.3): BookingService must only call deductCredit with playerId sourced from a verified booking
    // entity — never from raw user input — as this method does not re-verify parent ownership by design.
    @Transactional
    public void deductCredit(Long playerId, UUID coachId) {
        List<SessionPackPurchased> packs = repository.findActivePacksForDeduction(playerId, coachId, Instant.now());
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

    @Transactional
    public PauseConflictResponse pausePack(Long parentId, Long playerId, UUID packId, PausePackRequest req) {
        verifyPlayerOwnership(parentId, playerId);
        SessionPackPurchased pack = repository.findByIdForUpdate(packId)
            .orElseThrow(() -> new ResourceNotFoundException("Pack not found", "session_pack_purchased"));
        if (!Objects.equals(pack.getPlayerId(), playerId)) {
            throw new OperationNotAllowedException("Forbidden: pack not owned by this player", SecurityError.MISSING_RIGHTS);
        }
        if (!STATUS_ACTIVE.equals(pack.getStatus())) {
            throw new OperationNotAllowedException("Pack is not active", SecurityError.MISSING_RIGHTS);
        }
        // AC 10: one pause per pack lifetime
        if (pack.getPausedUntil() != null) {
            throw new BatchRuleViolationException("booking.packAlreadyPaused");
        }

        long maxDays = configService.getLong("pack.pause.maxDays");
        if (req.pauseDurationDays() < 1 || req.pauseDurationDays() > maxDays) {
            throw new BatchRuleViolationException("booking.pauseDurationInvalid");
        }

        Instant pauseStart = req.pauseStartDate();
        if (pauseStart.isBefore(Instant.now().truncatedTo(ChronoUnit.DAYS))) {
            throw new BatchRuleViolationException("booking.pauseStartInPast");
        }
        if (!pauseStart.isBefore(pack.getExpiresAt())) {
            throw new BatchRuleViolationException("booking.pauseStartAfterExpiry");
        }
        Instant pauseEnd = pauseStart.plus(Duration.ofDays(req.pauseDurationDays()));

        List<String> conflictStatuses = List.of("REQUESTED", "ACCEPTED", "CONFIRMED", "UPCOMING");
        List<Booking> conflicting = bookingRepository.findConflictingBookingsForPause(
            playerId, pack.getCoachId(), pauseStart, pauseEnd, conflictStatuses);

        List<UUID> confirmedIds = req.confirmedCancellationIds() != null
            ? req.confirmedCancellationIds() : List.of();

        // Step 1: conflicts exist and not yet confirmed → return list without applying
        if (!conflicting.isEmpty() && confirmedIds.isEmpty()) {
            List<ConflictingBookingItem> items = conflicting.stream()
                .map(b -> new ConflictingBookingItem(b.getId(), b.getRequestedStartTime(),
                    b.getRequestedEndTime(), b.getStatus(), b.getCanonicalTimezone()))
                .toList();
            return new PauseConflictResponse(false, items, null);
        }

        // Validate confirmedIds against live conflict set; collect times without N+1 queries
        Map<UUID, Instant> conflictMap = conflicting.stream()
            .collect(Collectors.toMap(Booking::getId, Booking::getRequestedStartTime));
        List<UUID> validatedIds = confirmedIds.stream()
            .filter(conflictMap::containsKey)
            .toList();
        List<Instant> cancelledTimes = validatedIds.stream()
            .map(conflictMap::get)
            .toList();
        for (UUID bookingId : validatedIds) {
            bookingService.cancelDueToPause(bookingId, pack.getCoachId(), parentId);
        }

        // Apply pause
        pack.setPausedUntil(pauseEnd);
        pack.setExpiresAt(pack.getExpiresAt().plus(Duration.ofDays(req.pauseDurationDays())));
        repository.save(pack);

        // Publish parent's single confirmation event
        CoachProfile coach = coachProfileRepository.findById(pack.getCoachId()).orElse(null);
        String parentEmail = userRepository.findById(parentId).map(u -> u.getEmail()).orElse("");
        String coachDisplayName = coach != null ? coach.getDisplayName() : "Coach";
        String canonicalTimezone = coach != null ? coach.getCanonicalTimezone() : "UTC";
        eventPublisher.publishEvent(new PackPausedEvent(
            this, pack.getId(), parentId, parentEmail, coachDisplayName,
            pack.getExpiresAt(), cancelledTimes, canonicalTimezone
        ));

        return new PauseConflictResponse(true, List.of(), pack.getExpiresAt());
    }

    private Instant computeExpiresAt(int sessionCount, Instant purchasedAt) {
        long days;
        if (sessionCount == 1) {
            days = configService.getLong("pack.expiry.days.tier1");
        } else if (sessionCount <= 5) {
            days = configService.getLong("pack.expiry.days.tier2");
        } else if (sessionCount <= 10) {
            days = configService.getLong("pack.expiry.days.tier3");
        } else {
            days = configService.getLong("pack.expiry.days.tier4");
        }
        return purchasedAt.plus(Duration.ofDays(days));
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
