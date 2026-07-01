package com.softropic.skillars.platform.booking.service;

import com.softropic.skillars.infrastructure.exception.ResourceNotFoundException;
import com.softropic.skillars.infrastructure.security.SecurityError;
import com.softropic.skillars.platform.booking.contract.BookingStatus;
import com.softropic.skillars.platform.booking.contract.CreateRescheduleRequest;
import com.softropic.skillars.platform.booking.contract.RescheduleAcceptedEvent;
import com.softropic.skillars.platform.booking.contract.RescheduleDeclinedEvent;
import com.softropic.skillars.platform.booking.contract.RescheduleRequestedEvent;
import com.softropic.skillars.platform.booking.repo.Booking;
import com.softropic.skillars.platform.booking.repo.BookingRepository;
import com.softropic.skillars.platform.booking.repo.BookingRescheduleRequest;
import com.softropic.skillars.platform.booking.repo.BookingRescheduleRequestRepository;
import com.softropic.skillars.platform.marketplace.repo.CoachProfile;
import com.softropic.skillars.platform.marketplace.repo.CoachProfileRepository;
import com.softropic.skillars.platform.security.contract.exception.OperationNotAllowedException;
import com.softropic.skillars.platform.security.repo.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class RescheduleService {

    private static final Set<String> RESCHEDULABLE_STATUSES = Set.of(
        BookingStatus.CONFIRMED.name(), BookingStatus.UPCOMING.name());

    private final BookingService bookingService;
    private final BookingRepository bookingRepository;
    private final BookingRescheduleRequestRepository rescheduleRepo;
    private final CoachProfileRepository coachProfileRepository;
    private final UserRepository userRepository;
    private final ApplicationEventPublisher eventPublisher;

    @Transactional
    public void requestReschedule(UUID bookingId, Long parentUserId, CreateRescheduleRequest req) {
        Booking booking = bookingService.getBookingOrThrow(bookingId);

        if (!booking.getParentId().equals(parentUserId)) {
            throw new OperationNotAllowedException("Parent does not own this booking", SecurityError.MISSING_RIGHTS);
        }
        if (!RESCHEDULABLE_STATUSES.contains(booking.getStatus())) {
            throw new OperationNotAllowedException(
                "Reschedule is only allowed for CONFIRMED or UPCOMING bookings", SecurityError.MISSING_RIGHTS);
        }
        if (!req.proposedStartTime().isAfter(Instant.now())) {
            throw new OperationNotAllowedException(
                "Proposed start time must be in the future", SecurityError.MISSING_RIGHTS);
        }
        if (!req.proposedEndTime().isAfter(req.proposedStartTime())) {
            throw new OperationNotAllowedException(
                "Proposed end time must be after start time", SecurityError.MISSING_RIGHTS);
        }
        rescheduleRepo.findFirstByBookingIdAndStatusOrderByCreatedAtDesc(bookingId, "PENDING")
            .ifPresent(existing -> {
                throw new OperationNotAllowedException(
                    "A pending reschedule request already exists", SecurityError.MISSING_RIGHTS);
            });

        BookingRescheduleRequest rescheduleRequest = new BookingRescheduleRequest();
        rescheduleRequest.setBookingId(bookingId);
        rescheduleRequest.setProposedBy("PARENT");
        rescheduleRequest.setProposedStartTime(req.proposedStartTime());
        rescheduleRequest.setProposedEndTime(req.proposedEndTime());
        rescheduleRepo.save(rescheduleRequest);

        CoachProfile coach = coachProfileRepository.findById(booking.getCoachId())
            .orElseThrow(() -> new ResourceNotFoundException("Coach profile not found", "coach_profile"));
        String coachEmail = resolveEmail(coach.getUserId(), bookingId);
        String parentName = userRepository.findById(parentUserId)
            .map(u -> u.getFirstName() + " " + u.getLastName())
            .orElse("Parent");

        eventPublisher.publishEvent(new RescheduleRequestedEvent(
            this, bookingId, coachEmail, parentName,
            booking.getRequestedStartTime(), req.proposedStartTime(),
            booking.getCanonicalTimezone()
        ));
        log.info("Reschedule requested for booking {} by parent {}", bookingId, parentUserId);
    }

    @Transactional
    public void acceptReschedule(UUID bookingId, UUID rescheduleId, Long coachUserId) {
        Booking booking = bookingService.getBookingOrThrow(bookingId);
        CoachProfile coach = coachProfileRepository.findByUserId(coachUserId)
            .orElseThrow(() -> new ResourceNotFoundException("Coach profile not found", "coach_profile"));

        if (!booking.getCoachId().equals(coach.getId())) {
            throw new OperationNotAllowedException("Coach does not own this booking", SecurityError.MISSING_RIGHTS);
        }

        BookingRescheduleRequest req = rescheduleRepo.findById(rescheduleId)
            .orElseThrow(() -> new ResourceNotFoundException("Reschedule request not found", "reschedule_request"));
        if (!req.getBookingId().equals(bookingId)) {
            throw new ResourceNotFoundException("Reschedule request not found", "reschedule_request");
        }
        if (!"PENDING".equals(req.getStatus())) {
            throw new OperationNotAllowedException(
                "Reschedule request is not in PENDING status", SecurityError.MISSING_RIGHTS);
        }
        if (!RESCHEDULABLE_STATUSES.contains(booking.getStatus())) {
            throw new OperationNotAllowedException(
                "Booking is no longer in a reschedulable state", SecurityError.MISSING_RIGHTS);
        }
        if (!req.getProposedStartTime().isAfter(Instant.now())) {
            throw new OperationNotAllowedException(
                "Proposed start time is no longer in the future", SecurityError.MISSING_RIGHTS);
        }

        booking.setRequestedStartTime(req.getProposedStartTime());
        booking.setRequestedEndTime(req.getProposedEndTime());
        bookingRepository.save(booking);
        req.setStatus("ACCEPTED");
        rescheduleRepo.save(req);

        String parentEmail = resolveEmail(booking.getParentId(), bookingId);
        String coachEmail = resolveEmail(coach.getUserId(), bookingId);

        eventPublisher.publishEvent(new RescheduleAcceptedEvent(
            this, bookingId, parentEmail, coachEmail, coach.getDisplayName(),
            req.getProposedStartTime(), booking.getCanonicalTimezone()
        ));
        log.info("Reschedule {} accepted for booking {} by coach {}", rescheduleId, bookingId, coachUserId);
    }

    @Transactional
    public void declineReschedule(UUID bookingId, UUID rescheduleId, Long coachUserId) {
        Booking booking = bookingService.getBookingOrThrow(bookingId);
        CoachProfile coach = coachProfileRepository.findByUserId(coachUserId)
            .orElseThrow(() -> new ResourceNotFoundException("Coach profile not found", "coach_profile"));

        if (!booking.getCoachId().equals(coach.getId())) {
            throw new OperationNotAllowedException("Coach does not own this booking", SecurityError.MISSING_RIGHTS);
        }

        BookingRescheduleRequest req = rescheduleRepo.findById(rescheduleId)
            .orElseThrow(() -> new ResourceNotFoundException("Reschedule request not found", "reschedule_request"));
        if (!req.getBookingId().equals(bookingId)) {
            throw new ResourceNotFoundException("Reschedule request not found", "reschedule_request");
        }
        if (!"PENDING".equals(req.getStatus())) {
            throw new OperationNotAllowedException(
                "Reschedule request is not in PENDING status", SecurityError.MISSING_RIGHTS);
        }

        req.setStatus("DECLINED");
        rescheduleRepo.save(req);

        String parentEmail = resolveEmail(booking.getParentId(), bookingId);
        eventPublisher.publishEvent(new RescheduleDeclinedEvent(
            this, bookingId, parentEmail, coach.getDisplayName(),
            booking.getRequestedStartTime(), booking.getCanonicalTimezone()
        ));
        log.info("Reschedule {} declined for booking {} by coach {}", rescheduleId, bookingId, coachUserId);
    }

    private String resolveEmail(Long userId, UUID bookingId) {
        return userRepository.findById(userId).map(u -> u.getEmail()).orElseGet(() -> {
            log.warn("Could not resolve email for userId={} bookingId={} — notification will be skipped", userId, bookingId);
            return "";
        });
    }
}
