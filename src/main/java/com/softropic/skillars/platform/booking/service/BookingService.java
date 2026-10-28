package com.softropic.skillars.platform.booking.service;

import com.softropic.skillars.infrastructure.exception.ResourceNotFoundException;
import com.softropic.skillars.infrastructure.persistence.PessimisticLockRetryer;
import com.softropic.skillars.infrastructure.sanitizer.ContactDetailSanitizer;
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
import com.softropic.skillars.infrastructure.util.ClockProvider;
import com.softropic.skillars.platform.booking.contract.BookingStatus;
import com.softropic.skillars.platform.booking.contract.CoachPlanNoteResponse;
import com.softropic.skillars.platform.booking.contract.BookingStatusChangedEvent;
import com.softropic.skillars.platform.booking.contract.CoachInboxResponse;
import com.softropic.skillars.platform.booking.contract.AdminBookingCancellationOutcome;
import com.softropic.skillars.platform.booking.contract.BookingCancelledByAdminEvent;
import com.softropic.skillars.platform.booking.contract.BookingCancelledByCoachEvent;
import com.softropic.skillars.platform.booking.contract.BookingCancelledByParentEvent;
import com.softropic.skillars.platform.booking.contract.CoachNoShowEvent;
import com.softropic.skillars.platform.booking.contract.CreateBookingRequest;
import com.softropic.skillars.platform.booking.contract.PlayerNoShowEvent;
import com.softropic.skillars.platform.booking.contract.ParentScheduleItem;
import com.softropic.skillars.platform.config.service.ConfigBounds;
import com.softropic.skillars.platform.config.service.ConfigService;
import com.softropic.skillars.platform.booking.contract.ParentScheduleResponse;
import com.softropic.skillars.platform.booking.contract.PlayerRequestResponse;
import com.softropic.skillars.platform.booking.contract.RescheduleRequestResponse;
import com.softropic.skillars.platform.booking.contract.ScheduleBookingItem;
import com.softropic.skillars.platform.booking.contract.TransitionContext;
import com.softropic.skillars.platform.booking.repo.Booking;
import com.softropic.skillars.platform.booking.repo.BookingBatch;
import com.softropic.skillars.platform.booking.repo.BookingBatchRepository;
import com.softropic.skillars.platform.booking.repo.CoachAvailabilityBlockRepository;
import com.softropic.skillars.platform.booking.repo.BookingRepository;
import com.softropic.skillars.platform.booking.repo.SessionCompletionData;
import com.softropic.skillars.platform.booking.repo.SessionCompletionDataRepository;
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
import com.softropic.skillars.platform.payment.contract.BookingPaymentStatus;
import com.softropic.skillars.platform.payment.repo.BookingPaymentRepository;
import com.softropic.skillars.platform.payment.repo.SessionPackPurchase;
import com.softropic.skillars.platform.payment.repo.SessionPackPurchaseRepository;
import com.softropic.skillars.platform.marketplace.repo.CoachPricingRepository;
import com.softropic.skillars.platform.security.repo.PlayerProfile;
import com.softropic.skillars.platform.security.repo.PlayerProfileRepository;
import com.softropic.skillars.platform.security.repo.User;
import com.softropic.skillars.platform.security.repo.UserRepository;
import com.softropic.skillars.platform.security.service.DevelopmentDataErasureGuard;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import org.apache.commons.lang3.StringUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.security.authentication.InsufficientAuthenticationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.DateTimeException;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Collection;
import java.util.EnumSet;
import java.util.HashSet;
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
        Map.entry(BookingEvent.COMPLETE,         EnumSet.of(ActorRole.PARENT, ActorRole.SYSTEM, ActorRole.ADMIN)),
        Map.entry(BookingEvent.QUICK_COMPLETE,   EnumSet.of(ActorRole.COACH, ActorRole.SYSTEM)),
        Map.entry(BookingEvent.DISPUTE,          EnumSet.of(ActorRole.PARENT, ActorRole.COACH)),
        Map.entry(BookingEvent.SETTLE_REFUND,    EnumSet.of(ActorRole.SYSTEM)),
        Map.entry(BookingEvent.SETTLE_COMPLETE,      EnumSet.of(ActorRole.SYSTEM)),
        Map.entry(BookingEvent.REFUND_PROCESSED,     EnumSet.of(ActorRole.SYSTEM)),
        Map.entry(BookingEvent.CANCEL_DUE_TO_PAUSE,  EnumSet.of(ActorRole.SYSTEM)),
        Map.entry(BookingEvent.CANCEL_ADMIN,         EnumSet.of(ActorRole.ADMIN))
    );

    private final BookingRepository bookingRepository;
    private final SessionCompletionDataRepository sessionCompletionDataRepository;
    private final BookingStateMachine bookingStateMachine;
    private final CoachProfileRepository coachProfileRepository;
    private final PaymentGateway paymentGateway;
    private final CoachAvailabilityWindowRepository coachAvailabilityWindowRepository;
    private final CoachAvailabilityBlockRepository coachAvailabilityBlockRepository;
    private final PlayerProfileRepository playerProfileRepository;
    private final UserRepository userRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final BookingRescheduleRequestRepository rescheduleRequestRepository;
    private final BookingBatchRepository batchRepository;
    private final SessionPackPurchaseRepository sessionPackPurchaseRepository;
    private final CoachPricingRepository coachPricingRepository;
    private final SessionDurationResolver sessionDurationResolver;
    // UAT.3 AC2: crossing into platform.payment.repo is the established shape in this class — it
    // already injects SessionPackPurchaseRepository from the same package. Whether a capture is in
    // flight is a database question, not a gateway one, so it deliberately does not go through
    // PaymentGateway.
    private final BookingPaymentRepository bookingPaymentRepository;
    private final EntityManager entityManager;
    private final PessimisticLockRetryer lockRetryer;
    private final ContactDetailSanitizer contactDetailSanitizer;
    private final DevelopmentDataErasureGuard developmentDataErasureGuard;
    // fix/booking-cancel-policy-loopholes: late-cancel grace window (cancelBookingAsParent only).
    private final ConfigService configService;

    // skillars-deferred-155 AC5: the two statuses representing "this session is genuinely still
    // going to happen" — the only window in which a player/parent's session-prep request can be
    // meaningfully set or edited. Deliberately NOT ACTIVE_SLOT_STATUSES above (that set also
    // includes REQUESTED/ACCEPTED/PAYMENT_PENDING, not yet locked in, and PAUSED/IN_PROGRESS, where
    // editing "what to work on next" no longer makes sense).
    private static final Set<String> PLAYER_REQUEST_EDITABLE_STATUSES = Set.of(
        BookingStatus.CONFIRMED.name(), BookingStatus.UPCOMING.name());

    // Package-private, not private: AvailabilityService reuses this exact status set to exclude
    // already-booked slots from the availability calendar (Deferred-18 AC1) — same rationale as
    // ACTIVE_SLOT_STATUSES_EXCLUDING_REQUESTED below. Keeping one definition is the point.
    static final List<String> ACTIVE_SLOT_STATUSES =
        List.of("REQUESTED", "ACCEPTED", "PAYMENT_PENDING", "CONFIRMED", "UPCOMING", "IN_PROGRESS", "PAUSED");

    // Package-private, not private: BookingBatchService and RescheduleService run the same
    // accept-time overlap check against this exact status set (Deferred-14 AC4). Keeping one
    // definition is the point — a second copy would drift from the V87 constraint's WHERE clause.
    static final List<String> ACTIVE_SLOT_STATUSES_EXCLUDING_REQUESTED =
        ACTIVE_SLOT_STATUSES.stream().filter(s -> !s.equals("REQUESTED")).toList();

    public List<String> getActiveSlotStatuses() {
        return ACTIVE_SLOT_STATUSES;
    }

    @Transactional
    public void transition(UUID bookingId, BookingEvent event, TransitionContext context) {
        transitionInternal(bookingId, event, context, true);
    }

    private void transitionInternal(UUID bookingId, BookingEvent event, TransitionContext context, boolean publishEvent) {
        validateActorAuthorization(event, context);
        // Deferred-77 AC14: @Version already prevents silent state corruption from a concurrent
        // transition (losers get a clean OptimisticLockingFailureException at save time, caught at
        // every call site) — this lock is a UX improvement, trading fail-fast-and-retry for
        // blocking, not a correctness fix. Re-acquiring the same row lock across
        // acceptAndInitiatePayment's two same-transaction calls is a no-op, not a deadlock risk.
        Booking booking = lockBookingForUpdate(bookingId);
        BookingStatus currentStatus = readStatusOrThrow(booking);
        bookingStateMachine.validate(currentStatus, event);
        BookingStatus newStatus = bookingStateMachine.targetStatus(currentStatus, event);

        booking.setStatus(newStatus.name());
        bookingRepository.save(booking);
        if (publishEvent) {
            eventPublisher.publishEvent(new BookingStatusChangedEvent(this, bookingId, newStatus.name()));
        }
    }

    /**
     * Locked, refreshed read of one booking row. The refresh matters when the row is already managed
     * in this persistence context: the JPQL locked read would otherwise hand back the stale instance.
     */
    private Booking lockBookingForUpdate(UUID bookingId) {
        Booking booking = lockRetryer.withBoundedRetry("BookingService.lockBookingForUpdate",
            () -> bookingRepository.findByIdForUpdate(bookingId)
                .orElseThrow(() -> new ResourceNotFoundException("Booking not found", "booking")));
        entityManager.refresh(booking, LockModeType.PESSIMISTIC_WRITE);
        return booking;
    }

    /**
     * Statuses {@code CANCEL_ADMIN} leaves from — every pre-session status (C2, DECISIONS.md item 2).
     * Kept in step with {@code BookingStateMachine}; IN_PROGRESS / PAUSED are deliberately absent.
     */
    public static final List<String> ADMIN_CANCELLABLE_STATUSES = List.of(
        BookingStatus.REQUESTED.name(), BookingStatus.ACCEPTED.name(), BookingStatus.PAYMENT_PENDING.name(),
        BookingStatus.CONFIRMED.name(), BookingStatus.UPCOMING.name());

    /** A session that is happening right now — reported, never cancelled ({@link AdminBookingCancellationOutcome#LIVE_SESSION}). */
    public static final List<String> LIVE_SESSION_STATUSES = List.of(
        BookingStatus.IN_PROGRESS.name(), BookingStatus.PAUSED.name());

    /**
     * Admin/system cancellation of a booking on the coach's side — a coach suspension or a child-safety
     * hold (B2 for REQUESTED; C2 extends it to ACCEPTED / PAYMENT_PENDING / CONFIRMED / UPCOMING). Goes
     * through the state machine ({@code CANCEL_ADMIN}, ADMIN actor) so a {@link BookingStatusChangedEvent}
     * is published — batch roll-up, SSE and the session-plan listeners all key off it — and publishes
     * {@link BookingCancelledByAdminEvent}, whose listeners refund the payer in full (no coach strike:
     * {@code CancellationRefundService} issues none for this event) and send the parent a neutral
     * "the coach is unavailable" notice.
     *
     * <p>Everything is decided under the booking row lock, which the caller takes after the coach row
     * lock (the accept paths' order). A refund is owed only when payment was captured
     * ({@code CONFIRMED}/{@code UPCOMING}) — the same whitelist as {@link #cancelBookingAsParent}, so a
     * pre-payment booking never mints a refund or restores a pack session that was never deducted.
     * {@code PAYMENT_PENDING} with a {@code CAPTURE_PENDING} payment row is refused (a charge is in
     * flight — cancelling on top of it is "captured but cancelled, no refund"). A capture that has not
     * reserved yet is safe to cancel ahead of: {@code reserveCapture} and the pack/credit settle both
     * re-check {@code PAYMENT_PENDING} under the same row lock, so they abort before taking money.
     *
     * <p>Never throws for a booking that simply is not cancellable (any more): this method joins the
     * caller's transaction, so a throw would mark the whole suspension rollback-only. Lock contention
     * still propagates ({@code PessimisticLockRetryer} exhaustion), rolling the suspension back as a
     * retryable 409 — all-or-nothing.
     *
     * @param actorId the admin's user id, or {@code AdminActionLog.SYSTEM_ACTOR_ID} for a safety hold
     */
    @Transactional
    public AdminBookingCancellationOutcome cancelBookingAsAdmin(UUID bookingId, Long actorId, String cancelReason) {
        TransitionContext ctx = new TransitionContext(ActorRole.ADMIN, actorId);
        validateActorAuthorization(BookingEvent.CANCEL_ADMIN, ctx);
        Booking booking = lockBookingForUpdate(bookingId);
        String status = booking.getStatus();
        if (LIVE_SESSION_STATUSES.contains(status)) {
            return AdminBookingCancellationOutcome.LIVE_SESSION;
        }
        if (!ADMIN_CANCELLABLE_STATUSES.contains(status)) {
            log.info("Booking {} is not admin-cancellable (status={}) -- left as is", bookingId, status);
            return AdminBookingCancellationOutcome.NOT_CANCELLABLE;
        }
        if (BookingStatus.PAYMENT_PENDING.name().equals(status)
                && bookingPaymentRepository.findById(bookingId)
                       .filter(bp -> BookingPaymentStatus.CAPTURE_PENDING.equals(bp.getStatus())).isPresent()) {
            return AdminBookingCancellationOutcome.PAYMENT_IN_PROGRESS;
        }

        boolean paymentCaptured = BookingStatus.CONFIRMED.name().equals(status)
            || BookingStatus.UPCOMING.name().equals(status);
        boolean packExpired = false;
        if (paymentCaptured && booking.getSessionPackPurchaseId() != null) {
            packExpired = sessionPackPurchaseRepository.findById(booking.getSessionPackPurchaseId())
                .map(p -> p.getExpiresAt().isBefore(Instant.now()))
                .orElse(false);
        }
        BigDecimal refundAmount = paymentCaptured ? resolveAmountPaidOrZero(booking) : BigDecimal.ZERO;
        CoachProfile coach = coachProfileRepository.findById(booking.getCoachId()).orElse(null);
        String coachDisplayName = coach != null ? coach.getDisplayName() : "";
        String parentEmail = resolveEmail(booking.getParentId(), bookingId);
        String coachEmail = coach != null ? resolveEmail(coach.getUserId(), bookingId) : "";

        transitionInternal(bookingId, BookingEvent.CANCEL_ADMIN, ctx, true);
        // After the transition, never before: transitionInternal refreshes this same managed instance,
        // which would discard an unflushed cancelReason.
        booking.setCancelReason(cancelReason);
        bookingRepository.save(booking);

        eventPublisher.publishEvent(new BookingCancelledByAdminEvent(
            this, bookingId, booking.getParentId(), booking.getCoachId(), booking.getSessionPackPurchaseId(),
            refundAmount, paymentCaptured, packExpired, parentEmail, coachEmail, coachDisplayName, cancelReason,
            booking.getRequestedStartTime(), booking.getCanonicalTimezone()));
        return AdminBookingCancellationOutcome.CANCELLED;
    }

    /**
     * What the payer actually paid for a captured booking — the full-refund amount. A card/credit
     * booking's {@code booking_payments} row records the credit debited plus the Stripe charge; the
     * coach's current price is only a fallback for a row-less legacy booking (and is wrong once the
     * coach has re-priced, which is why it is not the primary source). A pack booking refunds the
     * pack's per-session price (its row records zero — the pack was charged at purchase).
     *
     * <p>Throws {@link ResourceNotFoundException} when neither a captured payment row nor a price exists,
     * failing the cancellation: the coach / parent / no-show paths (C6) must never commit a refund of 0
     * while telling the parent they were "fully refunded".
     */
    private BigDecimal resolveAmountPaid(Booking booking) {
        if (booking.getSessionPackPurchaseId() == null) {
            BigDecimal paid = bookingPaymentRepository.findById(booking.getId())
                .filter(bp -> BookingPaymentStatus.CAPTURED.equals(bp.getStatus()))
                .map(bp -> bp.getCreditDebited().add(bp.getStripeCharged()))
                .orElse(null);
            if (paid != null && paid.signum() > 0) {
                return paid;
            }
        }
        return resolveSessionPrice(booking);
    }

    /**
     * {@link #resolveAmountPaid}, but never throws: {@link #cancelBookingAsAdmin} joins a suspension's
     * transaction, where one unpriceable legacy booking must not roll back the whole suspension. The
     * amount is recorded as 0 and logged for manual reconciliation.
     */
    private BigDecimal resolveAmountPaidOrZero(Booking booking) {
        try {
            return resolveAmountPaid(booking);
        } catch (ResourceNotFoundException e) {
            log.error("[ADMIN_CANCEL_REFUND_AMOUNT_UNKNOWN] bookingId={} — no payment row and no price; "
                + "refund recorded as 0, reconcile by hand", booking.getId());
            return BigDecimal.ZERO;
        }
    }

    @Transactional
    public BookingResponse createBookingRequest(Long parentId, CreateBookingRequest req) {
        PlayerProfile player = playerProfileRepository.findById(req.playerId())
            .orElseThrow(() -> new ResourceNotFoundException("Player not found", "player_profile"));
        if (player.getParentId() != null) {
            if (!Objects.equals(player.getParentId(), parentId)) {
                throw new OperationNotAllowedException("Parent does not own this player", Map.of("submitted parent id", parentId), SecurityError.MISSING_RIGHTS);
            }
        } else {
            if (!Objects.equals(player.getUserId(), parentId)) {
                throw new OperationNotAllowedException("Player does not own this profile", Map.of("submitted caller id", parentId), SecurityError.MISSING_RIGHTS);
            }
        }
        // B2: unlocked fast-fail only — the authoritative re-check runs under the player row lock
        // below (requirePlayerBookableUnderLock).
        requireNotErased(player);

        CoachProfile coach = coachProfileRepository.findById(req.coachId())
            .orElseThrow(() -> new ResourceNotFoundException("Coach profile not found", "coach_profile"));
        final Map<String, Object> metaData = Map.of("submitted coach id", req.coachId());
        requireBookableCoachStatus(coach, metaData);

        requireCoachPaymentReady(coach.getId());

        if (!req.requestedStartTime().isAfter(Instant.now())) {
            throw new OperationNotAllowedException("Requested start time must be in the future",
                Map.of("requested start time", req.requestedStartTime()), BookingError.START_TIME_IN_PAST);
        }
        if (!req.requestedEndTime().isAfter(req.requestedStartTime())) {
            throw new OperationNotAllowedException("Requested end time must be after start time",
                Map.of("requested start time", req.requestedStartTime(), "requested end time", req.requestedEndTime()),
                BookingError.INVALID_TIME_RANGE);
        }

        // UAT.2 AC3. Placed BEFORE the window lookup and the pessimistic lock below, so a malformed
        // request costs neither a window query nor a row lock — and AFTER the end-after-start check
        // above, so a reversed range still reports the clearer error.
        //
        // Duration.between on Instants is exact and calendar-free, which is what this needs: a
        // session that crosses a DST transition has a wall-clock length different from its elapsed
        // length, and the booking's stored bounds are instants. Do not compare LocalTimes.
        Duration requiredDuration = sessionDurationResolver.resolve(req.coachId());
        Duration requestedDuration = Duration.between(req.requestedStartTime(), req.requestedEndTime());
        if (!requestedDuration.equals(requiredDuration)) {
            throw new OperationNotAllowedException(
                "Requested session length does not match this coach's session length",
                Map.of("requested minutes", requestedDuration.toMinutes(),
                    "required minutes", requiredDuration.toMinutes()),
                BookingError.INVALID_SESSION_DURATION);
        }

        // Deferred-78 AC1: the lock is acquired here, BEFORE the availability-window read below,
        // not after it as this used to do. A window fetched outside the lock could be mutated by a
        // concurrent addWindow/updateWindow/deleteWindow (now also lock-guarded, see
        // AvailabilityService) before this method's overlap check ever ran, so the signature check
        // and isSlotWithinAvailabilityWindow below were validating against data that could already
        // be stale by the time this transaction commits. The row is re-read under the lock
        // (Deferred-12 AC3): a suspension committed between the unlocked read at the top of this
        // method and the lock being granted would otherwise still let the booking through.
        // orElseThrow guards against the lock silently no-op'ing if the coach row vanished
        // mid-request.
        CoachProfile lockedCoach = lockRetryer.withBoundedRetry("BookingService.createBookingRequest", () -> {
            CoachProfile c = coachProfileRepository.findByIdForUpdate(req.coachId())
                .orElseThrow(() -> new ResourceNotFoundException("Coach profile not found", "coach_profile"));
            // The explicit refresh is required, not defensive: findByIdForUpdate is a JPQL query and the
            // same row is already managed from the findById above, so Hibernate takes the DB lock but
            // returns the existing instance without overwriting its in-memory state. Reading getStatus()
            // off lockedCoach without this would re-check the same stale value and could never fire.
            entityManager.refresh(c, LockModeType.PESSIMISTIC_WRITE);
            return c;
        });
        requireBookableCoachStatus(lockedCoach, metaData);
        requirePlayerBookableUnderLock(req.playerId(), "BookingService.createBookingRequest");

        List<CoachAvailabilityWindow> windows = coachAvailabilityWindowRepository.findByCoachIdOrderByDayOfWeekAscStartTimeAscIdAsc(req.coachId());
        if (req.availabilitySignature() != null) {
            String currentSignature = AvailabilityService.computeAvailabilitySignature(windows, requiredDuration);
            if (!currentSignature.equals(req.availabilitySignature())) {
                throw new OperationNotAllowedException(
                    "Coach availability changed since this view was loaded — please refresh",
                    Map.of("coach id", req.coachId()), BookingError.AVAILABILITY_CHANGED);
            }
        }
        requireSlotWithinAvailabilityWindow(req.requestedStartTime(), req.requestedEndTime(), windows,
            req.coachId(), lockedCoach.getCanonicalTimezone(),
            "Requested slot is not within coach availability",
            Map.of("requested start time", req.requestedStartTime(), "requested end time", req.requestedEndTime()));
        // Deferred-79 AC1: a CoachAvailabilityBlock was previously enforced only on the read-side
        // calendar (getAvailabilityCalendar) — a parent working off a stale view, or any client
        // calling this endpoint directly, could book a slot the coach explicitly marked unavailable.
        if (isSlotBlocked(req.requestedStartTime(), req.requestedEndTime(), req.coachId())) {
            throw new OperationNotAllowedException("Coach has blocked out this time",
                Map.of("submitted coach id", req.coachId(), "requested start time", req.requestedStartTime(),
                    "requested end time", req.requestedEndTime()),
                BookingError.SLOT_BLOCKED_BY_COACH);
        }

        requireNoOverlappingBooking(req.coachId(), req.requestedStartTime(), req.requestedEndTime());

        // Validate session pack purchase if provided (AC 8)
        if (req.sessionPackPurchaseId() != null) {
            SessionPackPurchase pack = sessionPackPurchaseRepository.findById(req.sessionPackPurchaseId())
                .orElseThrow(() -> new ResourceNotFoundException("Session pack purchase not found", "session_pack_purchase"));
            // Ownership is checked BEFORE any state check on the pack. Reversed (the original
            // order), an expired pack belonging to someone else reported "payment.packExpired"
            // while an unexpired one reported MISSING_RIGHTS and a nonexistent id reported 404 —
            // three distinguishable answers, i.e. an oracle over other parents' pack state. That
            // was invisible while every pack rejection collapsed into one generic toast; the
            // skillars-deferred-30 AC2 mapping is what made the three cases tellable apart, so the
            // ordering is fixed here rather than left to the frontend, which cannot fix it.
            // packCoachMismatch and packExhausted below were already correctly ordered.
            if (!Objects.equals(pack.getParentId(), parentId)) {
                throw new OperationNotAllowedException("Pack does not belong to this parent",
                    Map.of("submitted parent id", parentId, "submitted pack id", req.sessionPackPurchaseId()),
                    SecurityError.MISSING_RIGHTS);
            }
            if (pack.getExpiresAt().isBefore(Instant.now())) {
                throw new PaymentGatewayException("payment.packExpired");
            }
            if (!pack.getCoachId().equals(req.coachId())) {
                throw new PaymentGatewayException("payment.packCoachMismatch");
            }
            if (pack.getRemainingSessions() <= 0) {
                throw new PaymentGatewayException("payment.packExhausted");
            }
        }

        String canonicalTimezone = resolveCoachTimezone(coach);

        Booking booking = new Booking();
        booking.setParentId(parentId);
        booking.setPlayerId(req.playerId());
        booking.setCoachId(req.coachId());
        booking.setRequestedStartTime(req.requestedStartTime());
        booking.setRequestedEndTime(req.requestedEndTime());
        booking.setCanonicalTimezone(canonicalTimezone);
        // skillars-deferred-156 AC2: notes previously reached the DB column AND the coach's
        // booking-request email (below) completely unsanitized, unlike the sibling playerRequest
        // column — sanitize once here and reuse the result for both, so the off-platform-contact
        // channel (email) is covered, not just the write.
        String sanitizedNotes = sanitizeAndTruncate(contactDetailSanitizer, req.notes(), 500);
        booking.setNotes(sanitizedNotes);
        booking.setSessionPackPurchaseId(req.sessionPackPurchaseId());
        bookingRepository.save(booking);

        String coachEmail = resolveEmail(coach.getUserId(), booking.getId());
        eventPublisher.publishEvent(new BookingRequestedEvent(
            this, booking.getId(), parentId, req.playerId(), req.coachId(),
            coach.getDisplayName(), coachEmail, sanitizedNotes,
            req.requestedStartTime(), canonicalTimezone
        ));

        return toResponse(booking, coach.getDisplayName(), player.getName(), null, null, null, null);
    }

    /**
     * Coach-status gate shared by single and batch creation (B2: the batch path used to accept
     * {@code ACTIVE} only, while this path also admits {@code REDUCED}/{@code PENDING_REVIEW}).
     * Called on both the unlocked read and the locked re-read.
     */
    static void requireBookableCoachStatus(CoachProfile coach, Map<String, Object> metaData) {
        if (coach.getStatus() == CoachProfileStatus.SUSPENDED) {
            throw new OperationNotAllowedException("Coach is suspended", metaData, BookingError.COACH_UNAVAILABLE);
        }
        if (coach.getStatus() != CoachProfileStatus.ACTIVE
                && coach.getStatus() != CoachProfileStatus.REDUCED
                && coach.getStatus() != CoachProfileStatus.PENDING_REVIEW) {
            throw new OperationNotAllowedException("Coach profile is not active", metaData, BookingError.COACH_UNAVAILABLE);
        }
    }

    /** Stripe-readiness gate shared by single and batch creation (B2). */
    void requireCoachPaymentReady(UUID coachId) {
        if (!paymentGateway.isCoachPaymentReady(coachId)) {
            throw new PaymentGatewayException("payment.coachStripeNotConfigured");
        }
    }

    /**
     * Request-time overlap check against every active booking of the coach, REQUESTED included —
     * shared by single and batch creation (B2). Must run under the coach row lock: that is what
     * serialises two concurrent creates for the same slot (REQUESTED rows are outside the V87
     * exclusion constraint, so nothing else would).
     */
    void requireNoOverlappingBooking(UUID coachId, Instant start, Instant end) {
        List<Booking> overlapping = bookingRepository.findOverlappingBookings(
            coachId, start, end, ACTIVE_SLOT_STATUSES, null);
        if (!overlapping.isEmpty()) {
            throw new OperationNotAllowedException(
                "Requested slot overlaps an existing booking for this coach",
                Map.of("submitted coach id", coachId, "requested start time", start, "requested end time", end),
                BookingError.SLOT_UNAVAILABLE);
        }
    }

    /** Unlocked fast-fail on the erasure tombstone; {@link #requirePlayerBookableUnderLock} is authoritative. */
    static void requireNotErased(PlayerProfile player) {
        if (player.getDevelopmentDataErasedAt() != null) {
            throw new OperationNotAllowedException("Player can no longer be booked",
                Map.of("player id", String.valueOf(player.getId())), BookingError.PLAYER_NOT_BOOKABLE);
        }
    }

    /**
     * B2: no booking may be created for a player whose data has been erased or whose child-erasure
     * request is in flight. Takes the {@code player_profiles} row lock (via
     * {@link DevelopmentDataErasureGuard}) and must run inside the creating transaction, so the lock is
     * held until the booking row commits. Both sides of the race take that same lock:
     * <ul>
     *   <li>{@code GdprRequestService.requestChildErasure} locks it before its live-booking check and
     *       the request insert, so either this transaction sees the request row, or the erasure request
     *       sees this booking and is refused with {@code gdpr.activeBookingsExist};</li>
     *   <li>{@code GdprErasureService} locks it before stamping {@code developmentDataErasedAt}.</li>
     * </ul>
     * Lock order on the create paths is coach row, then player row; the erasure side never takes the
     * coach row, so no cycle.
     */
    void requirePlayerBookableUnderLock(Long playerId, String caller) {
        if (developmentDataErasureGuard.isErasedUnderLock(playerId, caller)
                || bookingRepository.existsInFlightChildErasureRequest(playerId)) {
            throw new OperationNotAllowedException("Player can no longer be booked",
                Map.of("player id", String.valueOf(playerId)), BookingError.PLAYER_NOT_BOOKABLE);
        }
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
        // REQUESTED (e.g. the coach already accepted a different overlapping request).
        CoachProfile lockedCoach = lockRetryer.withBoundedRetry("BookingService.acceptBooking", () -> {
            CoachProfile c = coachProfileRepository.findByIdForUpdate(coach.getId())
                .orElseThrow(() -> new ResourceNotFoundException("Coach profile not found", "coach_profile"));
            // Deferred-15 AC4: a suspended coach must not be able to accept work. The refresh is
            // required, not defensive — this row is already managed from the findByUserId above, so the
            // JPQL locked read returns that instance without overwriting its in-memory state and the
            // check could never fire. Identical reasoning to createBookingRequest:203-207. Note the
            // second-order effect this closes: a booking accepted inside suspendCoach's window would
            // move to PAYMENT_PENDING after its sweep had already read the coach's bookings, and
            // survive with a suspended coach.
            entityManager.refresh(c, LockModeType.PESSIMISTIC_WRITE);
            return c;
        });
        if (lockedCoach.getStatus() == CoachProfileStatus.SUSPENDED) {
            throw new OperationNotAllowedException("Coach is suspended",
                Map.of("submitted coach id", coach.getId()), BookingError.COACH_UNAVAILABLE);
        }
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
        try {
            acceptAndInitiatePayment(bookingId, ctx);
        } catch (OptimisticLockingFailureException e) {
            throw new OperationNotAllowedException(BookingError.CONCURRENT_MODIFICATION_MESSAGE, e, BookingError.CONCURRENT_MODIFICATION);
        }

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
        try {
            transition(bookingId, BookingEvent.DECLINE, ctx);
        } catch (OptimisticLockingFailureException e) {
            throw new OperationNotAllowedException(BookingError.CONCURRENT_MODIFICATION_MESSAGE, e, BookingError.CONCURRENT_MODIFICATION);
        }

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

        Set<Long> playerIds = bookings.stream().map(Booking::getPlayerId).collect(Collectors.toSet());
        Map<Long, String> playerNames = resolvePlayerNames(playerIds);

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

        // feat/coach-workflow-gaps A23: one batched lookup drives the per-row "Receipt" link.
        Set<UUID> receiptBookingIds = bookingIds.isEmpty()
            ? Set.of()
            : new HashSet<>(bookingPaymentRepository.findCapturedBookingIds(bookingIds));

        return bookings.stream().map(b -> {
            String coachName = coachNames.getOrDefault(b.getCoachId(), "Unknown Coach");
            String playerName = playerNames.getOrDefault(b.getPlayerId(), "Unknown Player");
            // batchSizeMap may be Map.of() (or simply lack the key) — Map.get(null) throws NPE on
            // the JDK's immutable maps, so only look up batch size when the booking is actually batched.
            Integer batchSize = b.getBatchId() != null ? batchSizeMap.get(b.getBatchId()) : null;
            return toResponse(b, coachName, playerName, null,
                pendingReschedules.get(b.getId()), b.getBatchId(), batchSize,
                receiptBookingIds.contains(b.getId()));
        }).toList();
    }

    @Transactional(readOnly = true)
    public CoachInboxResponse getCoachBookingRequests(Long coachUserId) {
        CoachProfile coach = coachProfileRepository.findByUserId(coachUserId)
            .orElseThrow(() -> new ResourceNotFoundException("Coach profile not found", "coach_profile"));

        List<Booking> bookings = bookingRepository
            .findByCoachIdAndStatusOrderByRequestedStartTimeAsc(coach.getId(), "REQUESTED");

        // Deferred-81 AC1: collect player ids across BOTH singles and every batchGroups entry
        // before either loop below, so the N+1 findById-per-row call is replaced by one
        // findAllById covering the whole response, not just one branch of it. Parent ids are
        // collected the same way — a batch group's parentId is shared by every booking in that
        // group (one basket per parent), so collecting from ALL bookings (singles and batched
        // alike) yields exactly the distinct parentId set either loop needs, no more. (Code
        // review finding, Deferred-81: the singles loop's own per-row resolveParentName call was
        // still unbatched — only the batch-group call was, per AC1's own explicit carve-out.)
        Set<Long> playerIds = bookings.stream().map(Booking::getPlayerId).collect(Collectors.toSet());
        Map<Long, String> playerNames = resolvePlayerNames(playerIds);
        Set<Long> parentIds = bookings.stream().map(Booking::getParentId).collect(Collectors.toSet());
        Map<Long, String> parentNames = resolveParentNames(parentIds);

        List<BookingResponse> singles = new java.util.ArrayList<>();
        Map<UUID, List<Booking>> batchedByBatchId = new java.util.LinkedHashMap<>();

        for (Booking b : bookings) {
            if (b.getBatchId() == null) {
                String playerName = playerNames.getOrDefault(b.getPlayerId(), "Unknown Player");
                String parentName = parentNames.getOrDefault(b.getParentId(), "Unknown Parent");
                singles.add(toResponse(b, coach.getDisplayName(), playerName, parentName, null, null, null));
            } else {
                batchedByBatchId.computeIfAbsent(b.getBatchId(), k -> new java.util.ArrayList<>()).add(b);
            }
        }

        Set<UUID> batchIds = batchedByBatchId.keySet();
        Map<UUID, String> batchStatusMap = batchIds.isEmpty() ? Map.of()
            : batchRepository.findAllById(batchIds).stream()
                .collect(Collectors.toMap(BookingBatch::getId, BookingBatch::getStatus));

        List<BatchGroupedBookingResponse> batchGroups = batchedByBatchId.entrySet().stream().map(entry -> {
            UUID batchId = entry.getKey();
            List<Booking> batchBookings = entry.getValue();
            // Deliberately the count of bookings still REQUESTED right now (this method's own query
            // already filters to status='REQUESTED'), not BookingBatch.requestedCount (the original
            // batch size at creation) — the latter goes stale the moment any sibling booking is
            // individually accepted/declined, showing a coach "Accept all 5 sessions" when only 2
            // remain. See skillars-deferred-34 code review Decision 2.
            int totalCount = batchBookings.size();
            String parentName = batchBookings.isEmpty() ? ""
                : parentNames.getOrDefault(batchBookings.get(0).getParentId(), "Unknown Parent");
            List<BookingResponse> bookingResponses = batchBookings.stream().map(b -> {
                String playerName = playerNames.getOrDefault(b.getPlayerId(), "Unknown Player");
                return toResponse(b, coach.getDisplayName(), playerName, parentName, null, batchId, totalCount);
            }).toList();
            String status = batchStatusMap.getOrDefault(batchId, "PENDING");
            return new BatchGroupedBookingResponse(batchId, parentName, totalCount, bookingResponses, status);
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

        // skillars-12-7 AC7: ONE batched lookup for whether each booking's wrap-up row exists / has recap text.
        Map<UUID, SessionCompletionData> completionByBooking = bookingIds.isEmpty()
            ? Map.of()
            : sessionCompletionDataRepository.findByBookingIdIn(bookingIds).stream()
                .collect(Collectors.toMap(SessionCompletionData::getBookingId, s -> s, (a, b2) -> a));

        return bookings.stream()
            .map(b -> {
                SessionCompletionData scd = completionByBooking.get(b.getId());
                return new ScheduleBookingItem(
                    b.getId(),
                    b.getPlayerId(),
                    resolvePlayerName(b.getPlayerId()),
                    b.getRequestedStartTime(),
                    b.getRequestedEndTime(),
                    b.getStatus(),
                    b.getCanonicalTimezone(),
                    pendingReschedules.get(b.getId()),
                    scd != null,
                    scd != null && (scd.getWhatWeDid() != null || scd.getWhatsNext() != null),
                    b.getLiveStartedAt() != null);
            })
            .toList();
    }

    @Transactional(readOnly = true)
    public ParentScheduleResponse getParentPlayerSchedule(Long parentId, Long playerId) {
        PlayerProfile player = playerProfileRepository.findById(playerId)
            .orElseThrow(() -> new ResourceNotFoundException("Player not found", "player_profile"));
        // skillars-deferred-69 AC4: a self-registered adult player has no parent (player.getParentId()
        // == null) and calls this endpoint as themselves — mirrors createBookingRequest's own
        // parentId-vs-userId branch (:170-176) rather than assuming every caller has a parent.
        if (player.getParentId() != null) {
            if (!Objects.equals(player.getParentId(), parentId)) {
                throw new ResourceNotFoundException("Player not found", "player_profile");
            }
        } else {
            if (!Objects.equals(player.getUserId(), parentId)) {
                throw new ResourceNotFoundException("Player not found", "player_profile");
            }
        }

        List<Booking> bookings = bookingRepository.findByParentIdAndPlayerIdAndStatusIn(
            parentId, playerId, List.of("CONFIRMED", "UPCOMING", "REQUESTED", "IN_PROGRESS"));

        // Deferred-81 AC1: batches this method's per-row coach-name lookup, the identical N+1
        // pattern getParentBookings' own coachNames map already avoids.
        Set<UUID> coachIds = bookings.stream().map(Booking::getCoachId).collect(Collectors.toSet());
        Map<UUID, String> coachNames = coachProfileRepository.findAllById(coachIds).stream()
            .collect(Collectors.toMap(CoachProfile::getId, CoachProfile::getDisplayName));

        List<ParentScheduleItem> items = bookings.stream().map(b -> {
            String coachName = coachNames.getOrDefault(b.getCoachId(), "Unknown Coach");
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
        rejectIfPostCompletion(booking); // skillars-deferred-106 AC9.2
        try {
            transition(bookingId, BookingEvent.CANCEL_DUE_TO_PAUSE, new TransitionContext(ActorRole.SYSTEM, null));
        } catch (OptimisticLockingFailureException e) {
            throw new OperationNotAllowedException(BookingError.CONCURRENT_MODIFICATION_MESSAGE, e, BookingError.CONCURRENT_MODIFICATION);
        }
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
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Booking " + booking.getId() + " has unrecognised status '" + booking.getStatus() + "'");
        }
    }

    /**
     * skillars-deferred-106 AC9.2: a {@code COMPLETED} booking is immutable for cancellation — the
     * coach payout has been (or is being) released, so cancelling / no-showing / refunding on top of
     * it would chase money that has left the platform. The only post-completion route is
     * {@code DisputeService} (AC10). {@code BookingStateMachine} already blocks the {@code CANCEL_*}
     * transition from {@code COMPLETED}; this fail-fast check makes the rejection an explicit,
     * i18n-coded {@code OperationNotAllowedException} (not a raw {@code BookingStateTransitionException})
     * and keeps the guard visible so a future refactor cannot open the path silently.
     */
    private void rejectIfPostCompletion(Booking booking) {
        if (BookingStatus.COMPLETED.name().equals(booking.getStatus())) {
            throw new OperationNotAllowedException(
                "A completed booking cannot be cancelled — raise a dispute instead",
                BookingError.BOOKING_ALREADY_COMPLETED);
        }
    }

    public Booking getBookingOrThrow(UUID bookingId) {
        return bookingRepository.findById(bookingId)
            .orElseThrow(() -> new ResourceNotFoundException("Booking not found", "booking"));
    }

    /**
     * skillars-deferred-155 AC5: sets or clears the player/parent-initiated "work on X next time"
     * session-prep request. {@code callerId} is checked against {@link Booking#getParentId()}
     * exactly as {@link #recordNoShowCoach} checks it — that field already stores the requesting
     * party's own userId for both a parent-created and a self-registered-player-created booking
     * (see {@link #createBookingRequest}), so this one check is already correct for both roles.
     */
    @Transactional
    public PlayerRequestResponse setPlayerRequest(UUID bookingId, Long callerId, String playerRequest) {
        Booking booking = getBookingOrThrow(bookingId);
        if (!Objects.equals(booking.getParentId(), callerId)) {
            throw new OperationNotAllowedException("Caller does not own this booking", SecurityError.MISSING_RIGHTS);
        }
        rejectIfNotEditablePlayerRequest(booking);

        // skillars-deferred-155 AC4: REDACTION's replacement text is longer than most of what it
        // matches, so sanitizing a @Size(max=500)-validated input can produce a longer string —
        // truncate after sanitizing so a save can never fail on this path.
        String sanitized = sanitizeAndTruncate(contactDetailSanitizer, playerRequest, 500);
        booking.setPlayerRequest(sanitized);
        try {
            bookingRepository.save(booking);
        } catch (OptimisticLockingFailureException e) {
            throw new OperationNotAllowedException(BookingError.CONCURRENT_MODIFICATION_MESSAGE, e, BookingError.CONCURRENT_MODIFICATION);
        }
        return new PlayerRequestResponse(booking.getId(), booking.getPlayerRequest());
    }

    /**
     * skillars-12-6 AC2: sets or clears the COACH's private plan note on a booking. Ownership is
     * resolved exactly as {@link #recordNoShowPlayer} does (coach profile id vs {@code booking.coachId}),
     * NOT the parent-id check {@link #setPlayerRequest} uses. Editability is the single
     * {@link Booking#isPrepNoteEditable} predicate, shared with the prep response's
     * {@code coachPlanNoteEditable} flag.
     *
     * <p>Concurrency: {@code saveAndFlush} (not {@code save}) so the version-checked UPDATE runs inside
     * the {@code try} and a clash with a parent's player-request edit or the reminder scheduler's
     * CONFIRMED to UPCOMING move maps to {@link BookingError#CONCURRENT_MODIFICATION} (the sibling
     * setters' contract), instead of surfacing at commit as a generic 409. Callers still handle both.
     */
    @Transactional
    public CoachPlanNoteResponse setCoachPlanNote(UUID bookingId, Long coachUserId, String planNote) {
        Booking booking = getBookingOrThrow(bookingId);
        CoachProfile coach = coachProfileRepository.findByUserId(coachUserId)
            .orElseThrow(() -> new ResourceNotFoundException("Coach profile not found", "coach_profile"));
        if (!Objects.equals(booking.getCoachId(), coach.getId())) {
            throw new OperationNotAllowedException("Coach does not own this booking", SecurityError.MISSING_RIGHTS);
        }
        Instant now = Instant.now(ClockProvider.getClock());
        if (!BookingStatus.CONFIRMED.name().equals(booking.getStatus())
                && !BookingStatus.UPCOMING.name().equals(booking.getStatus())) {
            throw new OperationNotAllowedException(
                "This booking cannot be edited right now", BookingError.BOOKING_NOT_EDITABLE);
        }
        if (!booking.isPrepNoteEditable(now)) {
            throw new OperationNotAllowedException(
                "This session has already taken place", BookingError.SESSION_ALREADY_ENDED);
        }
        booking.setCoachPlanNote(sanitizeAndTruncate(contactDetailSanitizer, planNote, 500));
        try {
            bookingRepository.saveAndFlush(booking);
        } catch (OptimisticLockingFailureException e) {
            throw new OperationNotAllowedException(BookingError.CONCURRENT_MODIFICATION_MESSAGE, e, BookingError.CONCURRENT_MODIFICATION);
        }
        return new CoachPlanNoteResponse(booking.getId(), booking.getCoachPlanNote());
    }

    /**
     * skillars-deferred-155 AC5: deliberately NOT {@link #rejectIfPostCompletion} — that guard blocks
     * only {@code COMPLETED} with a cancellation-flavoured message, which would misdescribe a
     * rejected text edit here.
     *
     * <p>skillars-deferred-155 code review, Decision 4(a): the status allow-list alone was not
     * sufficient. {@code UPCOMING} is entered ~24h out by {@code BookingReminderScheduler} and leaves
     * only via the coach's wrap-up or an admin force-complete — and nothing sweeps it
     * ({@code StuckCompletionSweeperService} only handles {@code COMPLETED_PENDING_CONFIRMATION},
     * {@code BookingExpiryScheduler} only stale pre-confirmation requests). So a session the coach never
     * completes stayed editable forever, and the coach's Session Prep Card would serve a "work on X next
     * time" request authored after the session had already happened. Bounded on
     * {@code requestedEndTime}, not {@code requestedStartTime}, so a parent submitting as the session
     * begins is not rejected; the two no-show siblings in this class carry their own
     * {@code requestedStartTime} bounds for their own different reasons. Separate error code from the
     * status rejection so the UI can say "this session has already taken place" rather than inviting a
     * retry that can never succeed.
     *
     * <p>skillars-deferred-163 AC3.3: the time check's clock source is now {@link ClockProvider},
     * matching {@code setCoachPlanNote}'s identical two-step pattern — the status check above
     * already guarantees {@link Booking#isPrepNoteEditable}'s own status half, so only its time half
     * is actually exercised here.
     */
    private void rejectIfNotEditablePlayerRequest(Booking booking) {
        if (!PLAYER_REQUEST_EDITABLE_STATUSES.contains(booking.getStatus())) {
            throw new OperationNotAllowedException(
                "This booking cannot be edited right now", BookingError.BOOKING_NOT_EDITABLE);
        }
        if (!booking.isPrepNoteEditable(Instant.now(ClockProvider.getClock()))) {
            throw new OperationNotAllowedException(
                "This session has already taken place", BookingError.SESSION_ALREADY_ENDED);
        }
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
        // Unlocked read + ownership check FIRST, locked re-read second. Deliberately in this order:
        // taking a row lock before authorising the caller lets any authenticated user pin an
        // arbitrary booking row for the duration of the transaction before receiving their 403 —
        // the exact finding Deferred-16 D2 raised against MessagingService.softDeleteMessage. One
        // extra SELECT is cheap.
        Booking unlocked = getBookingOrThrow(bookingId);
        if (!Objects.equals(unlocked.getParentId(), parentUserId)) {
            throw new OperationNotAllowedException("Parent does not own this booking", SecurityError.MISSING_RIGHTS);
        }
        Booking booking = lockRetryer.withBoundedRetry("BookingService.cancelBookingAsParent",
            () -> bookingRepository.findByIdForUpdate(bookingId)
                .orElseThrow(() -> new ResourceNotFoundException("Booking not found", "booking")));
        // Deferred-64 AC2: findByIdForUpdate returns the same managed instance already loaded by
        // getBookingOrThrow above (Hibernate's persistence-context identity map), so without this
        // refresh the "locked" read never actually re-reads the row FOR UPDATE just acquired.
        entityManager.refresh(booking, LockModeType.PESSIMISTIC_WRITE);
        rejectIfPostCompletion(booking); // skillars-deferred-106 AC9.2
        long hoursBeforeSession = ChronoUnit.HOURS.between(Instant.now(), booking.getRequestedStartTime());
        // Deferred-12 AC4: money only ever leaves the parent (credit debit / pack unit deduction)
        // once payment has been captured, i.e. from CONFIRMED onwards. Everything else — notably
        // PAYMENT_PENDING and ACCEPTED, where batch bookings briefly rest — has nothing to refund,
        // so a >24h cancellation there must not mint a BOOKING_REFUND credit or restore a pack unit.
        // Deliberately a whitelist: a blacklist would leave newly-added pre-payment statuses open.
        // This CONFIRMED/UPCOMING condition used to also be duplicated in a dead refund-tier field's
        // populating method, deleted by Deferred-33 AC7 — not adding a third refund rule here.
        BookingStatus statusBeforeCancel = readStatusOrThrow(booking);

        // UAT.3 AC2. A CAPTURE_PENDING row means a Stripe charge for this booking is in flight, or
        // has completed without its record committing. Cancelling on top of it is what produced
        // "money captured, booking cancelled, no refund" (Deferred-12 D2). Refuse and let the
        // parent retry: within seconds the booking is CONFIRMED and the ordinary refund rules
        // apply. statusBeforeCancel is read under the row lock above, so it cannot be stale by the
        // time transition(...) writes.
        if (statusBeforeCancel == BookingStatus.PAYMENT_PENDING
                && bookingPaymentRepository.findById(bookingId)
                       .filter(bp -> BookingPaymentStatus.CAPTURE_PENDING.equals(bp.getStatus())).isPresent()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "booking.paymentInProgress");
        }

        boolean paymentWasCaptured = statusBeforeCancel == BookingStatus.CONFIRMED
            || statusBeforeCancel == BookingStatus.UPCOMING;
        // Deferred-64 AC4: a parent cancellation arriving after the booking's own start time has
        // already passed is refund-eligible too — refund-only, no coach reliability strike, no
        // BookingStateMachine/event-type change. Stays entirely on the ordinary CANCEL_PARENT path.
        // fix/booking-cancel-policy-loopholes: narrowed by a grace window and a reschedule anchor —
        // see isParentCancelRefundEligible.
        boolean refundEligible = paymentWasCaptured && isParentCancelRefundEligible(
            booking.getRequestedStartTime(), booking.getOriginalStartTime(), Instant.now(),
            Duration.ofMinutes(configService.getBoundedLong(ConfigBounds.BOOKING_LATE_CANCEL_GRACE_MINUTES)));
        // C6: the refund is what the payer actually paid (resolveAmountPaid), not the coach's current
        // price; nothing is owed — and no price lookup can fail the cancel — before capture.
        BigDecimal sessionPrice = paymentWasCaptured ? resolveAmountPaid(booking) : BigDecimal.ZERO;
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

    /**
     * fix/booking-cancel-policy-loopholes — the timing half of a captured booking's parent-cancel
     * refund rule (the caller checks capture):
     * <ul>
     *   <li><b>&gt;24h notice</b>, measured from the EARLIER of the original (first confirmed) start
     *       and the current one. Otherwise a parent inside 24h reschedules further out (coach
     *       accepts) and then cancels &gt;24h before the new time for a full refund.</li>
     *   <li><b>Coach never started</b>: still CONFIRMED/UPCOMING once {@code start + grace} has
     *       passed (Deferred-64 AC4). Without the grace, start+1s qualified — the coach cannot
     *       press Start before the start time — so every &lt;24h forfeit could be dodged by waiting
     *       a second. Between start and start+grace it is an ordinary &lt;24h forfeit.</li>
     * </ul>
     */
    static boolean isParentCancelRefundEligible(Instant currentStart, Instant originalStart, Instant now,
                                                Duration lateCancelGrace) {
        Instant noticeAnchor = originalStart != null && originalStart.isBefore(currentStart)
            ? originalStart : currentStart;
        boolean enoughNotice = noticeAnchor.isAfter(now.plus(24, ChronoUnit.HOURS));
        boolean coachNeverStarted = now.isAfter(currentStart.plus(lateCancelGrace));
        return enoughNotice || coachNeverStarted;
    }

    private static final Set<String> VALID_CANCEL_REASONS = Set.of(
        "MUTUAL_AGREEMENT", "HEALTH_MEDICAL", "FAMILY_EMERGENCY",
        "WEATHER", "SCHEDULING_PREFERENCE", "OTHER_UNEXCUSED"
    );

    /**
     * C6: everything money-related is decided under the booking row lock, from the status the
     * cancellation actually leaves. {@code CANCEL_COACH} is legal from ACCEPTED (nothing charged yet: a
     * card booking is charged, and a pack booking's session deducted, only at settle), CONFIRMED,
     * UPCOMING and PAUSED (all captured). Previously the event carried no capture flag and the refund
     * listeners refunded / restored for ACCEPTED too — minting wallet credit, or a pack session, for
     * money never taken. The refund amount is what the payer actually paid ({@link #resolveAmountPaid}),
     * not the coach's current price (wrong once the coach has re-priced). The reliability strike is
     * unchanged: an accepted-then-cancelled booking still lets the family down.
     */
    @Transactional
    public void cancelBookingAsCoach(UUID bookingId, Long coachUserId, String cancelReason) {
        // Unlocked read + ownership check first, locked re-read second (cancelBookingAsParent's order:
        // an unauthorised caller must not be able to pin the row before receiving their 403).
        Booking unlocked = getBookingOrThrow(bookingId);
        CoachProfile coach = coachProfileRepository.findByUserId(coachUserId)
            .orElseThrow(() -> new ResourceNotFoundException("Coach profile not found", "coach_profile"));
        if (!Objects.equals(unlocked.getCoachId(), coach.getId())) {
            throw new OperationNotAllowedException("Coach does not own this booking", SecurityError.MISSING_RIGHTS);
        }
        if (cancelReason != null && !VALID_CANCEL_REASONS.contains(cancelReason)) {
            throw new OperationNotAllowedException("Invalid cancel reason: " + cancelReason, SecurityError.MISSING_RIGHTS);
        }
        Booking booking = lockBookingForUpdate(bookingId);
        rejectIfPostCompletion(booking); // skillars-deferred-106 AC9.2
        BookingStatus statusBeforeCancel = readStatusOrThrow(booking);
        boolean paymentCaptured = statusBeforeCancel == BookingStatus.CONFIRMED
            || statusBeforeCancel == BookingStatus.UPCOMING
            || statusBeforeCancel == BookingStatus.PAUSED;

        BigDecimal sessionPrice = paymentCaptured ? resolveAmountPaid(booking) : BigDecimal.ZERO;
        String parentEmail = resolveEmail(booking.getParentId(), bookingId);

        boolean packExpired = false;
        if (paymentCaptured && booking.getSessionPackPurchaseId() != null) {
            packExpired = sessionPackPurchaseRepository.findById(booking.getSessionPackPurchaseId())
                .map(p -> p.getExpiresAt().isBefore(Instant.now()))
                .orElse(false);
        }

        String resolvedReason = cancelReason != null ? cancelReason : "OTHER_UNEXCUSED";
        try {
            transition(bookingId, BookingEvent.CANCEL_COACH, new TransitionContext(ActorRole.COACH, coachUserId));
            booking.setCancelReason(resolvedReason);
            bookingRepository.save(booking);
        } catch (OptimisticLockingFailureException e) {
            throw new OperationNotAllowedException(BookingError.CONCURRENT_MODIFICATION_MESSAGE, e, BookingError.CONCURRENT_MODIFICATION);
        }

        eventPublisher.publishEvent(new BookingCancelledByCoachEvent(
            this, bookingId, booking.getParentId(), booking.getCoachId(),
            resolvedReason, booking.getSessionPackPurchaseId(),
            sessionPrice, packExpired, paymentCaptured, parentEmail,
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
        rejectIfPostCompletion(booking); // skillars-deferred-106 AC9.2
        // skillars-deferred-151 AC6: USER DECISION — this explicitly reverses
        // spec-coach-booking-cancel-no-show.md's prior "Never add a client-side too-early guard on
        // no-show reporting" note. That note's own reasoning ("the backend intentionally has none
        // for this endpoint") no longer holds: recordNoShowPlayer's own sibling, recordNoShowCoach
        // below, already has this exact guard, and leaving the player-facing path ungated made a
        // full-fee, no-refund, no-strike-counterweight forfeiture reportable before a session ever
        // started. Mirrors recordNoShowCoach's own check character-for-character.
        if (Instant.now().isBefore(booking.getRequestedStartTime())) {
            throw new OperationNotAllowedException(
                "Cannot report a no-show before the booking's scheduled start time",
                Map.of("requested start time", booking.getRequestedStartTime()), BookingError.NO_SHOW_TOO_EARLY);
        }
        String coachEmail = resolveCoachEmail(booking.getCoachId(), bookingId);

        try {
            transition(bookingId, BookingEvent.NO_SHOW_PLAYER, new TransitionContext(ActorRole.COACH, coachUserId));
        } catch (OptimisticLockingFailureException e) {
            throw new OperationNotAllowedException(BookingError.CONCURRENT_MODIFICATION_MESSAGE, e, BookingError.CONCURRENT_MODIFICATION);
        }

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
        rejectIfPostCompletion(booking); // skillars-deferred-106 AC9.2
        // Deferred-63 AC4: without this, a parent could report a no-show before the session's own
        // scheduled start time — before there was anything to fail to show up to — and still trigger
        // the same automatic full-refund + coach-strike consequence a genuine no-show gets.
        if (Instant.now().isBefore(booking.getRequestedStartTime())) {
            throw new OperationNotAllowedException(
                "Cannot report a no-show before the booking's scheduled start time",
                Map.of("requested start time", booking.getRequestedStartTime()), BookingError.NO_SHOW_TOO_EARLY);
        }
        // C6: NO_SHOW_COACH only leaves UPCOMING, so payment was always captured — refund what the payer
        // actually paid, not the coach's current price.
        BigDecimal sessionPrice = resolveAmountPaid(booking);
        String parentEmail = resolveEmail(parentUserId, bookingId);

        boolean packExpired = false;
        if (booking.getSessionPackPurchaseId() != null) {
            packExpired = sessionPackPurchaseRepository.findById(booking.getSessionPackPurchaseId())
                .map(p -> p.getExpiresAt().isBefore(Instant.now()))
                .orElse(false);
        }

        try {
            transition(bookingId, BookingEvent.NO_SHOW_COACH, new TransitionContext(ActorRole.PARENT, parentUserId));
        } catch (OptimisticLockingFailureException e) {
            throw new OperationNotAllowedException(BookingError.CONCURRENT_MODIFICATION_MESSAGE, e, BookingError.CONCURRENT_MODIFICATION);
        }

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

    private String resolveEmail(Long userId, UUID bookingId) {
        return userRepository.findById(userId).map(u -> u.getEmail()).orElseGet(() -> {
            log.warn("Could not resolve email for userId={} bookingId={} — notification will be skipped", userId, bookingId);
            return "";
        });
    }

    /**
     * Resolves the coach's canonical timezone for a booking, falling back to UTC when it is
     * missing or not a real IANA zone.
     *
     * <p>Nothing validates {@code coach_profiles.canonical_timezone} on its write path — the
     * profile-builder requests are {@code @NotBlank} only — and this value is stamped onto the
     * booking and rendered unguarded by several frontend pages, where an unrecognized zone throws
     * and kills the render. Falling back rather than failing keeps a parent from being blocked by
     * the coach's misconfiguration; the WARN keeps the bad row observable. Mirrors the guard
     * {@code AvailabilityService.getAvailabilityCalendar} applies to this same value.
     */
    private String resolveCoachTimezone(CoachProfile coach) {
        String timezone = coach.getCanonicalTimezone();
        if (timezone == null || timezone.isBlank()) {
            log.warn("Coach coachId={} has no canonical timezone — defaulting booking to UTC", coach.getId());
            return "UTC";
        }
        try {
            ZoneId.of(timezone);
            return timezone;
        } catch (DateTimeException e) {
            log.warn("Coach coachId={} has an invalid canonical timezone '{}' — defaulting booking to UTC",
                coach.getId(), timezone);
            return "UTC";
        }
    }

    private String resolvePlayerName(Long playerId) {
        return playerProfileRepository.findById(playerId).map(PlayerProfile::getName).orElse("Unknown Player");
    }

    private String resolveParentName(Long parentId) {
        return userRepository.findById(parentId)
            .map(this::formatParentName)
            .orElse("Unknown Parent");
    }

    private String formatParentName(User u) {
        String firstName = u.getFirstName() != null ? u.getFirstName() : "";
        String lastName = u.getLastName() != null ? u.getLastName() : "";
        String fullName = (firstName + " " + lastName).trim();
        return fullName.isEmpty() ? "Unknown Parent" : fullName;
    }

    // Deferred-81 AC1: batched counterparts of resolvePlayerName/resolveParentName for the
    // list-producing methods below, which previously issued one findById per row. Genuinely
    // single-row call sites (createBookingRequest, acceptBooking, etc.) keep calling the
    // per-id methods above unchanged — only getParentBookings/getCoachBookingRequests/
    // getParentPlayerSchedule iterate a list of rows and need a pre-built map.
    Map<Long, String> resolvePlayerNames(Collection<Long> playerIds) {
        return playerProfileRepository.findAllById(playerIds).stream()
            .collect(Collectors.toMap(PlayerProfile::getId, PlayerProfile::getName));
    }

    private Map<Long, String> resolveParentNames(Collection<Long> parentIds) {
        return userRepository.findAllById(parentIds).stream()
            .collect(Collectors.toMap(User::getId, this::formatParentName));
    }

    // skillars-deferred-156 AC2: shared by createBookingRequest's notes write and setPlayerRequest's
    // playerRequest write, which previously sanitized inconsistently (notes not at all). commons-lang3
    // is pinned at 3.21.0 (pom.xml), whose StringUtils.truncate is already surrogate-pair-aware — it
    // backs the cut index off by one rather than splitting an astral character straddling the
    // boundary — so no extra guard is needed here beyond sanitize-then-truncate in that order.
    // Package-private, not private — mirrors GdprErasureService.spendDownLockBudget's own rationale:
    // a pure static helper with no Spring/mocking dependency, so BookingServiceTest (same package)
    // can assert the surrogate-pair guarantee directly instead of only indirectly through a call site.
    static String sanitizeAndTruncate(ContactDetailSanitizer sanitizer, String input, int maxLength) {
        if (input == null || input.isBlank()) return null;
        String sanitized = sanitizer.sanitize(input).sanitized();
        return StringUtils.truncate(sanitized, maxLength);
    }

    /**
     * Package-private, not private: {@code BookingBatchService} calls this exact method rather than
     * carrying a second copy (UAT.2 AC4) — same rationale as {@code ACTIVE_SLOT_STATUSES}. A copy
     * would drift from this one's cross-midnight anchoring and invalid-timezone handling.
     *
     * {@code coachId} is passed explicitly (Deferred-60) rather than inferred from
     * {@code windows.get(0)} — every current caller already has it from the query it used to fetch
     * {@code windows}, and inferring it assumed a single-coach list that nothing in the signature
     * enforced.
     *
     * <p>skillars-deferred-140 code review (D1): {@code coachTimezone} — the coach profile's own
     * {@code canonicalTimezone} — is now the single zone every window is interpreted in, replacing
     * the previous per-window {@code w.getCanonicalTimezone()} read. That read made this method
     * disagree with {@code AvailabilityService.getAvailabilityCalendar}, which deferred-140 AC1.3
     * moved onto the profile zone: for any row whose stored zone diverged from the profile's, the
     * calendar advertised a slot this method then rejected (or hid one it would have accepted).
     * Divergent rows are expected rather than hypothetical — per-window divergence was a deliberate,
     * UI-exposed feature under deferred-63/-64, and AC1.3 deliberately shipped no backfill. The
     * window's own column is kept but is no longer read by any computational path.
     */
    boolean isSlotWithinAvailabilityWindow(Instant startTime, Instant endTime,
                                           List<CoachAvailabilityWindow> windows, UUID coachId,
                                           String coachTimezone) {
        // Null/blank guarded explicitly before ZoneId.of, mirroring
        // AvailabilityService.getAvailabilityCalendar's own outer zone derivation: ZoneId.of(null)
        // throws NullPointerException, not DateTimeException, so the catch alone would propagate out
        // of the slot check instead of falling back to UTC.
        String resolvedTimezone = (coachTimezone == null || coachTimezone.isBlank()) ? "UTC" : coachTimezone;
        ZoneId zoneId;
        try {
            zoneId = ZoneId.of(resolvedTimezone);
        } catch (DateTimeException e) {
            log.warn("Coach {} has an invalid canonicalTimezone '{}' — falling back to UTC for the "
                    + "availability-window slot check", coachId, coachTimezone);
            zoneId = ZoneId.of("UTC");
        }

        for (CoachAvailabilityWindow w : windows) {
            ZonedDateTime startZdt = startTime.atZone(zoneId);
            ZonedDateTime endZdt = endTime.atZone(zoneId);
            // Anchor window boundaries to the session's start date in the coach's timezone.
            // A session whose end falls on a different calendar day than its start is rejected
            // outright below, rather than matched against the next day's window — this method
            // does not support sessions crossing midnight.
            ZonedDateTime windowStart = w.getStartTime().atDate(startZdt.toLocalDate()).atZone(zoneId);
            ZonedDateTime windowEnd   = w.getEndTime().atDate(startZdt.toLocalDate()).atZone(zoneId);

            if (w.getDayOfWeek() == (short) startZdt.getDayOfWeek().getValue()
                && !startZdt.isBefore(windowStart)) {
                if (!endZdt.toLocalDate().equals(startZdt.toLocalDate())) {
                    throw new OperationNotAllowedException(
                        "A session cannot cross midnight",
                        Map.of("start time", startTime, "end time", endTime, "coach id", coachId),
                        BookingError.SESSION_CROSSES_MIDNIGHT);
                }
                if (!endZdt.isAfter(windowEnd)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * skillars-deferred-164 AC2. Shared check-and-throw idiom for the five call sites
     * ({@code createBookingRequest} below, plus {@code RescheduleService} ×2,
     * {@code BookingDuplicationService}, {@code BookingBatchService}) that each independently called
     * {@link #isSlotWithinAvailabilityWindow} and then threw an identically-shaped
     * {@code OperationNotAllowedException(..., BookingError.SLOT_OUTSIDE_AVAILABILITY)} on failure.
     * Only the repeated check-and-throw boilerplate is centralized — {@code message} and
     * {@code details} are still built by each caller, since the wording genuinely differs per site
     * ("Requested slot…" vs "Proposed slot…", and {@code acceptRescheduleShared} has a third,
     * {@code "submitted coach id"}, detail key the others don't). Package-private, mirroring
     * {@link #isSlotWithinAvailabilityWindow}'s own visibility and external-call pattern.
     */
    void requireSlotWithinAvailabilityWindow(Instant startTime, Instant endTime,
                                              List<CoachAvailabilityWindow> windows, UUID coachId,
                                              String coachTimezone, String message,
                                              Map<String, Object> details) {
        if (!isSlotWithinAvailabilityWindow(startTime, endTime, windows, coachId, coachTimezone)) {
            throw new OperationNotAllowedException(message, details, BookingError.SLOT_OUTSIDE_AVAILABILITY);
        }
    }

    /**
     * Deferred-79 AC1: package-private, mirrors {@link #isSlotWithinAvailabilityWindow}'s own
     * visibility and external-call pattern — called by {@code BookingBatchService},
     * {@code RescheduleService}, and {@code BookingDuplicationService} the same way. Unlike that
     * method, no caller needs the raw block list for anything else (no signature computation), so
     * the repository lookup lives entirely here rather than being fetched by each caller and passed
     * in — there is nothing for a second {@code CoachAvailabilityBlockRepository} field at any call
     * site to do.
     */
    boolean isSlotBlocked(Instant startTime, Instant endTime, UUID coachId) {
        return !coachAvailabilityBlockRepository
            .findByCoachIdAndEndDatetimeAfterAndStartDatetimeBefore(coachId, startTime, endTime)
            .isEmpty();
    }

    private BookingResponse toResponse(Booking b, String coachName, String playerName, String parentName,
                                        RescheduleRequestResponse pendingReschedule,
                                        UUID batchId, Integer batchSize) {
        return toResponse(b, coachName, playerName, parentName, pendingReschedule, batchId, batchSize, false);
    }

    private BookingResponse toResponse(Booking b, String coachName, String playerName, String parentName,
                                        RescheduleRequestResponse pendingReschedule,
                                        UUID batchId, Integer batchSize, boolean receiptAvailable) {
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
            batchSize,
            b.getPlayerRequest(),
            receiptAvailable
        );
    }
}
