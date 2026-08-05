package com.softropic.skillars.platform.booking.service;

import com.softropic.skillars.infrastructure.exception.ResourceNotFoundException;
import com.softropic.skillars.infrastructure.security.SecurityError;
import com.softropic.skillars.platform.booking.contract.ActorRole;
import com.softropic.skillars.platform.booking.contract.BookingError;
import com.softropic.skillars.platform.booking.contract.BatchGroupedBookingResponse;
import com.softropic.skillars.platform.booking.contract.BookingAcceptedEvent;
import com.softropic.skillars.platform.booking.contract.BookingCancelledDueToPauseEvent;
import com.softropic.skillars.platform.booking.contract.BookingDeclinedEvent;
import com.softropic.skillars.platform.booking.contract.BookingEvent;
import com.softropic.skillars.platform.booking.contract.BookingRequestedEvent;
import com.softropic.skillars.platform.booking.contract.BookingResponse;
import com.softropic.skillars.platform.booking.contract.BookingStatus;
import com.softropic.skillars.platform.booking.contract.BookingStatusChangedEvent;
import com.softropic.skillars.platform.booking.contract.CoachInboxResponse;
import com.softropic.skillars.platform.booking.contract.BookingCancelledByCoachEvent;
import com.softropic.skillars.platform.booking.contract.BookingCancelledByParentEvent;
import com.softropic.skillars.platform.booking.contract.CoachNoShowEvent;
import com.softropic.skillars.platform.booking.contract.CreateBookingRequest;
import com.softropic.skillars.platform.booking.contract.PlayerNoShowEvent;
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
import com.softropic.skillars.platform.payment.contract.PaymentGateway;
import com.softropic.skillars.platform.payment.contract.exception.PaymentGatewayException;
import com.softropic.skillars.platform.payment.repo.SessionPackPurchase;
import com.softropic.skillars.platform.payment.repo.SessionPackPurchaseRepository;
import com.softropic.skillars.platform.marketplace.repo.CoachPricingRepository;
import com.softropic.skillars.platform.security.repo.PlayerProfile;
import com.softropic.skillars.platform.security.repo.PlayerProfileRepository;
import com.softropic.skillars.platform.security.repo.UserRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.authentication.InsufficientAuthenticationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
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
    private final CoachProfileRepository coachProfileRepository;
    private final PaymentGateway paymentGateway;
    private final CoachAvailabilityWindowRepository coachAvailabilityWindowRepository;
    private final PlayerProfileRepository playerProfileRepository;
    private final UserRepository userRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final BookingRescheduleRequestRepository rescheduleRequestRepository;
    private final BookingBatchRepository batchRepository;
    private final SessionPackPurchaseRepository sessionPackPurchaseRepository;
    private final CoachPricingRepository coachPricingRepository;
    private final EntityManager entityManager;

    private static final List<String> ACTIVE_SLOT_STATUSES =
        List.of("REQUESTED", "ACCEPTED", "PAYMENT_PENDING", "CONFIRMED", "UPCOMING", "IN_PROGRESS", "PAUSED");

    // Package-private, not private: BookingBatchService and RescheduleService run the same
    // accept-time overlap check against this exact status set (Deferred-14 AC4). Keeping one
    // definition is the point — a second copy would drift from the V87 constraint's WHERE clause.
    static final List<String> ACTIVE_SLOT_STATUSES_EXCLUDING_REQUESTED =
        ACTIVE_SLOT_STATUSES.stream().filter(s -> !s.equals("REQUESTED")).toList();

    @Transactional
    public void transition(UUID bookingId, BookingEvent event, TransitionContext context) {
        transitionInternal(bookingId, event, context, true);
    }

    private void transitionInternal(UUID bookingId, BookingEvent event, TransitionContext context, boolean publishEvent) {
        validateActorAuthorization(event, context);
        Booking booking = getBookingOrThrow(bookingId);
        BookingStatus currentStatus = readStatusOrThrow(booking);
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
            throw new OperationNotAllowedException("Parent does not own this player", Map.of("submitted parent id", parentId), SecurityError.MISSING_RIGHTS);
        }

        CoachProfile coach = coachProfileRepository.findById(req.coachId())
            .orElseThrow(() -> new ResourceNotFoundException("Coach profile not found", "coach_profile"));
        final Map<String, Object> metaData = Map.of("submitted coach id", req.coachId());
        if (coach.getStatus() == CoachProfileStatus.SUSPENDED) {
            throw new OperationNotAllowedException("Coach is suspended", metaData, BookingError.COACH_UNAVAILABLE);
        }
        if (coach.getStatus() != CoachProfileStatus.ACTIVE
                && coach.getStatus() != CoachProfileStatus.REDUCED
                && coach.getStatus() != CoachProfileStatus.PENDING_REVIEW) {
            throw new OperationNotAllowedException("Coach profile is not active", metaData, SecurityError.MISSING_RIGHTS);
        }

        if (!paymentGateway.isCoachPaymentReady(coach.getId())) {
            throw new PaymentGatewayException("payment.coachStripeNotConfigured");
        }

        if (!req.requestedStartTime().isAfter(Instant.now())) {
            throw new OperationNotAllowedException("Requested start time must be in the future",
                Map.of("requested start time", req.requestedStartTime()), SecurityError.MISSING_RIGHTS);
        }
        if (!req.requestedEndTime().isAfter(req.requestedStartTime())) {
            throw new OperationNotAllowedException("Requested end time must be after start time",
                Map.of("requested start time", req.requestedStartTime(), "requested end time", req.requestedEndTime()),
                SecurityError.MISSING_RIGHTS);
        }

        try {
            ZoneId.of(req.canonicalTimezone());
        } catch (DateTimeException e) {
            throw new OperationNotAllowedException("Invalid timezone: " + req.canonicalTimezone(),
                Map.of("submitted timezone", req.canonicalTimezone()), SecurityError.MISSING_RIGHTS);
        }

        List<CoachAvailabilityWindow> windows = coachAvailabilityWindowRepository.findByCoachId(req.coachId());
        if (!isSlotWithinAvailabilityWindow(req.requestedStartTime(), req.requestedEndTime(), windows)) {
            throw new OperationNotAllowedException("Requested slot is not within coach availability",
                Map.of("requested start time", req.requestedStartTime(), "requested end time", req.requestedEndTime()),
                SecurityError.MISSING_RIGHTS);
        }

        // AC 1/2: acquire a per-coach lock before the authoritative overlap check so two
        // concurrent requests for the same coach are serialized, not interleaved. The row is then
        // re-read under that lock (Deferred-12 AC3): a suspension committed between the unlocked
        // read at the top of this method and the lock being granted would otherwise still let the
        // booking through. orElseThrow guards against the lock silently no-op'ing if the coach row
        // vanished mid-request.
        CoachProfile lockedCoach = coachProfileRepository.findByIdForUpdate(req.coachId())
            .orElseThrow(() -> new ResourceNotFoundException("Coach profile not found", "coach_profile"));
        // The explicit refresh is required, not defensive: findByIdForUpdate is a JPQL query and the
        // same row is already managed from the findById above, so Hibernate takes the DB lock but
        // returns the existing instance without overwriting its in-memory state. Reading getStatus()
        // off lockedCoach without this would re-check the same stale value and could never fire.
        entityManager.refresh(lockedCoach, LockModeType.PESSIMISTIC_WRITE);
        if (lockedCoach.getStatus() == CoachProfileStatus.SUSPENDED) {
            throw new OperationNotAllowedException("Coach is suspended", metaData, BookingError.COACH_UNAVAILABLE);
        }
        if (lockedCoach.getStatus() != CoachProfileStatus.ACTIVE
                && lockedCoach.getStatus() != CoachProfileStatus.REDUCED
                && lockedCoach.getStatus() != CoachProfileStatus.PENDING_REVIEW) {
            throw new OperationNotAllowedException("Coach profile is not active", metaData, SecurityError.MISSING_RIGHTS);
        }

        List<Booking> overlapping = bookingRepository.findOverlappingBookings(
            req.coachId(), req.requestedStartTime(), req.requestedEndTime(), ACTIVE_SLOT_STATUSES, null);
        if (!overlapping.isEmpty()) {
            throw new OperationNotAllowedException(
                "Requested slot overlaps an existing booking for this coach",
                Map.of("submitted coach id", req.coachId(), "requested start time", req.requestedStartTime(),
                    "requested end time", req.requestedEndTime()),
                BookingError.SLOT_UNAVAILABLE);
        }

        // Validate session pack purchase if provided (AC 8)
        if (req.sessionPackPurchaseId() != null) {
            SessionPackPurchase pack = sessionPackPurchaseRepository.findById(req.sessionPackPurchaseId())
                .orElseThrow(() -> new ResourceNotFoundException("Session pack purchase not found", "session_pack_purchase"));
            if (pack.getExpiresAt().isBefore(Instant.now())) {
                throw new PaymentGatewayException("payment.packExpired");
            }
            if (!pack.getParentId().equals(parentId)) {
                throw new OperationNotAllowedException("Pack does not belong to this parent",
                    Map.of("submitted parent id", parentId, "submitted pack id", req.sessionPackPurchaseId()),
                    SecurityError.MISSING_RIGHTS);
            }
            if (!pack.getCoachId().equals(req.coachId())) {
                throw new PaymentGatewayException("payment.packCoachMismatch");
            }
            if (pack.getRemainingSessions() <= 0) {
                throw new PaymentGatewayException("payment.packExhausted");
            }
        }

        Booking booking = new Booking();
        booking.setParentId(parentId);
        booking.setPlayerId(req.playerId());
        booking.setCoachId(req.coachId());
        booking.setRequestedStartTime(req.requestedStartTime());
        booking.setRequestedEndTime(req.requestedEndTime());
        booking.setCanonicalTimezone(req.canonicalTimezone());
        booking.setNotes(req.notes());
        booking.setSessionPackPurchaseId(req.sessionPackPurchaseId());
        bookingRepository.save(booking);

        String coachEmail = resolveEmail(coach.getUserId(), booking.getId());
        eventPublisher.publishEvent(new BookingRequestedEvent(
            this, booking.getId(), parentId, req.playerId(), req.coachId(),
            coach.getDisplayName(), coachEmail, req.notes(),
            req.requestedStartTime(), req.canonicalTimezone()
        ));

        return toResponse(booking, coach.getDisplayName(), player.getName(), null, null, null, null);
    }

    @Transactional
    public BookingResponse acceptBooking(UUID bookingId, Long coachUserId) {
        Booking booking = getBookingOrThrow(bookingId);

        CoachProfile coach = coachProfileRepository.findByUserId(coachUserId)
            .orElseThrow(() -> new ResourceNotFoundException("Coach profile not found", "coach_profile"));
        if (!Objects.equals(booking.getCoachId(), coach.getId())) {
            throw new OperationNotAllowedException("Coach does not own this booking",
                Map.of("submitted booking id", bookingId, "submitted coach id", coach.getId()), SecurityError.MISSING_RIGHTS);
        }

        // AC 3: re-check for a slot conflict that may have appeared since this booking was
        // REQUESTED (e.g. the coach already accepted a different overlapping request). The lookup
        // result itself is unused beyond the lock it acquires — orElseThrow both documents that
        // and guards against the lock silently no-op'ing if the coach row vanished mid-request.
        coachProfileRepository.findByIdForUpdate(coach.getId())
            .orElseThrow(() -> new ResourceNotFoundException("Coach profile not found", "coach_profile"));
        // excludeBookingId=bookingId: without this, retrying acceptBooking on a booking already
        // moved to one of these statuses would match itself and mask the real state-transition error.
        List<Booking> overlapping = bookingRepository.findOverlappingBookings(
            booking.getCoachId(), booking.getRequestedStartTime(), booking.getRequestedEndTime(),
            ACTIVE_SLOT_STATUSES_EXCLUDING_REQUESTED, bookingId);
        if (!overlapping.isEmpty()) {
            throw new OperationNotAllowedException(
                "This slot is no longer available — another booking was accepted for the same time",
                Map.of("submitted coach id", coach.getId(), "requested start time", booking.getRequestedStartTime(),
                    "requested end time", booking.getRequestedEndTime()),
                BookingError.SLOT_UNAVAILABLE);
        }

        TransitionContext ctx = new TransitionContext(ActorRole.COACH, coachUserId);
        acceptAndInitiatePayment(bookingId, ctx);

        Booking updated = getBookingOrThrow(bookingId);
        BigDecimal sessionPrice = resolveSessionPrice(updated);
        String parentEmail = resolveEmail(updated.getParentId(), updated.getId());

        // Publish inside @Transactional — @TransactionalEventListener(AFTER_COMMIT) ensures
        // PaymentLifecycleService runs only after DB commit
        eventPublisher.publishEvent(new BookingAcceptedEvent(
            this, updated.getId(), updated.getParentId(), updated.getCoachId(),
            sessionPrice, updated.getSessionPackPurchaseId(),
            parentEmail, coach.getDisplayName(),
            updated.getRequestedStartTime(), updated.getCanonicalTimezone()
        ));

        // Return PAYMENT_PENDING status — PaymentLifecycleService handles CONFIRMED/DECLINED
        return toResponse(updated, coach.getDisplayName(), resolvePlayerName(updated.getPlayerId()), null, null, null, null);
    }

    /**
     * Moves a REQUESTED booking to PAYMENT_PENDING in the one step every accept path must take:
     * ACCEPT then INITIATE_PAYMENT. Both the single-booking flow and the batch flow call this, so
     * they cannot drift apart again — the batch flow previously issued only ACCEPT, leaving its
     * bookings in a state from which PAYMENT_CAPTURED/PAYMENT_FAILED are illegal transitions
     * (Deferred-12 AC6). The intermediate ACCEPTED status change is not published: callers observe
     * one status-change event per accept, carrying PAYMENT_PENDING.
     *
     * <p>Note on the transaction boundary: {@code acceptBooking} reaches this method by plain
     * self-invocation, so the proxy is bypassed and both legs simply run inside acceptBooking's
     * own transaction. The annotation is what covers the batch caller, which does come through the
     * proxy. Both paths are atomic across the two legs — but by different mechanisms, so do not
     * assume this annotation is what makes the single-booking flow safe.
     */
    @Transactional
    public void acceptAndInitiatePayment(UUID bookingId, TransitionContext context) {
        transitionInternal(bookingId, BookingEvent.ACCEPT, context, false);
        transitionInternal(bookingId, BookingEvent.INITIATE_PAYMENT, context, true);
    }

    private BigDecimal resolveSessionPrice(Booking booking) {
        if (booking.getSessionPackPurchaseId() != null) {
            return sessionPackPurchaseRepository.findById(booking.getSessionPackPurchaseId())
                .map(p -> p.getPricePerSession())
                .orElseThrow(() -> new ResourceNotFoundException("Session pack purchase not found", "session_pack_purchase"));
        }
        return coachPricingRepository.findByCoachId(booking.getCoachId())
            .map(p -> p.getPerSessionPrice())
            .orElseThrow(() -> new ResourceNotFoundException("Coach pricing not found", "coach_pricing"));
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
            resolveEmail(booking.getParentId(), bookingId),
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
        return toResponse(booking, coachName, playerName, null, null, null, null);
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
            // batchSizeMap may be Map.of() (or simply lack the key) — Map.get(null) throws NPE on
            // the JDK's immutable maps, so only look up batch size when the booking is actually batched.
            Integer batchSize = b.getBatchId() != null ? batchSizeMap.get(b.getBatchId()) : null;
            return toResponse(b, coachName, playerName, null,
                pendingReschedules.get(b.getId()), b.getBatchId(), batchSize);
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
                singles.add(toResponse(b, coach.getDisplayName(), playerName, parentName, null, null, null));
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
                return toResponse(b, coach.getDisplayName(), playerName, parentName, null, batchId, totalCount);
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
            return new ParentScheduleItem(
                b.getId(),
                b.getCoachId(),
                coachName,
                b.getRequestedStartTime(),
                b.getRequestedEndTime(),
                b.getStatus(),
                b.getCanonicalTimezone());
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
        String coachEmail = coach != null ? resolveEmail(coach.getUserId(), bookingId) : "";
        String coachDisplayName = coach != null ? coach.getDisplayName() : "Coach";
        String parentName = resolveParentName(parentId);
        eventPublisher.publishEvent(new BookingCancelledDueToPauseEvent(
            this, bookingId, parentId, coachId, coachEmail, coachDisplayName,
            parentName, booking.getRequestedStartTime(), booking.getCanonicalTimezone()
        ));
        log.info("Booking {} cancelled due to pack pause", bookingId);
    }

    private BookingStatus readStatusOrThrow(Booking booking) {
        try {
            return BookingStatus.valueOf(booking.getStatus());
        } catch (IllegalArgumentException e) {
            throw new ResourceNotFoundException(
                "Booking " + booking.getId() + " has unrecognised status '" + booking.getStatus() + "'", "booking");
        }
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

    @Transactional
    public void cancelBookingAsParent(UUID bookingId, Long parentUserId) {
        Booking booking = getBookingOrThrow(bookingId);
        if (!Objects.equals(booking.getParentId(), parentUserId)) {
            throw new OperationNotAllowedException("Parent does not own this booking", SecurityError.MISSING_RIGHTS);
        }
        long hoursBeforeSession = ChronoUnit.HOURS.between(Instant.now(), booking.getRequestedStartTime());
        // Deferred-12 AC4: money only ever leaves the parent (credit debit / pack unit deduction)
        // once payment has been captured, i.e. from CONFIRMED onwards. Everything else — notably
        // PAYMENT_PENDING and ACCEPTED, where batch bookings briefly rest — has nothing to refund,
        // so a >24h cancellation there must not mint a BOOKING_REFUND credit or restore a pack unit.
        // Deliberately a whitelist: a blacklist would leave newly-added pre-payment statuses open.
        // Mirrors applyRefundLogic's existing CONFIRMED/UPCOMING condition rather than adding a
        // third refund rule.
        BookingStatus statusBeforeCancel = readStatusOrThrow(booking);
        boolean paymentWasCaptured = statusBeforeCancel == BookingStatus.CONFIRMED
            || statusBeforeCancel == BookingStatus.UPCOMING;
        boolean refundEligible = paymentWasCaptured
            && booking.getRequestedStartTime().isAfter(Instant.now().plus(24, ChronoUnit.HOURS));
        BigDecimal sessionPrice = resolveSessionPrice(booking);
        String parentEmail = resolveEmail(parentUserId, bookingId);
        String coachEmail = resolveCoachEmail(booking.getCoachId(), bookingId);

        transition(bookingId, BookingEvent.CANCEL_PARENT, new TransitionContext(ActorRole.PARENT, parentUserId));

        eventPublisher.publishEvent(new BookingCancelledByParentEvent(
            this, bookingId, parentUserId, booking.getCoachId(),
            booking.getSessionPackPurchaseId(), hoursBeforeSession, refundEligible,
            sessionPrice, parentEmail, coachEmail,
            booking.getRequestedStartTime(), booking.getCanonicalTimezone()
        ));
    }

    private static final Set<String> VALID_CANCEL_REASONS = Set.of(
        "MUTUAL_AGREEMENT", "HEALTH_MEDICAL", "FAMILY_EMERGENCY",
        "WEATHER", "SCHEDULING_PREFERENCE", "OTHER_UNEXCUSED"
    );

    @Transactional
    public void cancelBookingAsCoach(UUID bookingId, Long coachUserId, String cancelReason) {
        Booking booking = getBookingOrThrow(bookingId);
        CoachProfile coach = coachProfileRepository.findByUserId(coachUserId)
            .orElseThrow(() -> new ResourceNotFoundException("Coach profile not found", "coach_profile"));
        if (!Objects.equals(booking.getCoachId(), coach.getId())) {
            throw new OperationNotAllowedException("Coach does not own this booking", SecurityError.MISSING_RIGHTS);
        }
        if (cancelReason != null && !VALID_CANCEL_REASONS.contains(cancelReason)) {
            throw new OperationNotAllowedException("Invalid cancel reason: " + cancelReason, SecurityError.MISSING_RIGHTS);
        }

        BigDecimal sessionPrice = resolveSessionPrice(booking);
        String parentEmail = resolveEmail(booking.getParentId(), bookingId);

        boolean packExpired = false;
        if (booking.getSessionPackPurchaseId() != null) {
            packExpired = sessionPackPurchaseRepository.findById(booking.getSessionPackPurchaseId())
                .map(p -> p.getExpiresAt().isBefore(Instant.now()))
                .orElse(false);
        }

        transition(bookingId, BookingEvent.CANCEL_COACH, new TransitionContext(ActorRole.COACH, coachUserId));

        String resolvedReason = cancelReason != null ? cancelReason : "OTHER_UNEXCUSED";
        booking.setCancelReason(resolvedReason);
        bookingRepository.save(booking);

        eventPublisher.publishEvent(new BookingCancelledByCoachEvent(
            this, bookingId, booking.getParentId(), booking.getCoachId(),
            resolvedReason, booking.getSessionPackPurchaseId(),
            sessionPrice, packExpired, parentEmail,
            booking.getRequestedStartTime(), booking.getCanonicalTimezone()
        ));
    }

    @Transactional
    public void recordNoShowPlayer(UUID bookingId, Long coachUserId) {
        Booking booking = getBookingOrThrow(bookingId);
        CoachProfile coach = coachProfileRepository.findByUserId(coachUserId)
            .orElseThrow(() -> new ResourceNotFoundException("Coach profile not found", "coach_profile"));
        if (!Objects.equals(booking.getCoachId(), coach.getId())) {
            throw new OperationNotAllowedException("Coach does not own this booking", SecurityError.MISSING_RIGHTS);
        }
        String coachEmail = resolveCoachEmail(booking.getCoachId(), bookingId);

        transition(bookingId, BookingEvent.NO_SHOW_PLAYER, new TransitionContext(ActorRole.COACH, coachUserId));

        eventPublisher.publishEvent(new PlayerNoShowEvent(
            this, bookingId, booking.getParentId(), booking.getCoachId(),
            coachEmail, booking.getRequestedStartTime(), booking.getCanonicalTimezone()
        ));
    }

    @Transactional
    public void recordNoShowCoach(UUID bookingId, Long parentUserId) {
        Booking booking = getBookingOrThrow(bookingId);
        if (!Objects.equals(booking.getParentId(), parentUserId)) {
            throw new OperationNotAllowedException("Parent does not own this booking", SecurityError.MISSING_RIGHTS);
        }
        BigDecimal sessionPrice = resolveSessionPrice(booking);
        String parentEmail = resolveEmail(parentUserId, bookingId);

        boolean packExpired = false;
        if (booking.getSessionPackPurchaseId() != null) {
            packExpired = sessionPackPurchaseRepository.findById(booking.getSessionPackPurchaseId())
                .map(p -> p.getExpiresAt().isBefore(Instant.now()))
                .orElse(false);
        }

        transition(bookingId, BookingEvent.NO_SHOW_COACH, new TransitionContext(ActorRole.PARENT, parentUserId));

        eventPublisher.publishEvent(new CoachNoShowEvent(
            this, bookingId, parentUserId, booking.getCoachId(),
            booking.getSessionPackPurchaseId(), sessionPrice,
            packExpired, parentEmail,
            booking.getRequestedStartTime(), booking.getCanonicalTimezone()
        ));
    }

    private String resolveCoachEmail(UUID coachId, UUID bookingId) {
        return coachProfileRepository.findById(coachId)
            .map(coach -> resolveEmail(coach.getUserId(), bookingId))
            .orElseGet(() -> {
                log.warn("resolveCoachEmail: no coach profile found for coachId={} bookingId={} — notification will not be sent", coachId, bookingId);
                return "";
            });
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
            case CANCEL_COACH -> booking.setRefundEligibility("FULL");
            case NO_SHOW_PLAYER -> booking.setRefundEligibility("NONE");
            case NO_SHOW_COACH -> booking.setRefundEligibility("FULL");
            default -> { /* no refund logic for other events */ }
        }
    }

    private String resolveEmail(Long userId, UUID bookingId) {
        return userRepository.findById(userId).map(u -> u.getEmail()).orElseGet(() -> {
            log.warn("Could not resolve email for userId={} bookingId={} — notification will be skipped", userId, bookingId);
            return "";
        });
    }

    private String resolvePlayerName(Long playerId) {
        return playerProfileRepository.findById(playerId).map(PlayerProfile::getName).orElse("Unknown Player");
    }

    private String resolveParentName(Long parentId) {
        return userRepository.findById(parentId)
            .map(u -> {
                String firstName = u.getFirstName() != null ? u.getFirstName() : "";
                String lastName = u.getLastName() != null ? u.getLastName() : "";
                String fullName = (firstName + " " + lastName).trim();
                return fullName.isEmpty() ? "Unknown Parent" : fullName;
            })
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
            // Anchor window boundaries to the session's start date in the coach's timezone.
            // ZonedDateTime comparison handles cross-midnight sessions correctly without
            // the date-equality guard that previously rejected all late-night sessions.
            ZonedDateTime windowStart = w.getStartTime().atDate(startZdt.toLocalDate()).atZone(zoneId);
            ZonedDateTime windowEnd   = w.getEndTime().atDate(startZdt.toLocalDate()).atZone(zoneId);

            if (w.getDayOfWeek() == (short) startZdt.getDayOfWeek().getValue()
                && !startZdt.isBefore(windowStart)
                && !endZdt.isAfter(windowEnd)) {
                return true;
            }
        }
        return false;
    }

    private BookingResponse toResponse(Booking b, String coachName, String playerName, String parentName,
                                        RescheduleRequestResponse pendingReschedule,
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
            pendingReschedule,
            batchId,
            batchSize
        );
    }
}
