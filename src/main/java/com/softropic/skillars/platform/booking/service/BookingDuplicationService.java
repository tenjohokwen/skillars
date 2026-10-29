package com.softropic.skillars.platform.booking.service;

import com.softropic.skillars.infrastructure.exception.ResourceNotFoundException;
import com.softropic.skillars.infrastructure.persistence.PessimisticLockRetryer;
import com.softropic.skillars.infrastructure.security.SecurityError;
import com.softropic.skillars.platform.booking.contract.BookingError;
import com.softropic.skillars.platform.booking.contract.DuplicateBookingProposedEvent;
import com.softropic.skillars.platform.booking.repo.Booking;
import com.softropic.skillars.platform.booking.repo.BookingRepository;
import com.softropic.skillars.platform.marketplace.contract.CoachProfileStatus;
import com.softropic.skillars.platform.marketplace.repo.CoachAvailabilityWindow;
import com.softropic.skillars.platform.marketplace.repo.CoachAvailabilityWindowRepository;
import com.softropic.skillars.platform.marketplace.repo.CoachProfile;
import com.softropic.skillars.platform.marketplace.repo.CoachProfileRepository;
import com.softropic.skillars.infrastructure.util.ClockProvider;
import com.softropic.skillars.platform.payment.repo.SessionPackPurchase;
import com.softropic.skillars.platform.payment.repo.SessionPackPurchaseRepository;
import com.softropic.skillars.platform.security.contract.exception.OperationNotAllowedException;
import com.softropic.skillars.platform.security.repo.UserRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.DateTimeException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class BookingDuplicationService {

    private final BookingService bookingService;
    private final BookingRepository bookingRepository;
    private final CoachProfileRepository coachProfileRepository;
    private final UserRepository userRepository;
    private final SessionPackPurchaseRepository sessionPackPurchaseRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final CoachAvailabilityWindowRepository coachAvailabilityWindowRepository;
    private final EntityManager entityManager;
    private final PessimisticLockRetryer lockRetryer;

    @Transactional
    public UUID duplicateNextWeek(UUID originalBookingId, Long coachUserId) {
        Booking original = bookingService.getBookingOrThrow(originalBookingId);

        CoachProfile coach = coachProfileRepository.findByUserId(coachUserId)
            .orElseThrow(() -> new ResourceNotFoundException("Coach profile not found", "coach_profile"));

        if (!original.getCoachId().equals(coach.getId())) {
            throw new OperationNotAllowedException("Coach does not own this booking", SecurityError.MISSING_RIGHTS);
        }
        if (!"COMPLETED".equals(original.getStatus())) {
            throw new OperationNotAllowedException(
                "Can only duplicate a COMPLETED booking", SecurityError.MISSING_RIGHTS);
        }

        // Deferred-58 AC2: acceptReschedule already takes this same lock before its own availability
        // re-check; duplicateNextWeek never did, so a concurrent saveStep4 rewrite of this coach's windows
        // was never serialized against this method's read of them (or against its overlap check, which
        // otherwise only has V87's exclusion constraint as a commit-time backstop).
        lockRetryer.withBoundedRetry("BookingDuplicationService.duplicateNextWeek", () -> {
            coachProfileRepository.findByIdForUpdate(coach.getId())
                .orElseThrow(() -> new ResourceNotFoundException("Coach profile not found", "coach_profile"));
            entityManager.refresh(coach, LockModeType.PESSIMISTIC_WRITE);
            return null;
        });

        // Deferred-63 AC2: mirrors acceptReschedule's identical check on its identical lock pattern —
        // a coach suspended since the original booking completed must not be able to duplicate it.
        if (coach.getStatus() == CoachProfileStatus.SUSPENDED) {
            throw new OperationNotAllowedException("Coach is suspended",
                Map.of("submitted coach id", coach.getId()), BookingError.COACH_UNAVAILABLE);
        }
        // B2: this also creates a booking for the player, so it gets the same erasure guard as
        // BookingService.createBookingRequest — coach row lock first, then the player row lock.
        bookingService.requirePlayerBookableUnderLock(original.getPlayerId(),
            "BookingDuplicationService.duplicateNextWeek");

        // fix/booking-completion-integrity: "same time next week" in the booking's own zone, not a fixed
        // 7 x 24h — across a DST change that landed the duplicate an hour off in local wall-clock time
        // (and usually outside the coach's availability window).
        ZoneId zone = bookingZone(original.getCanonicalTimezone());
        Instant newStart = original.getRequestedStartTime().atZone(zone).plusWeeks(1).toInstant();
        Instant newEnd = original.getRequestedEndTime().atZone(zone).plusWeeks(1).toInstant();

        if (!newStart.isAfter(Instant.now())) {
            throw new OperationNotAllowedException(
                "Proposed session time is in the past", SecurityError.MISSING_RIGHTS);
        }

        // Deferred-49 AC2: same current-availability enforcement as requestReschedule — a coach can
        // no longer repeat-next-week into a time they don't currently work, even if the original
        // booking was legitimate when made. Reuses BookingService's own package-private helper.
        List<CoachAvailabilityWindow> windows = coachAvailabilityWindowRepository.findByCoachIdOrderByDayOfWeekAscStartTimeAscIdAsc(coach.getId());
        bookingService.requireSlotWithinAvailabilityWindow(newStart, newEnd, windows,
            coach.getId(), coach.getCanonicalTimezone(),
            "Proposed slot is not within coach availability",
            Map.of("proposed start time", newStart, "proposed end time", newEnd));

        // Deferred-79 AC1: this method creates exactly one duplicate per invocation — no loop over
        // multiple weeks/occurrences — so the check goes at this single point, once per call.
        if (bookingService.isSlotBlocked(newStart, newEnd, coach.getId())) {
            throw new OperationNotAllowedException(
                "Coach has blocked out this time",
                Map.of("proposed start time", newStart, "proposed end time", newEnd),
                BookingError.SLOT_BLOCKED_BY_COACH);
        }

        // Deferred-50 AC1: duplicateNextWeek previously relied solely on the DB-level exclusion
        // constraint at commit to catch a double-booking, surfacing as an unmapped 500 rather than a
        // clean rejection. Mirrors acceptReschedule's own overlap check (Deferred-14 AC4) — same
        // repository method, status list, and error code. excludeBookingId is null: unlike a
        // reschedule, this new booking doesn't exist yet, so there is nothing to exclude.
        List<Booking> overlapping = bookingRepository.findOverlappingBookings(
            coach.getId(), newStart, newEnd,
            BookingService.ACTIVE_SLOT_STATUSES_EXCLUDING_REQUESTED, null);
        if (!overlapping.isEmpty()) {
            throw new OperationNotAllowedException(
                "The proposed slot is no longer available — another booking occupies that time",
                Map.of("coach id", coach.getId(), "proposed start time", newStart, "proposed end time", newEnd),
                BookingError.SLOT_UNAVAILABLE);
        }

        // fix/booking-request-consent (packs-subs L2): PackSessionService.findActivePackId threw when the player
        // had no active pack, so a card-paid session could never be repeated (the price-lock branch below was
        // dead), and it matched on the player only, so a pack bought by another parent of the same player
        // could fund this parent's booking. Now: the earliest-expiring active pack of THIS booking's parent
        // (same order as findActivePackId), else the coach's price, locked now.
        UUID packId = resolveParentsActivePackId(original);

        Booking newBooking = new Booking();
        newBooking.setCoachId(original.getCoachId());
        newBooking.setPlayerId(original.getPlayerId());
        newBooking.setParentId(original.getParentId());
        newBooking.setCanonicalTimezone(original.getCanonicalTimezone());
        newBooking.setRequestedStartTime(newStart);
        newBooking.setRequestedEndTime(newEnd);
        newBooking.setSessionPackPurchaseId(packId);
        // fix/booking-request-consent (H3): stays REQUESTED until the parent confirms it (accept + charge) or
        // declines it; the coach cannot accept a booking they created (BookingService.acceptAndInitiatePayment).
        newBooking.setProposedByCoach(true);
        // fix/payment-agreed-price-lock: a non-pack duplicate locks the coach's price as of the proposal.
        // fix/booking-price-and-excused-race: refused (COACH_PRICING_UNAVAILABLE) when the coach has none.
        if (packId == null) {
            newBooking.setAgreedPrice(bookingService.requireRequestTimePrice(original.getCoachId()));
        }
        bookingRepository.save(newBooking);

        String parentEmail = userRepository.findById(original.getParentId())
            .map(u -> u.getEmail())
            .orElseGet(() -> {
                log.warn("Could not resolve email for userId={} bookingId={} — notification will be skipped",
                        original.getParentId(), newBooking.getId());
                return "";
            });

        eventPublisher.publishEvent(new DuplicateBookingProposedEvent(
            this, newBooking.getId(), parentEmail, coach.getDisplayName(),
            newStart, original.getCanonicalTimezone()
        ));
        log.info("Duplicated booking {} → new booking {} (coach {})", originalBookingId, newBooking.getId(), coachUserId);
        return newBooking.getId();
    }

    private UUID resolveParentsActivePackId(Booking original) {
        return sessionPackPurchaseRepository
            .findActivePacks(original.getPlayerId(), original.getCoachId(), Instant.now(ClockProvider.getClock()))
            .stream()
            .filter(p -> Objects.equals(p.getParentId(), original.getParentId()))
            .map(SessionPackPurchase::getPurchaseId)
            .findFirst()
            .orElse(null);
    }

    /** The booking's stamped zone; UTC when it is missing or invalid (pre-validation rows). */
    private static ZoneId bookingZone(String canonicalTimezone) {
        if (canonicalTimezone == null || canonicalTimezone.isBlank()) {
            return ZoneOffset.UTC;
        }
        try {
            return ZoneId.of(canonicalTimezone);
        } catch (DateTimeException e) {
            return ZoneOffset.UTC;
        }
    }
}
