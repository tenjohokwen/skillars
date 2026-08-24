package com.softropic.skillars.platform.booking.service;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.softropic.skillars.platform.booking.contract.BookingAcceptedEvent;
import com.softropic.skillars.platform.booking.contract.BookingCancelledByParentEvent;
import com.softropic.skillars.platform.booking.contract.BookingError;
import com.softropic.skillars.platform.booking.contract.BookingDeclinedEvent;
import com.softropic.skillars.platform.booking.contract.BookingRequestedEvent;
import com.softropic.skillars.platform.booking.contract.BookingResponse;
import com.softropic.skillars.platform.booking.contract.BookingStateTransitionException;
import com.softropic.skillars.platform.booking.contract.CreateBookingRequest;
import com.softropic.skillars.platform.booking.repo.Booking;
import com.softropic.skillars.platform.booking.repo.BookingBatchRepository;
import com.softropic.skillars.platform.booking.repo.BookingRepository;
import com.softropic.skillars.platform.booking.repo.BookingRescheduleRequestRepository;
import com.softropic.skillars.platform.marketplace.contract.CoachProfileStatus;
import com.softropic.skillars.platform.marketplace.repo.CoachAvailabilityWindow;
import com.softropic.skillars.platform.marketplace.repo.CoachAvailabilityWindowRepository;
import com.softropic.skillars.platform.marketplace.repo.CoachProfile;
import com.softropic.skillars.platform.marketplace.repo.CoachPricing;
import com.softropic.skillars.platform.marketplace.repo.CoachPricingRepository;
import com.softropic.skillars.platform.marketplace.repo.CoachProfileRepository;
import com.softropic.skillars.platform.payment.contract.PaymentGateway;
import com.softropic.skillars.platform.payment.repo.SessionPackPurchaseRepository;
import com.softropic.skillars.platform.payment.repo.BookingPayment;
import com.softropic.skillars.platform.payment.repo.BookingPaymentRepository;
import com.softropic.skillars.platform.security.contract.exception.OperationNotAllowedException;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import com.softropic.skillars.platform.security.repo.PlayerProfile;
import com.softropic.skillars.platform.security.repo.PlayerProfileRepository;
import com.softropic.skillars.platform.security.repo.User;
import com.softropic.skillars.platform.security.repo.UserRepository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEvent;
import org.springframework.context.ApplicationEventPublisher;

import jakarta.persistence.EntityManager;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BookingServiceTest {

    @Mock private BookingRepository bookingRepository;
    @Mock private CoachProfileRepository coachProfileRepository;
    @Mock private PaymentGateway paymentGateway;
    @Mock private CoachAvailabilityWindowRepository coachAvailabilityWindowRepository;
    @Mock private PlayerProfileRepository playerProfileRepository;
    @Mock private UserRepository userRepository;
    @Mock private ApplicationEventPublisher eventPublisher;
    @Mock private BookingRescheduleRequestRepository rescheduleRequestRepository;
    @Mock private BookingBatchRepository bookingBatchRepository;
    @Mock private SessionPackPurchaseRepository sessionPackPurchaseRepository;
    @Mock private CoachPricingRepository coachPricingRepository;
    @Mock private SessionDurationResolver sessionDurationResolver;
    // UAT.3 AC2: cancelBookingAsParent refuses while a CAPTURE_PENDING row stands.
    @Mock private BookingPaymentRepository bookingPaymentRepository;
    // Deferred-12 AC3: createBookingRequest re-reads the coach row under the pessimistic lock via
    // EntityManager.refresh. A mock makes that a no-op here, which is fine — the real behaviour is
    // proven by BookingServiceConcurrencyIT against a live database, as the AC requires.
    @Mock private EntityManager entityManager;

    @Spy
    private BookingStateMachine bookingStateMachine = new BookingStateMachine();

    @InjectMocks
    private BookingService bookingService;

    private static final Long PARENT_ID = 100L;
    private static final Long PLAYER_ID = 200L;
    private static final UUID COACH_ID = UUID.randomUUID();
    private static final Long COACH_USER_ID = 300L;

    @BeforeEach
    void setUp() {
        // UAT.2 AC3: every create-path fixture in this class books exactly one hour, which is the
        // platform default. Lenient because the tests that fail before reaching the duration check
        // (unknown player, wrong parent, suspended coach, reversed range) never call it.
        lenient().when(sessionDurationResolver.resolve(COACH_ID)).thenReturn(Duration.ofHours(1));
    }

    // ---- createBookingRequest tests ----

    @Test
    void createBookingRequest_hasCredits_createsRequestedBooking() {
        PlayerProfile player = makePlayer(PLAYER_ID, PARENT_ID);
        CoachProfile coach = makeActiveCoach(COACH_ID, COACH_USER_ID);
        CoachAvailabilityWindow window = makeCoveringWindow(COACH_ID);
        Booking savedBooking = makeBooking(PARENT_ID, PLAYER_ID, COACH_ID, "REQUESTED");

        when(playerProfileRepository.findById(PLAYER_ID)).thenReturn(Optional.of(player));
        when(coachProfileRepository.findById(COACH_ID)).thenReturn(Optional.of(coach));
        when(paymentGateway.isCoachPaymentReady(COACH_ID)).thenReturn(true);
        when(coachAvailabilityWindowRepository.findByCoachId(COACH_ID)).thenReturn(List.of(window));
        when(coachProfileRepository.findByIdForUpdate(COACH_ID)).thenReturn(Optional.of(coach));
        when(bookingRepository.findOverlappingBookings(eq(COACH_ID), any(Instant.class), any(Instant.class), anyList(), any()))
            .thenReturn(List.of());
        when(bookingRepository.save(any(Booking.class))).thenReturn(savedBooking);
        when(userRepository.findById(COACH_USER_ID)).thenReturn(Optional.of(makeUser("coach@test.com")));

        CreateBookingRequest req = makeValidRequest(COACH_ID, PLAYER_ID, window);
        BookingResponse response = bookingService.createBookingRequest(PARENT_ID, req);

        assertThat(response).isNotNull();
        verify(bookingRepository).save(any(Booking.class));
        verify(eventPublisher).publishEvent(any(BookingRequestedEvent.class));
    }

    @Test
    void createBookingRequest_payPerSession_createsRequestedBooking() {
        PlayerProfile player = makePlayer(PLAYER_ID, PARENT_ID);
        CoachProfile coach = makeActiveCoach(COACH_ID, COACH_USER_ID);
        CoachAvailabilityWindow window = makeCoveringWindow(COACH_ID);
        Booking savedBooking = makeBooking(PARENT_ID, PLAYER_ID, COACH_ID, "REQUESTED");

        when(playerProfileRepository.findById(PLAYER_ID)).thenReturn(Optional.of(player));
        when(coachProfileRepository.findById(COACH_ID)).thenReturn(Optional.of(coach));
        when(paymentGateway.isCoachPaymentReady(COACH_ID)).thenReturn(true);
        when(coachAvailabilityWindowRepository.findByCoachId(COACH_ID)).thenReturn(List.of(window));
        when(coachProfileRepository.findByIdForUpdate(COACH_ID)).thenReturn(Optional.of(coach));
        when(bookingRepository.findOverlappingBookings(eq(COACH_ID), any(Instant.class), any(Instant.class), anyList(), any()))
            .thenReturn(List.of());
        when(bookingRepository.save(any(Booking.class))).thenReturn(savedBooking);
        when(userRepository.findById(COACH_USER_ID)).thenReturn(Optional.of(makeUser("coach@test.com")));

        CreateBookingRequest req = makeValidRequest(COACH_ID, PLAYER_ID, window);
        BookingResponse response = bookingService.createBookingRequest(PARENT_ID, req);

        assertThat(response).isNotNull();
        verify(bookingRepository).save(any(Booking.class));
    }

    @Test
    void createBookingRequest_noOverlap_createsRequestedBooking() {
        PlayerProfile player = makePlayer(PLAYER_ID, PARENT_ID);
        CoachProfile coach = makeActiveCoach(COACH_ID, COACH_USER_ID);
        CoachAvailabilityWindow window = makeCoveringWindow(COACH_ID);
        Booking savedBooking = makeBooking(PARENT_ID, PLAYER_ID, COACH_ID, "REQUESTED");

        when(playerProfileRepository.findById(PLAYER_ID)).thenReturn(Optional.of(player));
        when(coachProfileRepository.findById(COACH_ID)).thenReturn(Optional.of(coach));
        when(paymentGateway.isCoachPaymentReady(COACH_ID)).thenReturn(true);
        when(coachAvailabilityWindowRepository.findByCoachId(COACH_ID)).thenReturn(List.of(window));
        when(coachProfileRepository.findByIdForUpdate(COACH_ID)).thenReturn(Optional.of(coach));
        when(bookingRepository.findOverlappingBookings(eq(COACH_ID), any(Instant.class), any(Instant.class), anyList(), any()))
            .thenReturn(List.of());
        when(bookingRepository.save(any(Booking.class))).thenReturn(savedBooking);
        when(userRepository.findById(COACH_USER_ID)).thenReturn(Optional.of(makeUser("coach@test.com")));

        CreateBookingRequest req = makeValidRequest(COACH_ID, PLAYER_ID, window);
        BookingResponse response = bookingService.createBookingRequest(PARENT_ID, req);

        assertThat(response).isNotNull();
        verify(bookingRepository).save(any(Booking.class));
    }

    @Test
    void createBookingRequest_overlappingActiveBooking_throwsOperationNotAllowedException() {
        PlayerProfile player = makePlayer(PLAYER_ID, PARENT_ID);
        CoachProfile coach = makeActiveCoach(COACH_ID, COACH_USER_ID);
        CoachAvailabilityWindow window = makeCoveringWindow(COACH_ID);
        Booking conflicting = makeBooking(PARENT_ID, 999L, COACH_ID, "ACCEPTED");

        when(playerProfileRepository.findById(PLAYER_ID)).thenReturn(Optional.of(player));
        when(coachProfileRepository.findById(COACH_ID)).thenReturn(Optional.of(coach));
        when(paymentGateway.isCoachPaymentReady(COACH_ID)).thenReturn(true);
        when(coachAvailabilityWindowRepository.findByCoachId(COACH_ID)).thenReturn(List.of(window));
        when(coachProfileRepository.findByIdForUpdate(COACH_ID)).thenReturn(Optional.of(coach));
        when(bookingRepository.findOverlappingBookings(eq(COACH_ID), any(Instant.class), any(Instant.class), anyList(), any()))
            .thenReturn(List.of(conflicting));

        CreateBookingRequest req = makeValidRequest(COACH_ID, PLAYER_ID, window);

        assertThatThrownBy(() -> bookingService.createBookingRequest(PARENT_ID, req))
            .isInstanceOf(OperationNotAllowedException.class)
            .satisfies(ex -> assertThat(((OperationNotAllowedException) ex).getErrorCode())
                .isEqualTo(BookingError.SLOT_UNAVAILABLE));
        verify(bookingRepository, never()).save(any(Booking.class));
        // Pins down the exact status list (mirrors BookingService.ACTIVE_SLOT_STATUSES) so a
        // regression that narrowed/widened it wouldn't pass silently under a looser anyList() stub.
        verify(bookingRepository).findOverlappingBookings(eq(COACH_ID), any(Instant.class), any(Instant.class),
            eq(List.of("REQUESTED", "ACCEPTED", "PAYMENT_PENDING", "CONFIRMED", "UPCOMING", "IN_PROGRESS", "PAUSED")),
            isNull());
    }

    @Test
    void createBookingRequest_playerNotOwnedByParent_throwsOperationNotAllowedException() {
        PlayerProfile player = makePlayer(PLAYER_ID, 999L);  // owned by different parent

        when(playerProfileRepository.findById(PLAYER_ID)).thenReturn(Optional.of(player));

        CreateBookingRequest req = makeValidRequest(COACH_ID, PLAYER_ID, makeCoveringWindow(COACH_ID));

        assertThatThrownBy(() -> bookingService.createBookingRequest(PARENT_ID, req))
            .isInstanceOf(OperationNotAllowedException.class);
    }

    // ---- UAT.5 AC1: self-registered adult PLAYER can book for themselves ----

    /**
     * The XOR "else" branch: a self-registered player's own userId lands in booking.parent_id, the
     * opaque-id shortcut this story is built around.
     */
    @Test
    void createBookingRequest_selfRegisteredPlayerBooksForThemselves_succeedsAndWritesOwnUserIdAsParentId() {
        Long selfPlayerUserId = 500L;
        PlayerProfile selfPlayer = makeSelfPlayer(PLAYER_ID, selfPlayerUserId);
        CoachProfile coach = makeActiveCoach(COACH_ID, COACH_USER_ID);
        CoachAvailabilityWindow window = makeCoveringWindow(COACH_ID);
        Booking savedBooking = makeBooking(selfPlayerUserId, PLAYER_ID, COACH_ID, "REQUESTED");

        when(playerProfileRepository.findById(PLAYER_ID)).thenReturn(Optional.of(selfPlayer));
        when(coachProfileRepository.findById(COACH_ID)).thenReturn(Optional.of(coach));
        when(paymentGateway.isCoachPaymentReady(COACH_ID)).thenReturn(true);
        when(coachAvailabilityWindowRepository.findByCoachId(COACH_ID)).thenReturn(List.of(window));
        when(coachProfileRepository.findByIdForUpdate(COACH_ID)).thenReturn(Optional.of(coach));
        when(bookingRepository.findOverlappingBookings(eq(COACH_ID), any(Instant.class), any(Instant.class), anyList(), any()))
            .thenReturn(List.of());
        when(userRepository.findById(COACH_USER_ID)).thenReturn(Optional.of(makeUser("coach@test.com")));
        ArgumentCaptor<Booking> bookingCaptor = ArgumentCaptor.forClass(Booking.class);
        when(bookingRepository.save(bookingCaptor.capture())).thenReturn(savedBooking);

        CreateBookingRequest req = makeValidRequest(COACH_ID, PLAYER_ID, window);
        BookingResponse response = bookingService.createBookingRequest(selfPlayerUserId, req);

        assertThat(response).isNotNull();
        assertThat(bookingCaptor.getValue().getParentId())
            .as("a self-booking player's own userId is written into the opaque parent_id column")
            .isEqualTo(selfPlayerUserId);
    }

    /** Self-registered player must not be able to book using someone else's playerId. */
    @Test
    void createBookingRequest_selfRegisteredPlayerUsesSomeoneElsesPlayerId_isRejected() {
        Long selfPlayerUserId = 500L;
        // A different player's profile, self-owned by a different user
        PlayerProfile someoneElsesPlayer = makeSelfPlayer(PLAYER_ID, 999L);
        when(playerProfileRepository.findById(PLAYER_ID)).thenReturn(Optional.of(someoneElsesPlayer));

        CreateBookingRequest req = makeValidRequest(COACH_ID, PLAYER_ID, makeCoveringWindow(COACH_ID));

        assertThatThrownBy(() -> bookingService.createBookingRequest(selfPlayerUserId, req))
            .isInstanceOf(OperationNotAllowedException.class)
            .hasMessageContaining("does not own this profile");
        verify(bookingRepository, never()).save(any(Booking.class));
    }

    /**
     * Exercises the "if" branch from a parent caller against a self-owned player: the parent's id
     * can never satisfy player.getUserId(), so this must be rejected too. Mutation check: deleting
     * the else branch entirely collapses both this test and the one above onto the same (wrong)
     * "if" comparison, so at least one must fail — this test pins the specific rejection reason.
     */
    @Test
    void createBookingRequest_parentAttemptsToBookASelfOwnedPlayer_isRejected() {
        PlayerProfile selfOwnedPlayer = makeSelfPlayer(PLAYER_ID, 500L);
        when(playerProfileRepository.findById(PLAYER_ID)).thenReturn(Optional.of(selfOwnedPlayer));

        CreateBookingRequest req = makeValidRequest(COACH_ID, PLAYER_ID, makeCoveringWindow(COACH_ID));

        assertThatThrownBy(() -> bookingService.createBookingRequest(PARENT_ID, req))
            .isInstanceOf(OperationNotAllowedException.class)
            .hasMessageContaining("does not own this profile");
        verify(bookingRepository, never()).save(any(Booking.class));
    }

    @Test
    void createBookingRequest_coachInDraftStatus_throwsOperationNotAllowedException() {
        PlayerProfile player = makePlayer(PLAYER_ID, PARENT_ID);
        CoachProfile coach = makeDraftCoach(COACH_ID, COACH_USER_ID);

        when(playerProfileRepository.findById(PLAYER_ID)).thenReturn(Optional.of(player));
        when(coachProfileRepository.findById(COACH_ID)).thenReturn(Optional.of(coach));

        CreateBookingRequest req = makeValidRequest(COACH_ID, PLAYER_ID, makeCoveringWindow(COACH_ID));

        assertThatThrownBy(() -> bookingService.createBookingRequest(PARENT_ID, req))
            .isInstanceOf(OperationNotAllowedException.class);
    }

    @Test
    void createBookingRequest_slotOutsideAvailabilityWindows_throwsOperationNotAllowedException() {
        PlayerProfile player = makePlayer(PLAYER_ID, PARENT_ID);
        CoachProfile coach = makeActiveCoach(COACH_ID, COACH_USER_ID);

        when(playerProfileRepository.findById(PLAYER_ID)).thenReturn(Optional.of(player));
        when(coachProfileRepository.findById(COACH_ID)).thenReturn(Optional.of(coach));
        when(paymentGateway.isCoachPaymentReady(COACH_ID)).thenReturn(true);
        when(coachAvailabilityWindowRepository.findByCoachId(COACH_ID)).thenReturn(Collections.emptyList());

        CreateBookingRequest req = makeValidRequest(COACH_ID, PLAYER_ID, makeCoveringWindow(COACH_ID));

        assertThatThrownBy(() -> bookingService.createBookingRequest(PARENT_ID, req))
            .isInstanceOf(OperationNotAllowedException.class);
    }

    @Test
    void isSlotWithinAvailabilityWindow_everyWindowHasInvalidTimezone_logsDistinctSummaryWarn() {
        CoachAvailabilityWindow badWindow = makeCoveringWindow(COACH_ID);
        badWindow.setCanonicalTimezone("not-a-zone");
        List<CoachAvailabilityWindow> windows = List.of(badWindow);

        Instant start = Instant.now();
        Instant end = start.plusSeconds(3600);

        Logger serviceLogger = (Logger) LoggerFactory.getLogger(BookingService.class);
        ListAppender<ILoggingEvent> logCapture = new ListAppender<>();
        logCapture.start();
        serviceLogger.addAppender(logCapture);
        boolean result;
        try {
            result = bookingService.isSlotWithinAvailabilityWindow(start, end, windows, COACH_ID);
        } finally {
            serviceLogger.detachAppender(logCapture);
        }

        assertThat(result).isFalse();
        assertThat(logCapture.list)
            .as("must emit a distinct summary WARN for the all-windows-invalid-timezone case")
            .anySatisfy(event -> {
                assertThat(event.getLevel()).isEqualTo(Level.WARN);
                assertThat(event.getFormattedMessage())
                    .contains(COACH_ID.toString())
                    .contains("1 availability window(s)")
                    .contains("none had a valid timezone");
            });
    }

    @Test
    void isSlotWithinAvailabilityWindow_emptyWindowList_doesNotLogSummaryWarn() {
        List<CoachAvailabilityWindow> windows = List.of();

        Instant start = Instant.now();
        Instant end = start.plusSeconds(3600);

        Logger serviceLogger = (Logger) LoggerFactory.getLogger(BookingService.class);
        ListAppender<ILoggingEvent> logCapture = new ListAppender<>();
        logCapture.start();
        serviceLogger.addAppender(logCapture);
        boolean result;
        try {
            result = bookingService.isSlotWithinAvailabilityWindow(start, end, windows, COACH_ID);
        } finally {
            serviceLogger.detachAppender(logCapture);
        }

        assertThat(result).isFalse();
        assertThat(logCapture.list)
            .as("an empty window list has no coach id to report and must not emit the summary WARN")
            .noneSatisfy(event -> assertThat(event.getFormattedMessage()).contains("none had a valid timezone"));
    }

    @Test
    void isSlotWithinAvailabilityWindow_mixedValidAndInvalidTimezoneWindows_doesNotLogSummaryWarn() {
        CoachAvailabilityWindow validWindow = makeCoveringWindow(COACH_ID);
        CoachAvailabilityWindow badWindow = makeCoveringWindow(COACH_ID);
        badWindow.setCanonicalTimezone("not-a-zone");
        List<CoachAvailabilityWindow> windows = List.of(validWindow, badWindow);

        Instant start = Instant.now();
        Instant end = start.plusSeconds(3600);

        Logger serviceLogger = (Logger) LoggerFactory.getLogger(BookingService.class);
        ListAppender<ILoggingEvent> logCapture = new ListAppender<>();
        logCapture.start();
        serviceLogger.addAppender(logCapture);
        try {
            bookingService.isSlotWithinAvailabilityWindow(start, end, windows, COACH_ID);
        } finally {
            serviceLogger.detachAppender(logCapture);
        }

        assertThat(logCapture.list)
            .as("at least one valid-timezone window means this is not the all-invalid case")
            .noneSatisfy(event -> assertThat(event.getFormattedMessage()).contains("none had a valid timezone"));
    }

    @Test
    void createBookingRequest_requestedStartTimeInPast_throwsOperationNotAllowedException() {
        PlayerProfile player = makePlayer(PLAYER_ID, PARENT_ID);
        CoachProfile coach = makeActiveCoach(COACH_ID, COACH_USER_ID);

        when(playerProfileRepository.findById(PLAYER_ID)).thenReturn(Optional.of(player));
        when(coachProfileRepository.findById(COACH_ID)).thenReturn(Optional.of(coach));
        when(paymentGateway.isCoachPaymentReady(COACH_ID)).thenReturn(true);

        // Request with past start time — service throws before reaching window check
        Instant pastTime = Instant.now().minusSeconds(3600);
        CreateBookingRequest req = new CreateBookingRequest(
            COACH_ID, PLAYER_ID, pastTime, pastTime.plusSeconds(3600),
            null, null
        );

        assertThatThrownBy(() -> bookingService.createBookingRequest(PARENT_ID, req))
            .isInstanceOf(OperationNotAllowedException.class);
    }

    // ---- UAT.2 AC3: session-duration enforcement on the single-booking create path ----

    /**
     * The defect P0-5 describes: a 09:00–17:00 window used to render as ONE clickable row, so one
     * click booked eight hours for one credit and locked the coach's whole day.
     */
    @Test
    void createBookingRequest_longerThanTheCoachSessionLength_isRejected() {
        stubUpToDurationCheck();

        ZonedDateTime slotStart = ZonedDateTime.now(ZoneId.of("Europe/Berlin"))
            .plusDays(1).withHour(9).withMinute(0).withSecond(0).withNano(0);
        CreateBookingRequest req = new CreateBookingRequest(
            COACH_ID, PLAYER_ID, slotStart.toInstant(), slotStart.plusHours(8).toInstant(), null, null);

        assertThatThrownBy(() -> bookingService.createBookingRequest(PARENT_ID, req))
            .isInstanceOf(OperationNotAllowedException.class)
            .hasMessageContaining("session length");
        // Rejected before the window query and before the pessimistic lock, so a malformed request
        // costs neither. Both verifications fail if the check is moved after the window lookup.
        verify(coachAvailabilityWindowRepository, never()).findByCoachId(COACH_ID);
        verify(coachProfileRepository, never()).findByIdForUpdate(COACH_ID);
    }

    @Test
    void createBookingRequest_shorterThanTheCoachSessionLength_isRejected() {
        stubUpToDurationCheck();

        ZonedDateTime slotStart = ZonedDateTime.now(ZoneId.of("Europe/Berlin"))
            .plusDays(1).withHour(10).withMinute(0).withSecond(0).withNano(0);
        CreateBookingRequest req = new CreateBookingRequest(
            COACH_ID, PLAYER_ID, slotStart.toInstant(), slotStart.plusMinutes(30).toInstant(), null, null);

        assertThatThrownBy(() -> bookingService.createBookingRequest(PARENT_ID, req))
            .isInstanceOf(OperationNotAllowedException.class)
            .hasMessageContaining("session length");
    }

    /** A coach who overrode their length to 90 minutes accepts 90 and rejects the platform 60. */
    @Test
    void createBookingRequest_coachOverrideOfNinety_acceptsNinety() {
        PlayerProfile player = makePlayer(PLAYER_ID, PARENT_ID);
        CoachProfile coach = makeActiveCoach(COACH_ID, COACH_USER_ID);
        CoachAvailabilityWindow window = makeCoveringWindow(COACH_ID);
        Booking savedBooking = makeBooking(PARENT_ID, PLAYER_ID, COACH_ID, "REQUESTED");

        when(playerProfileRepository.findById(PLAYER_ID)).thenReturn(Optional.of(player));
        when(coachProfileRepository.findById(COACH_ID)).thenReturn(Optional.of(coach));
        when(paymentGateway.isCoachPaymentReady(COACH_ID)).thenReturn(true);
        when(sessionDurationResolver.resolve(COACH_ID)).thenReturn(Duration.ofMinutes(90));
        when(coachAvailabilityWindowRepository.findByCoachId(COACH_ID)).thenReturn(List.of(window));
        when(coachProfileRepository.findByIdForUpdate(COACH_ID)).thenReturn(Optional.of(coach));
        when(bookingRepository.findOverlappingBookings(eq(COACH_ID), any(Instant.class), any(Instant.class), anyList(), any()))
            .thenReturn(List.of());
        when(bookingRepository.save(any(Booking.class))).thenReturn(savedBooking);
        when(userRepository.findById(COACH_USER_ID)).thenReturn(Optional.of(makeUser("coach@test.com")));

        ZonedDateTime slotStart = ZonedDateTime.now(ZoneId.of("Europe/Berlin"))
            .plusDays(1).withHour(10).withMinute(0).withSecond(0).withNano(0);

        assertThat(bookingService.createBookingRequest(PARENT_ID, new CreateBookingRequest(
            COACH_ID, PLAYER_ID, slotStart.toInstant(), slotStart.plusMinutes(90).toInstant(), null, null)))
            .isNotNull();
    }

    /**
     * The other half of the override rule, kept as its own test so a red run names which half
     * broke: with a 90-minute coach the PLATFORM default of 60 must be rejected, not silently
     * accepted because 60 is "the normal length".
     */
    @Test
    void createBookingRequest_coachOverrideOfNinety_rejectsSixty() {
        stubUpToDurationCheck();
        when(sessionDurationResolver.resolve(COACH_ID)).thenReturn(Duration.ofMinutes(90));

        ZonedDateTime slotStart = ZonedDateTime.now(ZoneId.of("Europe/Berlin"))
            .plusDays(1).withHour(10).withMinute(0).withSecond(0).withNano(0);

        assertThatThrownBy(() -> bookingService.createBookingRequest(PARENT_ID, new CreateBookingRequest(
            COACH_ID, PLAYER_ID, slotStart.toInstant(), slotStart.plusMinutes(60).toInstant(), null, null)))
            .isInstanceOf(OperationNotAllowedException.class)
            .hasMessageContaining("session length");
    }

    /** Stubs exactly the lookups createBookingRequest performs before the duration check. */
    private void stubUpToDurationCheck() {
        when(playerProfileRepository.findById(PLAYER_ID))
            .thenReturn(Optional.of(makePlayer(PLAYER_ID, PARENT_ID)));
        when(coachProfileRepository.findById(COACH_ID))
            .thenReturn(Optional.of(makeActiveCoach(COACH_ID, COACH_USER_ID)));
        when(paymentGateway.isCoachPaymentReady(COACH_ID)).thenReturn(true);
    }

    // ---- acceptBooking tests ----

    @Test
    void acceptBooking_requestedBooking_transitionsToPaymentPending() {
        Booking booking = makeBooking(PARENT_ID, PLAYER_ID, COACH_ID, "REQUESTED");
        CoachProfile coach = makeActiveCoach(COACH_ID, COACH_USER_ID);

        when(bookingRepository.findById(booking.getId())).thenReturn(Optional.of(booking));
        when(coachProfileRepository.findByUserId(COACH_USER_ID)).thenReturn(Optional.of(coach));
        when(coachProfileRepository.findByIdForUpdate(COACH_ID)).thenReturn(Optional.of(coach));
        when(bookingRepository.findOverlappingBookings(eq(COACH_ID), any(Instant.class), any(Instant.class), anyList(), any()))
            .thenReturn(List.of());
        when(bookingRepository.save(any(Booking.class))).thenReturn(booking);
        when(userRepository.findById(PARENT_ID)).thenReturn(Optional.of(makeUser("parent@test.com")));
        when(playerProfileRepository.findById(PLAYER_ID)).thenReturn(Optional.of(makePlayer(PLAYER_ID, PARENT_ID)));
        when(coachPricingRepository.findByCoachId(COACH_ID)).thenReturn(Optional.of(makeCoachPricing(new BigDecimal("50.00"))));

        bookingService.acceptBooking(booking.getId(), COACH_USER_ID);

        assertThat(booking.getStatus()).isEqualTo("PAYMENT_PENDING");
        verify(eventPublisher).publishEvent(any(BookingAcceptedEvent.class));
    }

    /**
     * Deferred-15 AC4, review follow-up. The equivalent check in BookingBatchService and
     * RescheduleService each got a fast mock-based test; this one was reachable only through the
     * thread-based BookingServiceConcurrencyIT and the end-to-end SuspendedCoachBookingBlockIT.
     * Note what this test does NOT prove: entityManager is a mock here, so refresh() is a no-op and
     * the locked instance is simply whatever findByIdForUpdate returns. That the refresh is load
     * bearing against a real persistence context is proven only by the IT.
     */
    @Test
    void acceptBooking_suspendedCoach_throwsCoachUnavailable() {
        Booking booking = makeBooking(PARENT_ID, PLAYER_ID, COACH_ID, "REQUESTED");
        CoachProfile coach = makeActiveCoach(COACH_ID, COACH_USER_ID);
        CoachProfile suspended = makeActiveCoach(COACH_ID, COACH_USER_ID);
        suspended.setStatus(CoachProfileStatus.SUSPENDED);

        when(bookingRepository.findById(booking.getId())).thenReturn(Optional.of(booking));
        when(coachProfileRepository.findByUserId(COACH_USER_ID)).thenReturn(Optional.of(coach));
        when(coachProfileRepository.findByIdForUpdate(COACH_ID)).thenReturn(Optional.of(suspended));

        assertThatThrownBy(() -> bookingService.acceptBooking(booking.getId(), COACH_USER_ID))
            .isInstanceOf(OperationNotAllowedException.class)
            .satisfies(e -> assertThat(((OperationNotAllowedException) e).getErrorCode())
                .isEqualTo(BookingError.COACH_UNAVAILABLE));

        assertThat(booking.getStatus()).isEqualTo("REQUESTED");
        verify(eventPublisher, never()).publishEvent(any(BookingAcceptedEvent.class));
    }

    @Test
    void acceptBooking_alreadyDeclined_throwsBookingStateTransitionException() {
        Booking booking = makeBooking(PARENT_ID, PLAYER_ID, COACH_ID, "DECLINED");
        CoachProfile coach = makeActiveCoach(COACH_ID, COACH_USER_ID);

        when(bookingRepository.findById(booking.getId())).thenReturn(Optional.of(booking));
        when(coachProfileRepository.findByUserId(COACH_USER_ID)).thenReturn(Optional.of(coach));
        when(coachProfileRepository.findByIdForUpdate(COACH_ID)).thenReturn(Optional.of(coach));
        when(bookingRepository.findOverlappingBookings(eq(COACH_ID), any(Instant.class), any(Instant.class), anyList(), any()))
            .thenReturn(List.of());

        assertThatThrownBy(() -> bookingService.acceptBooking(booking.getId(), COACH_USER_ID))
            .isInstanceOf(BookingStateTransitionException.class);
    }

    @Test
    void acceptBooking_overlappingConfirmedBooking_throwsOperationNotAllowedException() {
        Booking booking = makeBooking(PARENT_ID, PLAYER_ID, COACH_ID, "REQUESTED");
        CoachProfile coach = makeActiveCoach(COACH_ID, COACH_USER_ID);
        Booking conflicting = makeBooking(PARENT_ID, 999L, COACH_ID, "CONFIRMED");

        when(bookingRepository.findById(booking.getId())).thenReturn(Optional.of(booking));
        when(coachProfileRepository.findByUserId(COACH_USER_ID)).thenReturn(Optional.of(coach));
        when(coachProfileRepository.findByIdForUpdate(COACH_ID)).thenReturn(Optional.of(coach));
        when(bookingRepository.findOverlappingBookings(eq(COACH_ID), any(Instant.class), any(Instant.class), anyList(), any()))
            .thenReturn(List.of(conflicting));

        assertThatThrownBy(() -> bookingService.acceptBooking(booking.getId(), COACH_USER_ID))
            .isInstanceOf(OperationNotAllowedException.class)
            .satisfies(ex -> assertThat(((OperationNotAllowedException) ex).getErrorCode())
                .isEqualTo(BookingError.SLOT_UNAVAILABLE));
        verify(bookingRepository, never()).save(any(Booking.class));
        // Pins down that REQUESTED is excluded (mirrors BookingService's exclusion list) and that
        // the booking being accepted excludes itself from the overlap match (self-match guard).
        verify(bookingRepository).findOverlappingBookings(eq(COACH_ID), any(Instant.class), any(Instant.class),
            eq(List.of("ACCEPTED", "PAYMENT_PENDING", "CONFIRMED", "UPCOMING", "IN_PROGRESS", "PAUSED")),
            eq(booking.getId()));
    }

    @Test
    void acceptBooking_retriedOnAlreadyAcceptedBooking_doesNotSelfMatchAsOverlap() {
        Booking booking = makeBooking(PARENT_ID, PLAYER_ID, COACH_ID, "PAYMENT_PENDING");
        CoachProfile coach = makeActiveCoach(COACH_ID, COACH_USER_ID);

        when(bookingRepository.findById(booking.getId())).thenReturn(Optional.of(booking));
        when(coachProfileRepository.findByUserId(COACH_USER_ID)).thenReturn(Optional.of(coach));
        when(coachProfileRepository.findByIdForUpdate(COACH_ID)).thenReturn(Optional.of(coach));
        // Simulate the real query excluding the booking's own id: it would not appear here.
        when(bookingRepository.findOverlappingBookings(eq(COACH_ID), any(Instant.class), any(Instant.class), anyList(), eq(booking.getId())))
            .thenReturn(List.of());

        assertThatThrownBy(() -> bookingService.acceptBooking(booking.getId(), COACH_USER_ID))
            .isInstanceOf(BookingStateTransitionException.class);
    }

    // ---- cancelBookingAsParent tests (Deferred-12 AC4) ----

    @Test
    void cancelBookingAsParent_paymentPendingBooking_cancelsWithoutRefundEligibility() {
        // A booking stuck in PAYMENT_PENDING (crash between the INITIATE_PAYMENT commit and the
        // AFTER_COMMIT payment listener) must be cancellable — and must not mint a refund, because
        // nothing was ever captured and the pack unit has not been deducted yet.
        Booking booking = makeBooking(PARENT_ID, PLAYER_ID, COACH_ID, "PAYMENT_PENDING");
        booking.setRequestedStartTime(Instant.now().plus(72, java.time.temporal.ChronoUnit.HOURS));
        booking.setRequestedEndTime(Instant.now().plus(73, java.time.temporal.ChronoUnit.HOURS));

        when(bookingRepository.findById(booking.getId())).thenReturn(Optional.of(booking));
        // UAT.3 AC2: the status is now read under a row lock, so both reads are exercised.
        when(bookingRepository.findByIdForUpdate(booking.getId())).thenReturn(Optional.of(booking));
        // The ONLY one of the three cancel tests that reaches this lookup: it sits behind
        // `statusBeforeCancel == PAYMENT_PENDING &&`, and Java && short-circuits. Stubbing it in
        // the ACCEPTED/CONFIRMED tests would fail them with UnnecessaryStubbingException.
        when(bookingPaymentRepository.findById(booking.getId())).thenReturn(Optional.empty());
        when(coachPricingRepository.findByCoachId(COACH_ID)).thenReturn(Optional.of(makeCoachPricing(new BigDecimal("50.00"))));
        when(userRepository.findById(PARENT_ID)).thenReturn(Optional.of(makeUser("parent@test.com")));
        when(coachProfileRepository.findById(COACH_ID)).thenReturn(Optional.of(makeActiveCoach(COACH_ID, COACH_USER_ID)));
        when(userRepository.findById(COACH_USER_ID)).thenReturn(Optional.of(makeUser("coach@test.com")));
        when(bookingRepository.save(any(Booking.class))).thenReturn(booking);

        bookingService.cancelBookingAsParent(booking.getId(), PARENT_ID);

        assertThat(booking.getStatus()).isEqualTo("CANCELLED_PARENT");
        assertThat(capturedParentCancellation().isRefundEligible())
            .as("no payment was captured from PAYMENT_PENDING — refunding would credit money never taken")
            .isFalse();
    }

    /**
     * UAT.3 AC2 — the interlock, and the mutation check Task 6 requires. A CAPTURE_PENDING row
     * means a Stripe charge for this booking is in flight or has completed without its record
     * committing; cancelling on top of it is what produced "money captured, booking cancelled, no
     * refund" (Deferred-12 D2).
     *
     * <p>The assertions discriminate against the specific wrong behaviour rather than against
     * "something threw": a 409 status AND the booking still resting in PAYMENT_PENDING AND no
     * cancellation event published. Delete the CAPTURE_PENDING branch and this fails because the
     * cancel SUCCEEDS — no exception at all — not because a different exception surfaced.
     */
    @Test
    void cancelBookingAsParent_captureInFlight_refuses409AndLeavesBookingPending() {
        Booking booking = makeBooking(PARENT_ID, PLAYER_ID, COACH_ID, "PAYMENT_PENDING");
        booking.setRequestedStartTime(Instant.now().plus(72, java.time.temporal.ChronoUnit.HOURS));
        booking.setRequestedEndTime(Instant.now().plus(73, java.time.temporal.ChronoUnit.HOURS));

        BookingPayment reserved = new BookingPayment();
        reserved.setBookingId(booking.getId());
        reserved.setStatus("CAPTURE_PENDING");

        when(bookingRepository.findById(booking.getId())).thenReturn(Optional.of(booking));
        when(bookingRepository.findByIdForUpdate(booking.getId())).thenReturn(Optional.of(booking));
        when(bookingPaymentRepository.findById(booking.getId())).thenReturn(Optional.of(reserved));
        // The rest of the cancel path is stubbed lenient ON PURPOSE, so that removing the interlock
        // makes this cancel SUCCEED rather than trip over an unstubbed collaborator. Without these,
        // the mutation would fail the test with a ResourceNotFoundException from coach pricing —
        // i.e. it would only prove "something threw", which is not the bar. With them, the mutation
        // fails it with "Expecting code to raise a throwable" plus a CANCELLED_PARENT status: the
        // specific wrong behaviour this test exists to catch.
        lenient().when(coachPricingRepository.findByCoachId(COACH_ID))
            .thenReturn(Optional.of(makeCoachPricing(new BigDecimal("50.00"))));
        lenient().when(userRepository.findById(PARENT_ID)).thenReturn(Optional.of(makeUser("parent@test.com")));
        lenient().when(coachProfileRepository.findById(COACH_ID))
            .thenReturn(Optional.of(makeActiveCoach(COACH_ID, COACH_USER_ID)));
        lenient().when(userRepository.findById(COACH_USER_ID)).thenReturn(Optional.of(makeUser("coach@test.com")));
        lenient().when(bookingRepository.save(any(Booking.class))).thenReturn(booking);

        assertThatThrownBy(() -> bookingService.cancelBookingAsParent(booking.getId(), PARENT_ID))
            .isInstanceOf(ResponseStatusException.class)
            .satisfies(t -> {
                assertThat(((ResponseStatusException) t).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
                assertThat(((ResponseStatusException) t).getReason()).isEqualTo("booking.paymentInProgress");
            });

        assertThat(booking.getStatus())
            .as("the cancel must not commit while a capture may already have reached Stripe")
            .isEqualTo("PAYMENT_PENDING");
        verify(bookingRepository, never()).save(any(Booking.class));
        verify(eventPublisher, never()).publishEvent(any(BookingCancelledByParentEvent.class));
    }

    /**
     * UAT.3 AC2 regression guard: the interlock keys on CAPTURE_PENDING specifically. A terminal
     * row must not block the cancel, or a settled booking would become permanently uncancellable.
     */
    @Test
    void cancelBookingAsParent_terminalPaymentRow_doesNotBlockTheCancel() {
        Booking booking = makeBooking(PARENT_ID, PLAYER_ID, COACH_ID, "PAYMENT_PENDING");
        booking.setRequestedStartTime(Instant.now().plus(72, java.time.temporal.ChronoUnit.HOURS));
        booking.setRequestedEndTime(Instant.now().plus(73, java.time.temporal.ChronoUnit.HOURS));

        BookingPayment failed = new BookingPayment();
        failed.setBookingId(booking.getId());
        failed.setStatus("CHARGE_FAILED");

        when(bookingRepository.findById(booking.getId())).thenReturn(Optional.of(booking));
        when(bookingRepository.findByIdForUpdate(booking.getId())).thenReturn(Optional.of(booking));
        when(bookingPaymentRepository.findById(booking.getId())).thenReturn(Optional.of(failed));
        when(coachPricingRepository.findByCoachId(COACH_ID)).thenReturn(Optional.of(makeCoachPricing(new BigDecimal("50.00"))));
        when(userRepository.findById(PARENT_ID)).thenReturn(Optional.of(makeUser("parent@test.com")));
        when(coachProfileRepository.findById(COACH_ID)).thenReturn(Optional.of(makeActiveCoach(COACH_ID, COACH_USER_ID)));
        when(userRepository.findById(COACH_USER_ID)).thenReturn(Optional.of(makeUser("coach@test.com")));
        when(bookingRepository.save(any(Booking.class))).thenReturn(booking);

        bookingService.cancelBookingAsParent(booking.getId(), PARENT_ID);

        assertThat(booking.getStatus()).isEqualTo("CANCELLED_PARENT");
    }

    /**
     * UAT.3 AC2: the ownership check must still run BEFORE the row lock is taken, so a stranger
     * cannot pin an arbitrary booking row for the length of the transaction on their way to a 403
     * (the Deferred-16 D2 finding). Proven by the locked read never being reached.
     */
    @Test
    void cancelBookingAsParent_wrongParent_isRejectedBeforeTakingTheRowLock() {
        Booking booking = makeBooking(PARENT_ID, PLAYER_ID, COACH_ID, "PAYMENT_PENDING");
        when(bookingRepository.findById(booking.getId())).thenReturn(Optional.of(booking));

        assertThatThrownBy(() -> bookingService.cancelBookingAsParent(booking.getId(), 999_999L))
            .isInstanceOf(OperationNotAllowedException.class);

        verify(bookingRepository, never()).findByIdForUpdate(any());
    }

    // Deferred-33 AC2: a corrupted/unrecognised status column value means the row exists but the
    // server cannot interpret it — 409, not 404. A 404 would tell a caller who can already see the
    // booking in their own list to stop looking for something that is, in fact, right there.
    @Test
    void cancelBookingAsParent_corruptedStatusColumn_returns409NotResourceNotFound() {
        Booking booking = makeBooking(PARENT_ID, PLAYER_ID, COACH_ID, "BOGUS_STATUS");
        when(bookingRepository.findById(booking.getId())).thenReturn(Optional.of(booking));
        when(bookingRepository.findByIdForUpdate(booking.getId())).thenReturn(Optional.of(booking));

        assertThatThrownBy(() -> bookingService.cancelBookingAsParent(booking.getId(), PARENT_ID))
            .isInstanceOf(ResponseStatusException.class)
            .satisfies(ex -> {
                ResponseStatusException rse = (ResponseStatusException) ex;
                assertThat(rse.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
                // Proves the "honest answer" claim from AC2, not just the status code: the message
                // must actually name the unrecognised status, not read like a generic not-found.
                assertThat(rse.getReason())
                    .contains(booking.getId().toString())
                    .contains("unrecognised status")
                    .contains("BOGUS_STATUS");
            });
    }

    @Test
    void cancelBookingAsParent_acceptedBatchBooking_cancelsWithoutRefundEligibility() {
        // ACCEPTED is transiently reachable inside the accept transaction and carries the identical
        // hole. The guard is a whitelist precisely so this state is covered without naming it.
        Booking booking = makeBooking(PARENT_ID, PLAYER_ID, COACH_ID, "ACCEPTED");
        booking.setRequestedStartTime(Instant.now().plus(72, java.time.temporal.ChronoUnit.HOURS));
        booking.setRequestedEndTime(Instant.now().plus(73, java.time.temporal.ChronoUnit.HOURS));

        when(bookingRepository.findById(booking.getId())).thenReturn(Optional.of(booking));
        // UAT.3 AC2: locked re-read. No bookingPaymentRepository stub — the payment lookup sits
        // behind `statusBeforeCancel == PAYMENT_PENDING &&` and is never reached from this status.
        when(bookingRepository.findByIdForUpdate(booking.getId())).thenReturn(Optional.of(booking));
        when(coachPricingRepository.findByCoachId(COACH_ID)).thenReturn(Optional.of(makeCoachPricing(new BigDecimal("50.00"))));
        when(userRepository.findById(PARENT_ID)).thenReturn(Optional.of(makeUser("parent@test.com")));
        when(coachProfileRepository.findById(COACH_ID)).thenReturn(Optional.of(makeActiveCoach(COACH_ID, COACH_USER_ID)));
        when(userRepository.findById(COACH_USER_ID)).thenReturn(Optional.of(makeUser("coach@test.com")));
        when(bookingRepository.save(any(Booking.class))).thenReturn(booking);

        bookingService.cancelBookingAsParent(booking.getId(), PARENT_ID);

        assertThat(booking.getStatus()).isEqualTo("CANCELLED_PARENT");
        assertThat(capturedParentCancellation().isRefundEligible()).isFalse();
    }

    @Test
    void cancelBookingAsParent_confirmedBookingMoreThan24hOut_staysRefundEligible() {
        // Regression guard: the whitelist must not break the legitimate refund path.
        Booking booking = makeBooking(PARENT_ID, PLAYER_ID, COACH_ID, "CONFIRMED");
        booking.setRequestedStartTime(Instant.now().plus(72, java.time.temporal.ChronoUnit.HOURS));
        booking.setRequestedEndTime(Instant.now().plus(73, java.time.temporal.ChronoUnit.HOURS));

        when(bookingRepository.findById(booking.getId())).thenReturn(Optional.of(booking));
        // UAT.3 AC2: locked re-read. No bookingPaymentRepository stub — the payment lookup sits
        // behind `statusBeforeCancel == PAYMENT_PENDING &&` and is never reached from this status.
        when(bookingRepository.findByIdForUpdate(booking.getId())).thenReturn(Optional.of(booking));
        when(coachPricingRepository.findByCoachId(COACH_ID)).thenReturn(Optional.of(makeCoachPricing(new BigDecimal("50.00"))));
        when(userRepository.findById(PARENT_ID)).thenReturn(Optional.of(makeUser("parent@test.com")));
        when(coachProfileRepository.findById(COACH_ID)).thenReturn(Optional.of(makeActiveCoach(COACH_ID, COACH_USER_ID)));
        when(userRepository.findById(COACH_USER_ID)).thenReturn(Optional.of(makeUser("coach@test.com")));
        when(bookingRepository.save(any(Booking.class))).thenReturn(booking);

        bookingService.cancelBookingAsParent(booking.getId(), PARENT_ID);

        assertThat(booking.getStatus()).isEqualTo("CANCELLED_PARENT");
        assertThat(capturedParentCancellation().isRefundEligible()).isTrue();
    }

    private BookingCancelledByParentEvent capturedParentCancellation() {
        // Must be an ApplicationEvent captor, not Object: BookingCancelledByParentEvent extends
        // ApplicationEvent, so the production call binds to publishEvent(ApplicationEvent) and a
        // verify() on the publishEvent(Object) overload would never match.
        ArgumentCaptor<ApplicationEvent> captor = ArgumentCaptor.forClass(ApplicationEvent.class);
        verify(eventPublisher, org.mockito.Mockito.atLeastOnce()).publishEvent(captor.capture());
        return captor.getAllValues().stream()
            .filter(BookingCancelledByParentEvent.class::isInstance)
            .map(BookingCancelledByParentEvent.class::cast)
            .reduce((first, second) -> second)
            .orElseThrow(() -> new AssertionError("No BookingCancelledByParentEvent published"));
    }

    // ---- declineBooking tests ----

    @Test
    void declineBooking_requestedBooking_transitionsToDeclined() {
        Booking booking = makeBooking(PARENT_ID, PLAYER_ID, COACH_ID, "REQUESTED");
        CoachProfile coach = makeActiveCoach(COACH_ID, COACH_USER_ID);

        when(bookingRepository.findById(booking.getId())).thenReturn(Optional.of(booking));
        when(coachProfileRepository.findByUserId(COACH_USER_ID)).thenReturn(Optional.of(coach));
        when(bookingRepository.save(any(Booking.class))).thenReturn(booking);
        when(userRepository.findById(PARENT_ID)).thenReturn(Optional.of(makeUser("parent@test.com")));

        bookingService.declineBooking(booking.getId(), COACH_USER_ID);

        assertThat(booking.getStatus()).isEqualTo("DECLINED");

        // Must be an ApplicationEvent captor, not BookingDeclinedEvent: declineBooking also
        // publishes a BookingStatusChangedEvent via transition(), and both types bind to the same
        // publishEvent(ApplicationEvent) overload — a narrow-type captor's implicit times(1) would
        // throw TooManyActualInvocations. Same pattern as capturedParentCancellation() above.
        ArgumentCaptor<ApplicationEvent> captor = ArgumentCaptor.forClass(ApplicationEvent.class);
        verify(eventPublisher, org.mockito.Mockito.atLeastOnce()).publishEvent(captor.capture());
        BookingDeclinedEvent declinedEvent = captor.getAllValues().stream()
            .filter(BookingDeclinedEvent.class::isInstance)
            .map(BookingDeclinedEvent.class::cast)
            .reduce((first, second) -> second)
            .orElseThrow(() -> new AssertionError("No BookingDeclinedEvent published"));
        assertThat(declinedEvent.getCanonicalTimezone()).isEqualTo(booking.getCanonicalTimezone());
    }

    @Test
    void declineBooking_confirmedBooking_throwsBookingStateTransitionException() {
        Booking booking = makeBooking(PARENT_ID, PLAYER_ID, COACH_ID, "CONFIRMED");
        CoachProfile coach = makeActiveCoach(COACH_ID, COACH_USER_ID);

        when(bookingRepository.findById(booking.getId())).thenReturn(Optional.of(booking));
        when(coachProfileRepository.findByUserId(COACH_USER_ID)).thenReturn(Optional.of(coach));

        assertThatThrownBy(() -> bookingService.declineBooking(booking.getId(), COACH_USER_ID))
            .isInstanceOf(BookingStateTransitionException.class);
    }

    @Test
    void declineBooking_upcomingBooking_throwsBookingStateTransitionException() {
        Booking booking = makeBooking(PARENT_ID, PLAYER_ID, COACH_ID, "UPCOMING");
        CoachProfile coach = makeActiveCoach(COACH_ID, COACH_USER_ID);

        when(bookingRepository.findById(booking.getId())).thenReturn(Optional.of(booking));
        when(coachProfileRepository.findByUserId(COACH_USER_ID)).thenReturn(Optional.of(coach));

        assertThatThrownBy(() -> bookingService.declineBooking(booking.getId(), COACH_USER_ID))
            .isInstanceOf(BookingStateTransitionException.class);
    }

    // ---- helpers ----

    private PlayerProfile makePlayer(Long id, Long parentId) {
        PlayerProfile p = new PlayerProfile();
        p.setName("Test Player");
        try {
            var f = com.softropic.skillars.infrastructure.persistence.BaseEntity.class.getDeclaredField("id");
            f.setAccessible(true);
            f.set(p, id);
        } catch (Exception ignored) {}
        p.setParentId(parentId);
        return p;
    }

    /** UAT.5: a self-registered adult player — parentId null, userId set (the chk_pp_owner XOR). */
    private PlayerProfile makeSelfPlayer(Long id, Long userId) {
        PlayerProfile p = new PlayerProfile();
        p.setName("Self Player");
        try {
            var f = com.softropic.skillars.infrastructure.persistence.BaseEntity.class.getDeclaredField("id");
            f.setAccessible(true);
            f.set(p, id);
        } catch (Exception ignored) {}
        p.setUserId(userId);
        return p;
    }

    private CoachProfile makeActiveCoach(UUID coachId, Long userId) {
        CoachProfile c = new CoachProfile();
        try {
            var f = CoachProfile.class.getDeclaredField("id");
            f.setAccessible(true);
            f.set(c, coachId);
        } catch (Exception ignored) {}
        c.setUserId(userId);
        c.setDisplayName("Test Coach");
        c.setStatus(CoachProfileStatus.ACTIVE);
        c.setCanonicalTimezone("Europe/Berlin");
        return c;
    }

    private CoachProfile makeDraftCoach(UUID coachId, Long userId) {
        CoachProfile c = makeActiveCoach(coachId, userId);
        c.setStatus(CoachProfileStatus.DRAFT);
        return c;
    }

    private CoachAvailabilityWindow makeCoveringWindow(UUID coachId) {
        // Creates a window covering the next Monday 10:00–12:00 in Europe/Berlin
        CoachAvailabilityWindow w = new CoachAvailabilityWindow();
        w.setCoachId(coachId);
        // Use all 7 days to ensure coverage
        ZonedDateTime futureSlot = ZonedDateTime.now(ZoneId.of("Europe/Berlin")).plusDays(1);
        w.setDayOfWeek((short) futureSlot.getDayOfWeek().getValue());
        w.setStartTime(LocalTime.of(8, 0));
        w.setEndTime(LocalTime.of(18, 0));
        w.setCanonicalTimezone("Europe/Berlin");
        return w;
    }

    private CreateBookingRequest makeValidRequest(UUID coachId, Long playerId, CoachAvailabilityWindow window) {
        ZonedDateTime slotStart = ZonedDateTime.now(ZoneId.of("Europe/Berlin"))
            .plusDays(1)
            .withHour(10).withMinute(0).withSecond(0).withNano(0);
        Instant start = slotStart.toInstant();
        Instant end = slotStart.plusHours(1).toInstant();
        return new CreateBookingRequest(coachId, playerId, start, end, "test notes", null);
    }

    private Booking makeBooking(Long parentId, Long playerId, UUID coachId, String status) {
        Booking b = new Booking();
        try {
            var f = Booking.class.getDeclaredField("id");
            f.setAccessible(true);
            f.set(b, UUID.randomUUID());
        } catch (Exception ignored) {}
        b.setParentId(parentId);
        b.setPlayerId(playerId);
        b.setCoachId(coachId);
        b.setStatus(status);
        b.setRequestedStartTime(Instant.now().plusSeconds(7200));
        b.setRequestedEndTime(Instant.now().plusSeconds(10800));
        b.setCanonicalTimezone("Europe/Berlin");
        return b;
    }

    private CoachPricing makeCoachPricing(BigDecimal price) {
        CoachPricing p = new CoachPricing();
        p.setCoachId(COACH_ID);
        p.setPerSessionPrice(price);
        return p;
    }

    private User makeUser(String email) {
        User u = new User();
        try {
            var emailField = User.class.getDeclaredField("login");
            emailField.setAccessible(true);
            emailField.set(u, email);
            var emailField2 = User.class.getDeclaredField("email");
            emailField2.setAccessible(true);
            emailField2.set(u, email);
        } catch (Exception ignored) {}
        return u;
    }
}
