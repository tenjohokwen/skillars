package com.softropic.skillars.platform.booking.service;

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
import com.softropic.skillars.platform.security.contract.exception.OperationNotAllowedException;
import com.softropic.skillars.platform.security.repo.PlayerProfile;
import com.softropic.skillars.platform.security.repo.PlayerProfileRepository;
import com.softropic.skillars.platform.security.repo.User;
import com.softropic.skillars.platform.security.repo.UserRepository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEvent;
import org.springframework.context.ApplicationEventPublisher;

import jakarta.persistence.EntityManager;

import java.math.BigDecimal;
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
    // Deferred-12 AC3: createBookingRequest re-reads the coach row under the pessimistic lock via
    // EntityManager.refresh. A mock makes that a no-op here, which is fine — the real behaviour is
    // proven by BookingServiceConcurrencyIT against a live database, as the AC requires.
    @Mock private EntityManager entityManager;

    private BookingStateMachine bookingStateMachine;
    private BookingService bookingService;

    private static final Long PARENT_ID = 100L;
    private static final Long PLAYER_ID = 200L;
    private static final UUID COACH_ID = UUID.randomUUID();
    private static final Long COACH_USER_ID = 300L;

    @BeforeEach
    void setUp() {
        bookingStateMachine = new BookingStateMachine();
        bookingService = new BookingService(
            bookingRepository, bookingStateMachine, coachProfileRepository,
            paymentGateway, coachAvailabilityWindowRepository, playerProfileRepository,
            userRepository, eventPublisher,
            rescheduleRequestRepository, bookingBatchRepository,
            sessionPackPurchaseRepository, coachPricingRepository, entityManager
        );
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
            "Europe/Berlin", null, null
        );

        assertThatThrownBy(() -> bookingService.createBookingRequest(PARENT_ID, req))
            .isInstanceOf(OperationNotAllowedException.class);
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

    @Test
    void cancelBookingAsParent_acceptedBatchBooking_cancelsWithoutRefundEligibility() {
        // ACCEPTED is transiently reachable inside the accept transaction and carries the identical
        // hole. The guard is a whitelist precisely so this state is covered without naming it.
        Booking booking = makeBooking(PARENT_ID, PLAYER_ID, COACH_ID, "ACCEPTED");
        booking.setRequestedStartTime(Instant.now().plus(72, java.time.temporal.ChronoUnit.HOURS));
        booking.setRequestedEndTime(Instant.now().plus(73, java.time.temporal.ChronoUnit.HOURS));

        when(bookingRepository.findById(booking.getId())).thenReturn(Optional.of(booking));
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
        verify(eventPublisher).publishEvent(any(BookingDeclinedEvent.class));
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
        return new CreateBookingRequest(coachId, playerId, start, end, "Europe/Berlin", "test notes", null);
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
