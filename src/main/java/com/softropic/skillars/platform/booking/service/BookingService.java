package com.softropic.skillars.platform.booking.service;

import com.softropic.skillars.infrastructure.exception.ResourceNotFoundException;
import com.softropic.skillars.infrastructure.security.SecurityError;
import com.softropic.skillars.platform.booking.contract.BookingConfirmedEvent;
import com.softropic.skillars.platform.booking.contract.BookingDeclinedEvent;
import com.softropic.skillars.platform.booking.contract.BookingRequestedEvent;
import com.softropic.skillars.platform.booking.contract.BookingResponse;
import com.softropic.skillars.platform.booking.contract.CreateBookingRequest;
import com.softropic.skillars.platform.booking.repo.Booking;
import com.softropic.skillars.platform.booking.repo.BookingRepository;
import com.softropic.skillars.platform.marketplace.contract.CoachProfileStatus;
import com.softropic.skillars.platform.marketplace.repo.CoachAvailabilityWindow;
import com.softropic.skillars.platform.marketplace.repo.CoachAvailabilityWindowRepository;
import com.softropic.skillars.platform.marketplace.repo.CoachProfile;
import com.softropic.skillars.platform.marketplace.repo.CoachProfileRepository;
import com.softropic.skillars.platform.security.contract.exception.OperationNotAllowedException;
import com.softropic.skillars.platform.security.repo.PlayerProfile;
import com.softropic.skillars.platform.security.repo.PlayerProfileRepository;
import com.softropic.skillars.platform.security.repo.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.authentication.InsufficientAuthenticationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.DayOfWeek;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class BookingService {

    private static final Map<String, Set<String>> ALLOWED_TRANSITIONS = Map.of(
        "REQUESTED", Set.of("ACCEPTED", "DECLINED"),
        "ACCEPTED",  Set.of("CONFIRMED"),
        "CONFIRMED", Set.of("UPCOMING"),
        "UPCOMING",  Set.of()
    );

    private final BookingRepository bookingRepository;
    private final SessionPackService sessionPackService;
    private final CoachProfileRepository coachProfileRepository;
    private final CoachAvailabilityWindowRepository coachAvailabilityWindowRepository;
    private final PlayerProfileRepository playerProfileRepository;
    private final UserRepository userRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final com.softropic.skillars.platform.booking.repo.SessionPackPurchasedRepository sessionPackPurchasedRepository;

    @Transactional
    public BookingResponse createBookingRequest(Long parentId, CreateBookingRequest req) {
        PlayerProfile player = playerProfileRepository.findById(req.playerId())
            .orElseThrow(() -> new ResourceNotFoundException("Player not found", "player_profile"));
        if (!Objects.equals(player.getParentId(), parentId)) {
            throw new OperationNotAllowedException("Parent does not own this player", SecurityError.MISSING_RIGHTS);
        }

        CoachProfile coach = coachProfileRepository.findById(req.coachId())
            .orElseThrow(() -> new ResourceNotFoundException("Coach profile not found", "coach_profile"));
        if (coach.getStatus() != CoachProfileStatus.ACTIVE) {
            throw new OperationNotAllowedException("Coach profile is not active", SecurityError.MISSING_RIGHTS);
        }

        if (!req.requestedStartTime().isAfter(Instant.now())) {
            throw new OperationNotAllowedException("Requested start time must be in the future", SecurityError.MISSING_RIGHTS);
        }
        if (!req.requestedEndTime().isAfter(req.requestedStartTime())) {
            throw new OperationNotAllowedException("Requested end time must be after start time", SecurityError.MISSING_RIGHTS);
        }

        List<CoachAvailabilityWindow> windows = coachAvailabilityWindowRepository.findByCoachId(req.coachId());
        if (!isSlotWithinAvailabilityWindow(req.requestedStartTime(), req.requestedEndTime(), windows)) {
            throw new OperationNotAllowedException("Requested slot is not within coach availability", SecurityError.MISSING_RIGHTS);
        }

        // Acquire pessimistic lock on pack rows before credit check to prevent concurrent double-booking
        sessionPackPurchasedRepository.findActivePacksForDeduction(req.playerId(), req.coachId());
        if (!sessionPackService.hasCredits(req.playerId(), req.coachId())) {
            throw new OperationNotAllowedException("No effective session credits available for this coach", SecurityError.MISSING_RIGHTS);
        }

        Booking booking = new Booking();
        booking.setParentId(parentId);
        booking.setPlayerId(req.playerId());
        booking.setCoachId(req.coachId());
        booking.setRequestedStartTime(req.requestedStartTime());
        booking.setRequestedEndTime(req.requestedEndTime());
        booking.setCanonicalTimezone(req.canonicalTimezone());
        booking.setNotes(req.notes());
        bookingRepository.save(booking);

        String coachEmail = resolveEmail(coach.getUserId());
        eventPublisher.publishEvent(new BookingRequestedEvent(
            this, booking.getId(), parentId, req.playerId(), req.coachId(),
            coach.getDisplayName(), coachEmail, req.notes(),
            req.requestedStartTime(), req.canonicalTimezone()
        ));

        int effectiveCredits = (int) (sessionPackService.getCreditsRemaining(req.playerId(), req.coachId())
            - bookingRepository.countInFlightBookings(req.playerId(), req.coachId()));
        return toResponse(booking, coach.getDisplayName(), player.getName(), null, effectiveCredits);
    }

    @Transactional
    public BookingResponse acceptBooking(UUID bookingId, Long coachUserId) {
        Booking booking = bookingRepository.findById(bookingId)
            .orElseThrow(() -> new ResourceNotFoundException("Booking not found", "booking"));

        CoachProfile coach = coachProfileRepository.findByUserId(coachUserId)
            .orElseThrow(() -> new ResourceNotFoundException("Coach profile not found", "coach_profile"));
        if (!Objects.equals(booking.getCoachId(), coach.getId())) {
            throw new OperationNotAllowedException("Coach does not own this booking", SecurityError.MISSING_RIGHTS);
        }

        validateTransition(booking.getStatus(), "ACCEPTED");
        booking.setStatus("ACCEPTED");
        validateTransition("ACCEPTED", "CONFIRMED");
        booking.setStatus("CONFIRMED");
        bookingRepository.save(booking);

        String parentEmail = resolveEmail(booking.getParentId());
        eventPublisher.publishEvent(new BookingConfirmedEvent(
            this, booking.getId(), booking.getParentId(), parentEmail,
            coach.getDisplayName(), booking.getRequestedStartTime(), booking.getCanonicalTimezone()
        ));

        return toResponse(booking, coach.getDisplayName(), resolvePlayerName(booking.getPlayerId()), null, 0);
    }

    @Transactional
    public void declineBooking(UUID bookingId, Long coachUserId) {
        Booking booking = bookingRepository.findById(bookingId)
            .orElseThrow(() -> new ResourceNotFoundException("Booking not found", "booking"));

        CoachProfile coach = coachProfileRepository.findByUserId(coachUserId)
            .orElseThrow(() -> new ResourceNotFoundException("Coach profile not found", "coach_profile"));
        if (!Objects.equals(booking.getCoachId(), coach.getId())) {
            throw new OperationNotAllowedException("Coach does not own this booking", SecurityError.MISSING_RIGHTS);
        }

        validateTransition(booking.getStatus(), "DECLINED");
        booking.setStatus("DECLINED");
        bookingRepository.save(booking);

        eventPublisher.publishEvent(new BookingDeclinedEvent(
            this, booking.getId(), booking.getParentId(),
            resolveEmail(booking.getParentId()),
            coach.getDisplayName(), booking.getRequestedStartTime(),
            booking.getCanonicalTimezone()
        ));
    }

    @Transactional(readOnly = true)
    public List<BookingResponse> getParentBookings(Long parentId) {
        List<Booking> bookings = bookingRepository.findAllByParentIdOrderByRequestedStartTimeAsc(parentId);

        Set<UUID> coachIds = bookings.stream().map(Booking::getCoachId).collect(Collectors.toSet());
        Map<UUID, String> coachNames = coachProfileRepository.findAllById(coachIds).stream()
            .collect(Collectors.toMap(CoachProfile::getId, CoachProfile::getDisplayName));

        return bookings.stream().map(b -> {
            String coachName = coachNames.getOrDefault(b.getCoachId(), "Unknown Coach");
            String playerName = resolvePlayerName(b.getPlayerId());
            int effectiveCredits = (int) (sessionPackService.getCreditsRemaining(b.getPlayerId(), b.getCoachId())
                - bookingRepository.countInFlightBookings(b.getPlayerId(), b.getCoachId()));
            return toResponse(b, coachName, playerName, null, effectiveCredits);
        }).toList();
    }

    @Transactional(readOnly = true)
    public List<BookingResponse> getCoachBookingRequests(Long coachUserId) {
        CoachProfile coach = coachProfileRepository.findByUserId(coachUserId)
            .orElseThrow(() -> new ResourceNotFoundException("Coach profile not found", "coach_profile"));

        List<Booking> bookings = bookingRepository
            .findByCoachIdAndStatusOrderByRequestedStartTimeAsc(coach.getId(), "REQUESTED");

        return bookings.stream().map(b -> {
            String playerName = resolvePlayerName(b.getPlayerId());
            String parentName = resolveParentName(b.getParentId());
            return toResponse(b, coach.getDisplayName(), playerName, parentName, 0);
        }).toList();
    }

    private void validateTransition(String from, String to) {
        Set<String> allowed = ALLOWED_TRANSITIONS.getOrDefault(from, Set.of());
        if (!allowed.contains(to)) {
            throw new OperationNotAllowedException(
                "Invalid booking transition: " + from + " → " + to, SecurityError.MISSING_RIGHTS);
        }
    }

    private String resolveEmail(Long userId) {
        return userRepository.findById(userId).map(u -> u.getEmail()).orElse("");
    }

    private String resolvePlayerName(Long playerId) {
        return playerProfileRepository.findById(playerId).map(PlayerProfile::getName).orElse("Unknown Player");
    }

    private String resolveParentName(Long parentId) {
        return userRepository.findById(parentId)
            .map(u -> u.getFirstName() + " " + u.getLastName())
            .orElse("Unknown Parent");
    }

    private boolean isSlotWithinAvailabilityWindow(Instant startTime, Instant endTime,
                                                    List<CoachAvailabilityWindow> windows) {
        for (CoachAvailabilityWindow w : windows) {
            ZoneId zoneId;
            try {
                zoneId = ZoneId.of(w.getCanonicalTimezone());
            } catch (DateTimeException e) {
                log.warn("Availability window {} has invalid timezone '{}' — skipping",
                    w.getId(), w.getCanonicalTimezone());
                continue;
            }

            ZonedDateTime startZdt = startTime.atZone(zoneId);
            ZonedDateTime endZdt = endTime.atZone(zoneId);

            int requestedDow = startZdt.getDayOfWeek().getValue();
            LocalTime requestedStart = startZdt.toLocalTime();
            LocalTime requestedEnd = endZdt.toLocalTime();

            if (w.getDayOfWeek() == (short) requestedDow
                && !requestedStart.isBefore(w.getStartTime())
                && !requestedEnd.isAfter(w.getEndTime())) {
                return true;
            }
        }
        return false;
    }

    private BookingResponse toResponse(Booking b, String coachName, String playerName, String parentName, int effectiveCredits) {
        return new BookingResponse(
            b.getId(),
            b.getPlayerId(),
            playerName,
            b.getCoachId(),
            coachName,
            b.getRequestedStartTime(),
            b.getRequestedEndTime(),
            b.getStatus(),
            b.getCanonicalTimezone(),
            b.getNotes(),
            b.getCreatedAt(),
            parentName,
            effectiveCredits
        );
    }
}
