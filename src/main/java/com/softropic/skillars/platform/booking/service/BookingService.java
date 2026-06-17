package com.softropic.skillars.platform.booking.service;

import com.softropic.skillars.infrastructure.exception.ResourceNotFoundException;
import com.softropic.skillars.infrastructure.security.SecurityError;
import com.softropic.skillars.platform.booking.contract.ActorRole;
import com.softropic.skillars.platform.booking.contract.BatchGroupedBookingResponse;
import com.softropic.skillars.platform.booking.contract.BookingCancelledDueToPauseEvent;
import com.softropic.skillars.platform.booking.contract.BookingConfirmedEvent;
import com.softropic.skillars.platform.booking.contract.BookingDeclinedEvent;
import com.softropic.skillars.platform.booking.contract.BookingEvent;
import com.softropic.skillars.platform.booking.contract.BookingRequestedEvent;
import com.softropic.skillars.platform.booking.contract.BookingResponse;
import com.softropic.skillars.platform.booking.contract.BookingStatus;
import com.softropic.skillars.platform.booking.contract.BookingStatusChangedEvent;
import com.softropic.skillars.platform.booking.contract.CoachInboxResponse;
import com.softropic.skillars.platform.booking.contract.CoachReliabilityStrikeQueuedEvent;
import com.softropic.skillars.platform.booking.contract.CreateBookingRequest;
import com.softropic.skillars.platform.booking.contract.ParentScheduleItem;
import com.softropic.skillars.platform.booking.contract.ParentScheduleResponse;
import com.softropic.skillars.platform.booking.contract.RescheduleRequestResponse;
import com.softropic.skillars.platform.booking.contract.ScheduleBookingItem;
import com.softropic.skillars.platform.booking.contract.TransitionContext;
import com.softropic.skillars.platform.booking.repo.Booking;
import com.softropic.skillars.platform.booking.repo.BookingBatch;
import com.softropic.skillars.platform.booking.repo.BookingBatchRepository;
import com.softropic.skillars.platform.booking.repo.BookingRepository;
import com.softropic.skillars.platform.booking.repo.BookingRescheduleRequest;
import com.softropic.skillars.platform.booking.repo.BookingRescheduleRequestRepository;
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

import java.time.DateTimeException;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.EnumSet;
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

    private static final Map<BookingEvent, Set<ActorRole>> EVENT_ROLES = Map.ofEntries(
        Map.entry(BookingEvent.ACCEPT,            EnumSet.of(ActorRole.COACH)),
        Map.entry(BookingEvent.DECLINE,           EnumSet.of(ActorRole.COACH, ActorRole.SYSTEM)),
        Map.entry(BookingEvent.SCHEDULE_UPCOMING, EnumSet.of(ActorRole.SYSTEM)),
        Map.entry(BookingEvent.INITIATE_PAYMENT, EnumSet.of(ActorRole.COACH, ActorRole.SYSTEM)),
        Map.entry(BookingEvent.PAYMENT_CAPTURED, EnumSet.of(ActorRole.COACH, ActorRole.SYSTEM)),
        Map.entry(BookingEvent.PAYMENT_FAILED,   EnumSet.of(ActorRole.SYSTEM)),
        Map.entry(BookingEvent.CANCEL_PARENT,    EnumSet.of(ActorRole.PARENT)),
        Map.entry(BookingEvent.CANCEL_COACH,     EnumSet.of(ActorRole.COACH)),
        Map.entry(BookingEvent.START,            EnumSet.of(ActorRole.COACH, ActorRole.SYSTEM)),
        Map.entry(BookingEvent.PAUSE,            EnumSet.of(ActorRole.COACH)),
        Map.entry(BookingEvent.RESUME,           EnumSet.of(ActorRole.COACH)),
        Map.entry(BookingEvent.NO_SHOW_PLAYER,   EnumSet.of(ActorRole.COACH)),
        Map.entry(BookingEvent.NO_SHOW_COACH,    EnumSet.of(ActorRole.PARENT, ActorRole.SYSTEM)),
        Map.entry(BookingEvent.COMPLETE_PENDING, EnumSet.of(ActorRole.COACH, ActorRole.SYSTEM)),
        Map.entry(BookingEvent.COMPLETE,         EnumSet.of(ActorRole.PARENT, ActorRole.SYSTEM)),
        Map.entry(BookingEvent.QUICK_COMPLETE,   EnumSet.of(ActorRole.COACH, ActorRole.SYSTEM)),
        Map.entry(BookingEvent.DISPUTE,          EnumSet.of(ActorRole.PARENT, ActorRole.COACH)),
        Map.entry(BookingEvent.SETTLE_REFUND,    EnumSet.of(ActorRole.SYSTEM)),
        Map.entry(BookingEvent.SETTLE_COMPLETE,      EnumSet.of(ActorRole.SYSTEM)),
        Map.entry(BookingEvent.REFUND_PROCESSED,     EnumSet.of(ActorRole.SYSTEM)),
        Map.entry(BookingEvent.CANCEL_DUE_TO_PAUSE,  EnumSet.of(ActorRole.SYSTEM))
    );

    private final BookingRepository bookingRepository;
    private final BookingStateMachine bookingStateMachine;
    private final SessionPackService sessionPackService;
    private final CoachProfileRepository coachProfileRepository;
    private final CoachAvailabilityWindowRepository coachAvailabilityWindowRepository;
    private final PlayerProfileRepository playerProfileRepository;
    private final UserRepository userRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final com.softropic.skillars.platform.booking.repo.SessionPackPurchasedRepository sessionPackPurchasedRepository;
    private final BookingRescheduleRequestRepository rescheduleRequestRepository;
    private final BookingBatchRepository batchRepository;

    @Transactional
    public void transition(UUID bookingId, BookingEvent event, TransitionContext context) {
        transitionInternal(bookingId, event, context, true);
    }

    private void transitionInternal(UUID bookingId, BookingEvent event, TransitionContext context, boolean publishEvent) {
        validateActorAuthorization(event, context);
        Booking booking = getBookingOrThrow(bookingId);
        BookingStatus currentStatus;
        try {
            currentStatus = BookingStatus.valueOf(booking.getStatus());
        } catch (IllegalArgumentException e) {
            throw new ResourceNotFoundException(
                "Booking " + bookingId + " has unrecognised status '" + booking.getStatus() + "'", "booking");
        }
        bookingStateMachine.validate(currentStatus, event);
        BookingStatus newStatus = bookingStateMachine.targetStatus(currentStatus, event);

        applyRefundLogic(booking, event, currentStatus);

        booking.setStatus(newStatus.name());
        bookingRepository.save(booking);
        if (publishEvent) {
            eventPublisher.publishEvent(new BookingStatusChangedEvent(this, bookingId, newStatus.name()));
        }
    }

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
        sessionPackPurchasedRepository.findActivePacksForDeduction(req.playerId(), req.coachId(), Instant.now());
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
        return toResponse(booking, coach.getDisplayName(), player.getName(), null, effectiveCredits, null, null, null);
    }

    @Transactional
    public BookingResponse acceptBooking(UUID bookingId, Long coachUserId) {
        Booking booking = getBookingOrThrow(bookingId);

        CoachProfile coach = coachProfileRepository.findByUserId(coachUserId)
            .orElseThrow(() -> new ResourceNotFoundException("Coach profile not found", "coach_profile"));
        if (!Objects.equals(booking.getCoachId(), coach.getId())) {
            throw new OperationNotAllowedException("Coach does not own this booking", SecurityError.MISSING_RIGHTS);
        }

        TransitionContext ctx = new TransitionContext(ActorRole.COACH, coachUserId);
        transitionInternal(bookingId, BookingEvent.ACCEPT, ctx, false);
        transitionInternal(bookingId, BookingEvent.INITIATE_PAYMENT, ctx, false);
        transitionInternal(bookingId, BookingEvent.PAYMENT_CAPTURED, ctx, true);

        // Reload to get updated status
        Booking updated = getBookingOrThrow(bookingId);
        String parentEmail = resolveEmail(updated.getParentId());
        eventPublisher.publishEvent(new BookingConfirmedEvent(
            this, updated.getId(), updated.getParentId(), parentEmail,
            coach.getDisplayName(), updated.getRequestedStartTime(), updated.getCanonicalTimezone()
        ));

        return toResponse(updated, coach.getDisplayName(), resolvePlayerName(updated.getPlayerId()), null, 0, null, null, null);
    }

    @Transactional
    public void declineBooking(UUID bookingId, Long coachUserId) {
        Booking booking = getBookingOrThrow(bookingId);

        CoachProfile coach = coachProfileRepository.findByUserId(coachUserId)
            .orElseThrow(() -> new ResourceNotFoundException("Coach profile not found", "coach_profile"));
        if (!Objects.equals(booking.getCoachId(), coach.getId())) {
            throw new OperationNotAllowedException("Coach does not own this booking", SecurityError.MISSING_RIGHTS);
        }

        TransitionContext ctx = new TransitionContext(ActorRole.COACH, coachUserId);
        transition(bookingId, BookingEvent.DECLINE, ctx);

        eventPublisher.publishEvent(new BookingDeclinedEvent(
            this, bookingId, booking.getParentId(),
            resolveEmail(booking.getParentId()),
            coach.getDisplayName(), booking.getRequestedStartTime(),
            booking.getCanonicalTimezone()
        ));
    }

    @Transactional(readOnly = true)
    public BookingResponse getBooking(UUID id) {
        return toBookingResponse(getBookingOrThrow(id));
    }

    public BookingResponse toBookingResponse(Booking booking) {
        CoachProfile coach = coachProfileRepository.findById(booking.getCoachId()).orElse(null);
        String coachName = coach != null ? coach.getDisplayName() : "Unknown Coach";
        String playerName = resolvePlayerName(booking.getPlayerId());
        return toResponse(booking, coachName, playerName, null, 0, null, null, null);
    }

    @Transactional(readOnly = true)
    public List<BookingResponse> getParentBookings(Long parentId) {
        List<Booking> bookings = bookingRepository.findAllByParentIdOrderByRequestedStartTimeAsc(parentId);

        Set<UUID> coachIds = bookings.stream().map(Booking::getCoachId).collect(Collectors.toSet());
        Map<UUID, String> coachNames = coachProfileRepository.findAllById(coachIds).stream()
            .collect(Collectors.toMap(CoachProfile::getId, CoachProfile::getDisplayName));

        Set<UUID> bookingIds = bookings.stream().map(Booking::getId).collect(Collectors.toSet());
        Map<UUID, RescheduleRequestResponse> pendingReschedules = bookingIds.isEmpty()
            ? Map.of()
            : rescheduleRequestRepository.findPendingByBookingIdIn(bookingIds).stream()
                .collect(Collectors.toMap(
                    BookingRescheduleRequest::getBookingId,
                    r -> new RescheduleRequestResponse(r.getId(), r.getProposedBy(),
                        r.getProposedStartTime(), r.getProposedEndTime(), r.getStatus()),
                    (a, b) -> a));

        Set<UUID> batchIds = bookings.stream()
            .map(Booking::getBatchId)
            .filter(Objects::nonNull)
            .collect(Collectors.toSet());
        Map<UUID, Integer> batchSizeMap = batchIds.isEmpty()
            ? Map.of()
            : bookingRepository.countByBatchIdIn(batchIds).stream()
                .collect(Collectors.toMap(
                    arr -> (UUID) arr[0],
                    arr -> ((Number) arr[1]).intValue(),
                    (a, b) -> a));

        return bookings.stream().map(b -> {
            String coachName = coachNames.getOrDefault(b.getCoachId(), "Unknown Coach");
            String playerName = resolvePlayerName(b.getPlayerId());
            int effectiveCredits = (int) (sessionPackService.getCreditsRemaining(b.getPlayerId(), b.getCoachId())
                - bookingRepository.countInFlightBookings(b.getPlayerId(), b.getCoachId()));
            return toResponse(b, coachName, playerName, null, effectiveCredits,
                pendingReschedules.get(b.getId()), b.getBatchId(), batchSizeMap.get(b.getBatchId()));
        }).toList();
    }

    @Transactional(readOnly = true)
    public CoachInboxResponse getCoachBookingRequests(Long coachUserId) {
        CoachProfile coach = coachProfileRepository.findByUserId(coachUserId)
            .orElseThrow(() -> new ResourceNotFoundException("Coach profile not found", "coach_profile"));

        List<Booking> bookings = bookingRepository
            .findByCoachIdAndStatusOrderByRequestedStartTimeAsc(coach.getId(), "REQUESTED");

        List<BookingResponse> singles = new java.util.ArrayList<>();
        Map<UUID, List<Booking>> batchedByBatchId = new java.util.LinkedHashMap<>();

        for (Booking b : bookings) {
            if (b.getBatchId() == null) {
                String playerName = resolvePlayerName(b.getPlayerId());
                String parentName = resolveParentName(b.getParentId());
                singles.add(toResponse(b, coach.getDisplayName(), playerName, parentName, 0, null, null, null));
            } else {
                batchedByBatchId.computeIfAbsent(b.getBatchId(), k -> new java.util.ArrayList<>()).add(b);
            }
        }

        Set<UUID> batchIds = batchedByBatchId.keySet();
        Map<UUID, Integer> requestedCountMap = batchIds.isEmpty() ? Map.of()
            : batchRepository.findAllById(batchIds).stream()
                .collect(Collectors.toMap(BookingBatch::getId, BookingBatch::getRequestedCount));

        List<BatchGroupedBookingResponse> batchGroups = batchedByBatchId.entrySet().stream().map(entry -> {
            UUID batchId = entry.getKey();
            List<Booking> batchBookings = entry.getValue();
            int totalCount = requestedCountMap.getOrDefault(batchId, batchBookings.size());
            String parentName = batchBookings.isEmpty() ? "" : resolveParentName(batchBookings.get(0).getParentId());
            List<BookingResponse> bookingResponses = batchBookings.stream().map(b -> {
                String playerName = resolvePlayerName(b.getPlayerId());
                return toResponse(b, coach.getDisplayName(), playerName, parentName, 0, null, batchId, totalCount);
            }).toList();
            return new BatchGroupedBookingResponse(batchId, parentName, totalCount, bookingResponses);
        }).toList();

        return new CoachInboxResponse(singles, batchGroups);
    }

    @Transactional(readOnly = true)
    public List<ScheduleBookingItem> getCoachWeekSchedule(UUID coachId, LocalDate weekStart) {
        Instant wkStart = weekStart.atStartOfDay(ZoneOffset.UTC).toInstant();
        Instant wkEnd = weekStart.plusDays(7).atStartOfDay(ZoneOffset.UTC).toInstant();

        List<Booking> bookings = bookingRepository.findByCoachIdAndStatusInAndTimeBetween(
            coachId,
            List.of("CONFIRMED", "UPCOMING", "REQUESTED", "IN_PROGRESS",
                    "COMPLETED_PENDING_CONFIRMATION", "COMPLETED"),
            wkStart, wkEnd);

        Set<UUID> bookingIds = bookings.stream().map(Booking::getId).collect(Collectors.toSet());
        Map<UUID, RescheduleRequestResponse> pendingReschedules = bookingIds.isEmpty()
            ? Map.of()
            : rescheduleRequestRepository.findPendingByBookingIdIn(bookingIds).stream()
                .collect(Collectors.toMap(
                    BookingRescheduleRequest::getBookingId,
                    r -> new RescheduleRequestResponse(r.getId(), r.getProposedBy(),
                        r.getProposedStartTime(), r.getProposedEndTime(), r.getStatus()),
                    (a, b2) -> a));

        return bookings.stream()
            .map(b -> new ScheduleBookingItem(
                b.getId(),
                b.getPlayerId(),
                resolvePlayerName(b.getPlayerId()),
                b.getRequestedStartTime(),
                b.getRequestedEndTime(),
                b.getStatus(),
                b.getCanonicalTimezone(),
                pendingReschedules.get(b.getId())))
            .toList();
    }

    @Transactional(readOnly = true)
    public ParentScheduleResponse getParentPlayerSchedule(Long parentId, Long playerId) {
        PlayerProfile player = playerProfileRepository.findById(playerId)
            .orElseThrow(() -> new ResourceNotFoundException("Player not found", "player_profile"));
        if (!Objects.equals(player.getParentId(), parentId)) {
            throw new ResourceNotFoundException("Player not found", "player_profile");
        }

        List<Booking> bookings = bookingRepository.findByParentIdAndPlayerIdAndStatusIn(
            parentId, playerId, List.of("CONFIRMED", "UPCOMING", "REQUESTED", "IN_PROGRESS"));

        List<ParentScheduleItem> items = bookings.stream().map(b -> {
            String coachName = coachProfileRepository.findById(b.getCoachId())
                .map(CoachProfile::getDisplayName)
                .orElse("Unknown Coach");
            int credits = (int) (sessionPackService.getCreditsRemaining(b.getPlayerId(), b.getCoachId())
                - bookingRepository.countInFlightBookings(b.getPlayerId(), b.getCoachId()));
            if (credits < 0) {
                log.warn("Over-booking detected: playerId={} coachId={} credits={}", b.getPlayerId(), b.getCoachId(), credits);
            }
            int effectiveCredits = Math.max(0, credits);
            return new ParentScheduleItem(
                b.getId(),
                b.getCoachId(),
                coachName,
                b.getRequestedStartTime(),
                b.getRequestedEndTime(),
                b.getStatus(),
                b.getCanonicalTimezone(),
                effectiveCredits);
        }).toList();

        return new ParentScheduleResponse(playerId, items);
    }

    @Transactional
    public void cancelDueToPause(UUID bookingId, UUID coachId, Long parentId) {
        Booking booking = getBookingOrThrow(bookingId);
        if (!Objects.equals(booking.getParentId(), parentId) || !Objects.equals(booking.getCoachId(), coachId)) {
            throw new OperationNotAllowedException("Booking does not belong to this parent/pack", SecurityError.MISSING_RIGHTS);
        }
        transition(bookingId, BookingEvent.CANCEL_DUE_TO_PAUSE, new TransitionContext(ActorRole.SYSTEM, null));
        CoachProfile coach = coachProfileRepository.findById(coachId).orElse(null);
        String coachEmail = coach != null ? resolveEmail(coach.getUserId()) : "";
        String coachDisplayName = coach != null ? coach.getDisplayName() : "Coach";
        String parentName = resolveParentName(parentId);
        eventPublisher.publishEvent(new BookingCancelledDueToPauseEvent(
            this, bookingId, parentId, coachId, coachEmail, coachDisplayName,
            parentName, booking.getRequestedStartTime(), booking.getCanonicalTimezone()
        ));
        log.info("Booking {} cancelled due to pack pause", bookingId);
    }

    public Booking getBookingOrThrow(UUID bookingId) {
        return bookingRepository.findById(bookingId)
            .orElseThrow(() -> new ResourceNotFoundException("Booking not found", "booking"));
    }

    private void validateActorAuthorization(BookingEvent event, TransitionContext context) {
        Set<ActorRole> allowed = EVENT_ROLES.getOrDefault(event, Set.of());
        if (!allowed.contains(context.actorRole())) {
            throw new OperationNotAllowedException(
                "Role " + context.actorRole() + " may not fire event " + event,
                SecurityError.MISSING_RIGHTS
            );
        }
    }

    private void applyRefundLogic(Booking booking, BookingEvent event, BookingStatus currentStatus) {
        switch (event) {
            case CANCEL_PARENT -> {
                // Only compute refund eligibility when payment has actually been captured
                if (currentStatus == BookingStatus.CONFIRMED || currentStatus == BookingStatus.UPCOMING) {
                    long hoursUntilSession = ChronoUnit.HOURS.between(Instant.now(), booking.getRequestedStartTime());
                    String eligibility = hoursUntilSession > 24 ? "FULL" : hoursUntilSession >= 6 ? "PARTIAL" : "NONE";
                    booking.setRefundEligibility(eligibility);
                }
            }
            case CANCEL_COACH -> {
                booking.setRefundEligibility("FULL");
                long hoursUntilSession = ChronoUnit.HOURS.between(Instant.now(), booking.getRequestedStartTime());
                if (hoursUntilSession <= 24) {
                    eventPublisher.publishEvent(new CoachReliabilityStrikeQueuedEvent(
                        this, booking.getId(), booking.getCoachId(), "CANCEL_WITHIN_24H"
                    ));
                }
            }
            case NO_SHOW_PLAYER -> booking.setRefundEligibility("NONE");
            case NO_SHOW_COACH -> {
                booking.setRefundEligibility("FULL");
                eventPublisher.publishEvent(new CoachReliabilityStrikeQueuedEvent(
                    this, booking.getId(), booking.getCoachId(), "NO_SHOW_COACH"
                ));
            }
            default -> { /* no refund logic for other events */ }
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
                && startZdt.toLocalDate().equals(endZdt.toLocalDate())
                && !requestedStart.isBefore(w.getStartTime())
                && !requestedEnd.isAfter(w.getEndTime())) {
                return true;
            }
        }
        return false;
    }

    private BookingResponse toResponse(Booking b, String coachName, String playerName, String parentName,
                                        int effectiveCredits, RescheduleRequestResponse pendingReschedule,
                                        UUID batchId, Integer batchSize) {
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
            effectiveCredits,
            pendingReschedule,
            batchId,
            batchSize
        );
    }
}
