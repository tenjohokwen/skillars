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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Lazy;
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
    // skillars-deferred-107 AC1: a stored pause window longer than 10 years is certainly a
    // fat-finger, not a real setting. Business cap; mirrors ConfigBounds.PACK_PAUSE_MAX_DAYS.
    private static final long MIN_PACK_PAUSE_MAX_DAYS = 1L;
    private static final long MAX_PACK_PAUSE_MAX_DAYS = 3650L;

    private final SessionPackPurchaseRepository sessionPackPurchaseRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final BookingRepository bookingRepository;
    private final BookingService bookingService;
    private final ConfigService configService;
    private final CoachProfileRepository coachProfileRepository;
    private final UserRepository userRepository;
    private final PessimisticLockRetryer lockRetryer;
    private final Clock clock;

    // skillars-deferred-136 AC3: field-injected (not constructor/final), lazy to break the
    // self-referential creation cycle — see pausePack's own Javadoc for why self-invocation is
    // required here (mirrors GdprErasureService's own identical field).
    @Autowired
    @Lazy
    private PackSessionService self;

    /** See {@link #pausePack}'s own Javadoc for why the D8 fix throws rather than returns. */
    private static final class PauseWindowConflictException extends RuntimeException {
        private final List<ConflictingBookingItem> conflicts;

        PauseWindowConflictException(List<ConflictingBookingItem> conflicts) {
            this.conflicts = conflicts;
        }
    }

    private static List<ConflictingBookingItem> toConflictingBookingItems(List<Booking> bookings) {
        return bookings.stream()
            .map(b -> new ConflictingBookingItem(b.getId(), b.getRequestedStartTime(),
                b.getRequestedEndTime(), b.getStatus(), b.getCanonicalTimezone()))
            .toList();
    }

    @Transactional
    public void deductSession(UUID purchaseId) {
        SessionPackPurchase purchase = lockRetryer.withBoundedRetry("PackSessionService.deductSession",
            () -> sessionPackPurchaseRepository.findByIdForUpdate(purchaseId)
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
        SessionPackPurchase purchase = lockRetryer.withBoundedRetry("PackSessionService.restoreSession",
            () -> sessionPackPurchaseRepository.findByIdForUpdate(purchaseId)
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

    /**
     * skillars-deferred-136 AC3: thin, non-transactional wrapper around {@link
     * #pausePackTransactional} — required so the D8 fix below can roll back this transaction's own
     * already-committed-within-it {@code cancelDueToPause} calls when a late conflict is found, while
     * still returning the SAME {@code PauseConflictResponse(false, items, null)} shape the client
     * already expects from the Step-1/D1 conflict paths (a normal return from an {@code @Transactional}
     * method commits everything written so far in it — only an exception crossing the proxy boundary
     * triggers rollback, and this method IS that boundary). Mirrors this codebase's own established
     * self-invocation split pattern (e.g. {@code GdprErasureService.erase}/{@code eraseTransactional}).
     */
    public PauseConflictResponse pausePack(Long parentId, UUID purchaseId, PausePackRequest req) {
        try {
            return self.pausePackTransactional(parentId, purchaseId, req);
        } catch (PauseWindowConflictException e) {
            return new PauseConflictResponse(false, e.conflicts, null);
        }
    }

    /**
     * skillars-deferred-136 AC3 (D5) — disclosed, not silent: the {@code session_pack_purchases} lock
     * below is deliberately held for the WHOLE method, matching the pre-existing shape, not narrowed
     * to just before the final write. Narrowing was investigated (this AC's own critical caveat) and
     * found safe against the specific lock-ordering hazard originally raised — {@code
     * BookingService.transition()} (reached via {@code cancelDueToPause} in the loop below) was read in
     * full and acquires exactly one lock, on the {@code booking} table's own single row, no others —
     * but narrowing would ALSO require moving the {@code purchase.getPausedUntil() != null} "one pause
     * per lifetime" check (currently read once, under this same lock, right after acquisition) to
     * immediately before the final write too, so a second concurrent {@code pausePack} racing on the
     * SAME purchase can't both pass that check under an unlocked read and only discover the conflict
     * after already cancelling bookings. That is a second, independent correctness surface beyond the
     * D8 booking-conflict recheck this AC already adds, and disproportionate to what D5 itself
     * (a contention/timing concern, not a functional bug) asked for. Left as-is.
     */
    @Transactional
    public PauseConflictResponse pausePackTransactional(Long parentId, UUID purchaseId, PausePackRequest req) {
        SessionPackPurchase purchase = lockRetryer.withBoundedRetry("PackSessionService.pausePack",
            () -> sessionPackPurchaseRepository.findByIdForUpdate(purchaseId)
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

        // skillars-deferred-103 AC6: defensive default for max pause days config.
        // skillars-deferred-107 AC1: also range-guard it — a stored 0/negative/absurd value would
        // otherwise be accepted verbatim and reject every pauseDurationDays >= 1 as
        // booking.pauseDurationInvalid with nothing pointing at the config. Clamps to 90 + WARN;
        // ConfigStartupAssertion additionally refuses to boot on a bad stored value (failFast key).
        long maxDays = configService.getBoundedLong("pack.pause.maxDays", DEFAULT_PACK_PAUSE_MAX_DAYS,
            MIN_PACK_PAUSE_MAX_DAYS, MAX_PACK_PAUSE_MAX_DAYS);
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
            return new PauseConflictResponse(false, toConflictingBookingItems(conflicting), null);
        }

        // Validate confirmedIds against live conflict set; collect times without N+1 queries
        Map<UUID, Instant> conflictMap = conflicting.stream()
            .collect(Collectors.toMap(Booking::getId, Booking::getRequestedStartTime));
        List<UUID> validatedIds = confirmedIds.stream()
            .distinct()
            .filter(conflictMap::containsKey)
            .toList();

        // skillars-deferred-136 AC3 (D1): validatedIds is confirmedIds FILTERED down to real
        // conflicts — it never checked the reverse, that EVERY real conflict was covered by
        // confirmedIds. A client that confirms only a subset (or a stale/racing request that omits
        // the field) would otherwise have that subset cancelled while the pause still applied
        // unconditionally, leaving the un-confirmed conflicting bookings sitting inside a now-paused
        // window with no further check. Same response shape as the original unconfirmed case (Step 1
        // above) — the client re-confirms against the same conflict list either way.
        if (!validatedIds.containsAll(conflictMap.keySet())) {
            return new PauseConflictResponse(false, toConflictingBookingItems(conflicting), null);
        }

        List<Instant> cancelledTimes = validatedIds.stream()
            .map(conflictMap::get)
            .toList();
        for (UUID bookingId : validatedIds) {
            bookingService.cancelDueToPause(bookingId, coachId, parentId);
        }

        // skillars-deferred-136 AC3 (D8): a booking created between the :195-196 conflict read (now
        // above) and this point — e.g. a brand-new createBookingRequest for the same coach/player/
        // window, which takes no session_pack_purchases lock — would never have been in confirmedIds
        // and would otherwise sit uncancelled inside the pause with no signal to the client. Re-run
        // the identical query under the SAME still-held lock immediately before the write below; any
        // id not already accounted for in the original conflict set must ABORT rather than return
        // normally — the cancelDueToPause calls just above already wrote to THIS transaction, and only
        // an exception crossing pausePackTransactional's own proxy boundary rolls them back (see
        // pausePack's own Javadoc). The caught exception is converted back to the identical
        // PauseConflictResponse(false, items, null) shape the client already expects.
        List<Booking> recheckConflicting = bookingRepository.findConflictingBookingsForPause(
            playerId, coachId, pauseStart, pauseEnd, CONFLICT_STATUSES);
        boolean hasNewConflict = recheckConflicting.stream()
            .anyMatch(b -> !conflictMap.containsKey(b.getId()));
        if (hasNewConflict) {
            throw new PauseWindowConflictException(toConflictingBookingItems(recheckConflicting));
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
