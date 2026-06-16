package com.softropic.skillars.platform.booking.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.softropic.skillars.infrastructure.exception.ResourceNotFoundException;
import com.softropic.skillars.infrastructure.security.SecurityError;
import com.softropic.skillars.platform.booking.contract.ActorRole;
import com.softropic.skillars.platform.booking.contract.BookingCompletedEvent;
import com.softropic.skillars.platform.booking.contract.BookingEvent;
import com.softropic.skillars.platform.booking.contract.BookingStatus;
import com.softropic.skillars.platform.booking.contract.QuickCompleteConfirmationRequiredEvent;
import com.softropic.skillars.platform.booking.contract.TransitionContext;
import com.softropic.skillars.platform.booking.contract.WrapUpRequest;
import com.softropic.skillars.platform.booking.repo.Booking;
import com.softropic.skillars.platform.booking.repo.SessionCompletionData;
import com.softropic.skillars.platform.booking.repo.SessionCompletionDataRepository;
import com.softropic.skillars.platform.marketplace.repo.CoachProfile;
import com.softropic.skillars.platform.marketplace.repo.CoachProfileRepository;
import com.softropic.skillars.platform.security.contract.exception.OperationNotAllowedException;
import com.softropic.skillars.platform.security.repo.PlayerProfile;
import com.softropic.skillars.platform.security.repo.PlayerProfileRepository;
import com.softropic.skillars.platform.security.repo.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class BookingCompletionService {

    private final BookingService bookingService;
    private final SessionPackService sessionPackService;
    private final SessionCompletionDataRepository completionDataRepository;
    private final CoachProfileRepository coachProfileRepository;
    private final UserRepository userRepository;
    private final PlayerProfileRepository playerProfileRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final ObjectMapper objectMapper;

    @Transactional
    public void startSession(UUID bookingId, Long coachUserId) {
        CoachProfile coach = resolveCoach(coachUserId);
        Booking booking = bookingService.getBookingOrThrow(bookingId);
        verifyCoachOwnership(booking, coach);
        verifyStatus(booking, BookingStatus.UPCOMING);
        bookingService.transition(bookingId, BookingEvent.START, new TransitionContext(ActorRole.COACH, coachUserId));
    }

    @Transactional
    public void endSession(UUID bookingId, Long coachUserId) {
        CoachProfile coach = resolveCoach(coachUserId);
        Booking booking = bookingService.getBookingOrThrow(bookingId);
        verifyCoachOwnership(booking, coach);
        BookingStatus current = BookingStatus.valueOf(booking.getStatus());
        if (current != BookingStatus.IN_PROGRESS && current != BookingStatus.PAUSED) {
            throw new OperationNotAllowedException(
                "Booking is in status " + current + ", expected IN_PROGRESS or PAUSED",
                SecurityError.MISSING_RIGHTS);
        }
        try {
            bookingService.transition(bookingId, BookingEvent.COMPLETE_PENDING, new TransitionContext(ActorRole.COACH, coachUserId));
        } catch (OptimisticLockingFailureException e) {
            throw new OperationNotAllowedException("Booking status changed concurrently — retry", SecurityError.MISSING_RIGHTS);
        }
    }

    @Transactional
    public void pauseSession(UUID bookingId, Long coachUserId) {
        CoachProfile coach = resolveCoach(coachUserId);
        Booking booking = bookingService.getBookingOrThrow(bookingId);
        verifyCoachOwnership(booking, coach);
        verifyStatus(booking, BookingStatus.IN_PROGRESS);
        try {
            bookingService.transition(bookingId, BookingEvent.PAUSE, new TransitionContext(ActorRole.COACH, coachUserId));
        } catch (OptimisticLockingFailureException e) {
            throw new OperationNotAllowedException("Booking status changed concurrently — retry", SecurityError.MISSING_RIGHTS);
        }
    }

    @Transactional
    public void resumeSession(UUID bookingId, Long coachUserId) {
        CoachProfile coach = resolveCoach(coachUserId);
        Booking booking = bookingService.getBookingOrThrow(bookingId);
        verifyCoachOwnership(booking, coach);
        verifyStatus(booking, BookingStatus.PAUSED);
        try {
            bookingService.transition(bookingId, BookingEvent.RESUME, new TransitionContext(ActorRole.COACH, coachUserId));
        } catch (OptimisticLockingFailureException e) {
            throw new OperationNotAllowedException("Booking status changed concurrently — retry", SecurityError.MISSING_RIGHTS);
        }
    }

    @Transactional
    public void initiateQuickComplete(UUID bookingId, Long coachUserId) {
        CoachProfile coach = resolveCoach(coachUserId);
        Booking booking = bookingService.getBookingOrThrow(bookingId);
        verifyCoachOwnership(booking, coach);
        BookingStatus current = BookingStatus.valueOf(booking.getStatus());
        if (current != BookingStatus.UPCOMING) {
            throw new OperationNotAllowedException(
                "Quick Complete requires an UPCOMING booking", SecurityError.MISSING_RIGHTS);
        }
        bookingService.transition(bookingId, BookingEvent.COMPLETE_PENDING, new TransitionContext(ActorRole.COACH, coachUserId));
    }

    @Transactional
    public void submitWrapUp(UUID bookingId, Long coachUserId, WrapUpRequest req) {
        CoachProfile coach = resolveCoach(coachUserId);
        Booking booking = bookingService.getBookingOrThrow(bookingId);
        verifyCoachOwnership(booking, coach);
        verifyStatus(booking, BookingStatus.COMPLETED_PENDING_CONFIRMATION);

        SessionCompletionData scd = new SessionCompletionData();
        scd.setBookingId(bookingId);
        scd.setCoachId(coach.getId());
        scd.setPlayerId(booking.getPlayerId());
        scd.setPlayerAttended(Boolean.TRUE.equals(req.playerAttended()));
        scd.setEffortRating(req.effortRating());
        scd.setFocusRating(req.focusRating());
        scd.setTechniqueRating(req.techniqueRating());
        scd.setVoiceNoteText(req.voiceNoteText());
        scd.setCompletionMode(req.mode());

        if (req.homeworkDrillIds() != null && !req.homeworkDrillIds().isEmpty()) {
            try {
                scd.setHomeworkDrillIds(objectMapper.writeValueAsString(req.homeworkDrillIds()));
            } catch (JsonProcessingException e) {
                log.warn("Failed to serialize homeworkDrillIds for booking {}", bookingId, e);
            }
        }
        try {
            completionDataRepository.save(scd);
        } catch (DataIntegrityViolationException e) {
            // Idempotency guard: a previous request already saved completion data for this booking
            log.warn("Session completion data already exists for booking {} — ignoring duplicate submitWrapUp", bookingId);
            return;
        }

        if ("LIVE".equals(req.mode())) {
            bookingService.transition(bookingId, BookingEvent.QUICK_COMPLETE, new TransitionContext(ActorRole.COACH, coachUserId));
            sessionPackService.deductCredit(booking.getPlayerId(), booking.getCoachId());
            eventPublisher.publishEvent(new BookingCompletedEvent(
                this, bookingId, booking.getCoachId(), booking.getPlayerId(), booking.getParentId(),
                scd.isPlayerAttended(), req.effortRating(), req.focusRating(), req.techniqueRating(),
                req.homeworkDrillIds() != null ? req.homeworkDrillIds() : List.of()
            ));
        } else {
            String parentEmail = userRepository.findById(booking.getParentId())
                .map(u -> u.getEmail()).orElse("");
            String coachDisplayName = coach.getDisplayName();
            String playerName = playerProfileRepository.findById(booking.getPlayerId())
                .map(PlayerProfile::getName).orElse("Player");
            eventPublisher.publishEvent(new QuickCompleteConfirmationRequiredEvent(
                this, bookingId, booking.getParentId(), parentEmail, coachDisplayName,
                booking.getRequestedStartTime(), booking.getCanonicalTimezone(), playerName
            ));
        }
    }

    @Transactional
    public void confirmCompletion(UUID bookingId, Long parentUserId) {
        Booking booking = bookingService.getBookingOrThrow(bookingId);
        if (!Objects.equals(booking.getParentId(), parentUserId)) {
            throw new ResourceNotFoundException("Booking not found", "booking");
        }
        verifyStatus(booking, BookingStatus.COMPLETED_PENDING_CONFIRMATION);
        try {
            bookingService.transition(bookingId, BookingEvent.COMPLETE, new TransitionContext(ActorRole.PARENT, parentUserId));
        } catch (OptimisticLockingFailureException e) {
            throw new OperationNotAllowedException("Session already confirmed", SecurityError.MISSING_RIGHTS);
        }

        SessionCompletionData scd = completionDataRepository.findByBookingId(bookingId)
            .orElseThrow(() -> new ResourceNotFoundException("Session completion data not found", "session_completion_data"));
        sessionPackService.deductCredit(booking.getPlayerId(), booking.getCoachId());

        List<UUID> homeworkDrillIds = deserializeHomeworkDrillIds(scd.getHomeworkDrillIds());
        eventPublisher.publishEvent(new BookingCompletedEvent(
            this, bookingId, booking.getCoachId(), booking.getPlayerId(), booking.getParentId(),
            scd.isPlayerAttended(), scd.getEffortRating(), scd.getFocusRating(), scd.getTechniqueRating(),
            homeworkDrillIds
        ));
    }

    @SuppressWarnings("unchecked")
    private List<UUID> deserializeHomeworkDrillIds(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            List<String> ids = objectMapper.readValue(json, List.class);
            return ids.stream().map(UUID::fromString).toList();
        } catch (Exception e) {
            log.warn("Failed to deserialize homeworkDrillIds from JSON '{}': {}", json, e.getMessage());
            return List.of();
        }
    }

    private CoachProfile resolveCoach(Long coachUserId) {
        return coachProfileRepository.findByUserId(coachUserId)
            .orElseThrow(() -> new ResourceNotFoundException("Coach profile not found", "coach_profile"));
    }

    private void verifyCoachOwnership(Booking booking, CoachProfile coach) {
        if (!Objects.equals(booking.getCoachId(), coach.getId())) {
            throw new OperationNotAllowedException("Coach does not own this booking", SecurityError.MISSING_RIGHTS);
        }
    }

    private void verifyStatus(Booking booking, BookingStatus expected) {
        BookingStatus current = BookingStatus.valueOf(booking.getStatus());
        if (current != expected) {
            throw new OperationNotAllowedException(
                "Booking is in status " + current + ", expected " + expected, SecurityError.MISSING_RIGHTS);
        }
    }
}
