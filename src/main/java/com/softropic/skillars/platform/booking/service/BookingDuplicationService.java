package com.softropic.skillars.platform.booking.service;

import com.softropic.skillars.infrastructure.exception.ResourceNotFoundException;
import com.softropic.skillars.infrastructure.security.SecurityError;
import com.softropic.skillars.platform.booking.contract.BookingError;
import com.softropic.skillars.platform.booking.contract.DuplicateBookingProposedEvent;
import com.softropic.skillars.platform.booking.repo.Booking;
import com.softropic.skillars.platform.booking.repo.BookingRepository;
import com.softropic.skillars.platform.marketplace.repo.CoachAvailabilityWindow;
import com.softropic.skillars.platform.marketplace.repo.CoachAvailabilityWindowRepository;
import com.softropic.skillars.platform.marketplace.repo.CoachProfile;
import com.softropic.skillars.platform.marketplace.repo.CoachProfileRepository;
import com.softropic.skillars.platform.payment.service.PackSessionService;
import com.softropic.skillars.platform.security.contract.exception.OperationNotAllowedException;
import com.softropic.skillars.platform.security.repo.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class BookingDuplicationService {

    private final BookingService bookingService;
    private final BookingRepository bookingRepository;
    private final CoachProfileRepository coachProfileRepository;
    private final UserRepository userRepository;
    private final PackSessionService packSessionService;
    private final ApplicationEventPublisher eventPublisher;
    private final CoachAvailabilityWindowRepository coachAvailabilityWindowRepository;

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

        Instant newStart = original.getRequestedStartTime().plus(7, ChronoUnit.DAYS);
        Instant newEnd = original.getRequestedEndTime().plus(7, ChronoUnit.DAYS);

        if (!newStart.isAfter(Instant.now())) {
            throw new OperationNotAllowedException(
                "Proposed session time is in the past", SecurityError.MISSING_RIGHTS);
        }

        // Deferred-49 AC2: same current-availability enforcement as requestReschedule — a coach can
        // no longer repeat-next-week into a time they don't currently work, even if the original
        // booking was legitimate when made. Reuses BookingService's own package-private helper.
        List<CoachAvailabilityWindow> windows = coachAvailabilityWindowRepository.findByCoachId(coach.getId());
        if (!bookingService.isSlotWithinAvailabilityWindow(newStart, newEnd, windows)) {
            throw new OperationNotAllowedException(
                "Proposed slot is not within coach availability",
                Map.of("proposed start time", newStart, "proposed end time", newEnd),
                BookingError.SLOT_OUTSIDE_AVAILABILITY);
        }

        UUID packId = packSessionService.findActivePackId(original.getPlayerId(), original.getCoachId());

        Booking newBooking = new Booking();
        newBooking.setCoachId(original.getCoachId());
        newBooking.setPlayerId(original.getPlayerId());
        newBooking.setParentId(original.getParentId());
        newBooking.setCanonicalTimezone(original.getCanonicalTimezone());
        newBooking.setRequestedStartTime(newStart);
        newBooking.setRequestedEndTime(newEnd);
        newBooking.setSessionPackPurchaseId(packId);
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
}
