package com.softropic.skillars.platform.payment.service;

import com.softropic.skillars.infrastructure.persistence.PessimisticLockRetryer;
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

import java.time.Clock;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.springframework.util.StringUtils.hasText;

@Service
@RequiredArgsConstructor
@Slf4j
public class PackSessionService {

    private static final List<String> CONFLICT_STATUSES = List.of("REQUESTED", "ACCEPTED", "CONFIRMED", "UPCOMING");
    private static final long DEFAULT_PACK_PAUSE_MAX_DAYS = 90L;

    private final SessionPackPurchaseRepository sessionPackPurchaseRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final BookingRepository bookingRepository;
    private final BookingService bookingService;
    private final ConfigService configService;
    private final CoachProfileRepository coachProfileRepository;
    private final UserRepository userRepository;
    private final PessimisticLockRetryer lockRetryer;
    private final Clock clock;

    @Transactional
    public void deductSession(UUID purchaseId) {
        SessionPackPurchase purchase = lockRetryer.withBoundedRetry(() -> sessionPackPurchaseRepository.findByIdForUpdate(purchaseId)
            .orElseThrow(() -> new PaymentGatewayException("payment.packNotFound")));

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
        SessionPackPurchase purchase = lockRetryer.withBoundedRetry(() -> sessionPackPurchaseRepository.findByIdForUpdate(purchaseId)
            .orElseThrow(() -> new PaymentGatewayException("payment.packNotFound")));
        purchase.setRemainingSessions(purchase.getRemainingSessions() + 1);
        sessionPackPurchaseRepository.save(purchase);
        log.info("Session restored to pack: purchaseId={}", purchaseId);
    }

    @Transactional(readOnly = true)
    public boolean hasActivePack(Long playerId, UUID coachId) {
        return !sessionPackPurchaseRepository.findActivePacks(playerId, coachId, Instant.now()).isEmpty();
    }

    // Story Deferred-75 AC6: batch equivalent of hasActivePack, replacing HomeworkAssignmentService
    // .getLockerRoomDrills's per-coach N+1 loop with one query.
    @Transactional(readOnly = true)
    public Set<UUID> hasActivePackForAnyOf(Long playerId, Set<UUID> coachIds) {
        if (coachIds.isEmpty()) return Set.of();
        if (coachIds.size() > 1000) {
            throw new IllegalArgumentException(
                "coachIds Set size " + coachIds.size() + " exceeds maximum of 1000. " +
                "Consider implementing chunked batch queries for large coach lists.");
        }
        return sessionPackPurchaseRepository.findCoachIdsWithActivePack(playerId, coachIds, Instant.now());
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
        SessionPackPurchase purchase = lockRetryer.withBoundedRetry(() -> sessionPackPurchaseRepository.findByIdForUpdate(purchaseId)
            .orElseThrow(() -> new PaymentGatewayException("payment.packNotFound")));
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

        // skillars-deferred-103 AC5: load coach early to get timezone for past-date check.
        // skillars-deferred-103 code-review P11: a missing coach here is a data-integrity failure,
        // not an authorization problem — session_pack_purchases.coach_id carries an FK
        // (fk_spp_coach), so the parent has rights and the referenced row has vanished. Throw
        // IllegalStateException so ApiAdvice maps it to 500 + ERROR alerting (see
        // ApiAdvice.illegalStateExceptionHandler), instead of a misleading 403 MISSING_RIGHTS.
        UUID coachId = purchase.getCoachId();
        CoachProfile coach = coachProfileRepository.findById(coachId)
            .orElseThrow(() -> new IllegalStateException(
                "Coach profile " + coachId + " referenced by session pack " + purchaseId
                    + " does not exist — data-integrity failure (FK fk_spp_coach)"));

        // skillars-deferred-103 AC6: defensive default for max pause days config
        long maxDays = configService.getLong("pack.pause.maxDays", DEFAULT_PACK_PAUSE_MAX_DAYS);
        if (req.pauseDurationDays() < 1 || req.pauseDurationDays() > maxDays) {
            throw new BatchRuleViolationException("booking.pauseDurationInvalid");
        }

        // skillars-deferred-103 AC5: timezone-aware past-date check. Resolve coach's zone;
        // fall back to UTC with a WARN for legacy rows whose canonicalTimezone is blank OR holds a
        // non-canonical / deprecated id that ZoneId.of() rejects (skillars-deferred-103 P6).
        Instant pauseStart = req.pauseStartDate();
        ZoneId zone = resolveCoachZone(coach.getCanonicalTimezone(), coachId);
        LocalDate pauseStartDate = LocalDate.ofInstant(pauseStart, zone);
        LocalDate todayInCoachZone = LocalDate.now(clock.withZone(zone));
        if (pauseStartDate.isBefore(todayInCoachZone)) {
            throw new BatchRuleViolationException("booking.pauseStartInPast");
        }
        if (!pauseStart.isBefore(purchase.getExpiresAt())) {
            throw new BatchRuleViolationException("booking.pauseStartAfterExpiry");
        }
        Instant pauseEnd = pauseStart.plus(Duration.ofDays(req.pauseDurationDays()));

        Long playerId = purchase.getPlayerId();
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

        // skillars-deferred-103 AC7: publish confirmation event only if parent email is available.
        // Coach is already loaded (AC5) and guaranteed non-null, so use it directly.
        String parentEmail = userRepository.findById(parentId)
            .map(u -> u.getEmail())
            .filter(email -> hasText(email))
            .orElse(null);
        if (parentEmail == null) {
            log.error("Pack pause notification skipped — parent email missing/blank: "
                + "parentId={} purchaseId={} coachId={}",
                parentId, purchaseId, coachId);
        } else {
            eventPublisher.publishEvent(new PackPausedEvent(
                this, purchase.getPurchaseId(), parentId, parentEmail, coach.getDisplayName(),
                purchase.getExpiresAt(), cancelledTimes, coach.getCanonicalTimezone()
            ));
        }

        return new PauseConflictResponse(true, List.of(), purchase.getExpiresAt());
    }

    /**
     * Resolves a coach's stored {@code canonicalTimezone} to a {@link ZoneId}, falling back to UTC
     * (with a WARN) when the value is blank or is not a zone id {@link ZoneId#of} accepts — legacy
     * rows can hold {@code ""}, {@code "PST"}, {@code "Europe/Nowhere"} or a deprecated id, none of
     * which should turn a pause request into a 500.
     */
    private ZoneId resolveCoachZone(String canonicalTimezone, UUID coachId) {
        if (hasText(canonicalTimezone)) {
            try {
                return ZoneId.of(canonicalTimezone);
            } catch (DateTimeException e) {
                log.warn("Coach profile has an unrecognised canonicalTimezone '{}', falling back to UTC: coachId={}",
                    canonicalTimezone, coachId);
                return ZoneOffset.UTC;
            }
        }
        log.warn("Coach profile has blank canonicalTimezone, falling back to UTC: coachId={}", coachId);
        return ZoneOffset.UTC;
    }
}
