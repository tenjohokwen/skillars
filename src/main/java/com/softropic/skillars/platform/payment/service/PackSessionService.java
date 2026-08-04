package com.softropic.skillars.platform.payment.service;

import com.softropic.skillars.infrastructure.security.SecurityError;
import com.softropic.skillars.platform.booking.contract.BatchRuleViolationException;
import com.softropic.skillars.platform.booking.contract.ConflictingBookingItem;
import com.softropic.skillars.platform.booking.contract.PackPausedEvent;
import com.softropic.skillars.platform.booking.contract.PauseConflictResponse;
import com.softropic.skillars.platform.booking.contract.PausePackRequest;
import com.softropic.skillars.platform.booking.contract.SessionPackExhaustedEvent;
import com.softropic.skillars.platform.booking.repo.Booking;
import com.softropic.skillars.platform.booking.repo.BookingRepository;
import com.softropic.skillars.platform.booking.service.BookingService;
import com.softropic.skillars.platform.config.service.ConfigService;
import com.softropic.skillars.platform.marketplace.repo.CoachProfile;
import com.softropic.skillars.platform.marketplace.repo.CoachProfileRepository;
import com.softropic.skillars.platform.payment.contract.exception.PaymentGatewayException;
import com.softropic.skillars.platform.payment.repo.SessionPackPurchase;
import com.softropic.skillars.platform.payment.repo.SessionPackPurchaseRepository;
import com.softropic.skillars.platform.security.contract.exception.OperationNotAllowedException;
import com.softropic.skillars.platform.security.repo.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class PackSessionService {

    private static final List<String> CONFLICT_STATUSES = List.of("REQUESTED", "ACCEPTED", "CONFIRMED", "UPCOMING");

    private final SessionPackPurchaseRepository sessionPackPurchaseRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final BookingRepository bookingRepository;
    private final BookingService bookingService;
    private final ConfigService configService;
    private final CoachProfileRepository coachProfileRepository;
    private final UserRepository userRepository;

    @Transactional
    public void deductSession(UUID purchaseId) {
        SessionPackPurchase purchase = sessionPackPurchaseRepository.findByIdForUpdate(purchaseId)
            .orElseThrow(() -> new PaymentGatewayException("payment.packNotFound"));

        if (purchase.getRemainingSessions() <= 0) {
            throw new PaymentGatewayException("payment.packExhausted");
        }

        purchase.setRemainingSessions(purchase.getRemainingSessions() - 1);
        sessionPackPurchaseRepository.save(purchase);

        if (purchase.getRemainingSessions() == 0) {
            log.info("Session pack exhausted: purchaseId={} parentId={}", purchaseId, purchase.getParentId());
            // Reuse existing event from booking.contract — do not create a duplicate
            eventPublisher.publishEvent(new SessionPackExhaustedEvent(
                this, purchaseId, purchase.getParentId(), purchase.getCoachId()));
        }
    }

    @Transactional
    public void restoreSession(UUID purchaseId) {
        SessionPackPurchase purchase = sessionPackPurchaseRepository.findByIdForUpdate(purchaseId)
            .orElseThrow(() -> new PaymentGatewayException("payment.packNotFound"));
        purchase.setRemainingSessions(purchase.getRemainingSessions() + 1);
        sessionPackPurchaseRepository.save(purchase);
        log.info("Session restored to pack: purchaseId={}", purchaseId);
    }

    @Transactional(readOnly = true)
    public boolean hasActivePack(Long playerId, UUID coachId) {
        return !sessionPackPurchaseRepository.findActivePacks(playerId, coachId, Instant.now()).isEmpty();
    }

    @Transactional(readOnly = true)
    public UUID getActivePackId(Long playerId, UUID coachId) {
        List<SessionPackPurchase> packs = sessionPackPurchaseRepository.findActivePacks(playerId, coachId, Instant.now());
        if (!packs.isEmpty()) return packs.get(0).getPurchaseId();
        return sessionPackPurchaseRepository.findTopByPlayerIdAndCoachIdOrderByCreatedAtDesc(playerId, coachId)
            .map(SessionPackPurchase::getPurchaseId)
            .orElse(null);
    }

    /**
     * Single-snapshot resolution of the active pack to attach to a new booking. Unlike the
     * hasActivePack()/getActivePackId() pair, this is one query — no window between an
     * existence check and a separate resolve where the pack could be consumed/expire, and no
     * unfiltered "most recent pack ever" fallback that could attach an exhausted/expired pack.
     */
    @Transactional(readOnly = true)
    public UUID findActivePackId(Long playerId, UUID coachId) {
        List<SessionPackPurchase> packs = sessionPackPurchaseRepository.findActivePacks(playerId, coachId, Instant.now());
        if (packs.isEmpty()) {
            throw new OperationNotAllowedException(
                "No effective session credits available for this coach", SecurityError.MISSING_RIGHTS);
        }
        return packs.get(0).getPurchaseId();
    }

    @Transactional
    public PauseConflictResponse pausePack(Long parentId, UUID purchaseId, PausePackRequest req) {
        SessionPackPurchase purchase = sessionPackPurchaseRepository.findByIdForUpdate(purchaseId)
            .orElseThrow(() -> new PaymentGatewayException("payment.packNotFound"));
        if (!Objects.equals(purchase.getParentId(), parentId)) {
            throw new OperationNotAllowedException("Parent does not own this session pack", SecurityError.MISSING_RIGHTS);
        }
        if (purchase.getRemainingSessions() <= 0 || purchase.getExpiresAt().isBefore(Instant.now())) {
            throw new OperationNotAllowedException("Pack is not active", SecurityError.MISSING_RIGHTS);
        }
        // AC 4: one pause per pack lifetime
        if (purchase.getPausedUntil() != null) {
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
        if (!pauseStart.isBefore(purchase.getExpiresAt())) {
            throw new BatchRuleViolationException("booking.pauseStartAfterExpiry");
        }
        Instant pauseEnd = pauseStart.plus(Duration.ofDays(req.pauseDurationDays()));

        Long playerId = purchase.getPlayerId();
        UUID coachId = purchase.getCoachId();
        List<Booking> conflicting = bookingRepository.findConflictingBookingsForPause(
            playerId, coachId, pauseStart, pauseEnd, CONFLICT_STATUSES);

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
            .distinct()
            .filter(conflictMap::containsKey)
            .toList();
        List<Instant> cancelledTimes = validatedIds.stream()
            .map(conflictMap::get)
            .toList();
        for (UUID bookingId : validatedIds) {
            bookingService.cancelDueToPause(bookingId, coachId, parentId);
        }

        // Apply pause
        purchase.setPausedUntil(pauseEnd);
        purchase.setExpiresAt(purchase.getExpiresAt().plus(Duration.ofDays(req.pauseDurationDays())));
        sessionPackPurchaseRepository.save(purchase);

        // Publish parent's single confirmation event
        CoachProfile coach = coachProfileRepository.findById(coachId).orElse(null);
        String parentEmail = userRepository.findById(parentId).map(u -> u.getEmail()).orElse("");
        String coachDisplayName = coach != null ? coach.getDisplayName() : "Coach";
        String canonicalTimezone = coach != null ? coach.getCanonicalTimezone() : "UTC";
        eventPublisher.publishEvent(new PackPausedEvent(
            this, purchase.getPurchaseId(), parentId, parentEmail, coachDisplayName,
            purchase.getExpiresAt(), cancelledTimes, canonicalTimezone
        ));

        return new PauseConflictResponse(true, List.of(), purchase.getExpiresAt());
    }
}
