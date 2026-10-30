package com.softropic.skillars.platform.booking.service;

import ch.qos.logback.classic.Level;
import com.softropic.skillars.infrastructure.exception.ResourceNotFoundException;
import com.softropic.skillars.infrastructure.persistence.PessimisticLockRetryer;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.softropic.skillars.platform.booking.contract.BookingAcceptedEvent;
import com.softropic.skillars.platform.booking.contract.BookingCancelledByCoachEvent;
import com.softropic.skillars.platform.booking.contract.PlayerNoShowEvent;
import com.softropic.skillars.platform.booking.contract.BookingCancelledByParentEvent;
import com.softropic.skillars.platform.booking.contract.BookingError;
import com.softropic.skillars.platform.booking.contract.BookingDeclinedEvent;
import com.softropic.skillars.platform.booking.contract.BookingRequestedEvent;
import com.softropic.skillars.platform.booking.contract.BookingResponse;
import com.softropic.skillars.platform.booking.contract.BookingStateTransitionException;
import com.softropic.skillars.platform.booking.contract.CoachInboxResponse;
import com.softropic.skillars.platform.booking.contract.CreateBookingRequest;
import com.softropic.skillars.platform.booking.contract.ParentScheduleItem;
import com.softropic.skillars.platform.booking.contract.ParentScheduleResponse;
import com.softropic.skillars.platform.booking.contract.CoachPlanNoteResponse;
import com.softropic.skillars.platform.booking.contract.PlayerRequestResponse;
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
import com.softropic.skillars.platform.booking.contract.AdminBookingCancellationOutcome;
import com.softropic.skillars.platform.booking.contract.BookingCancelledByAdminEvent;
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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.OptimisticLockingFailureException;

import jakarta.persistence.EntityManager;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
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
    @Mock private com.softropic.skillars.platform.booking.repo.CoachAvailabilityBlockRepository coachAvailabilityBlockRepository;
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
    @Mock private PessimisticLockRetryer lockRetryer;
    @Mock private com.softropic.skillars.infrastructure.sanitizer.ContactDetailSanitizer contactDetailSanitizer;
    // B2: createBookingRequest re-checks the erasure tombstone under the player row lock.
    @Mock private com.softropic.skillars.platform.security.service.DevelopmentDataErasureGuard developmentDataErasureGuard;
    // fix/booking-cancel-policy-loopholes: late-cancel grace window.
    @Mock private com.softropic.skillars.platform.config.service.ConfigService configService;

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
        lenient().when(lockRetryer.withBoundedRetry(anyString(), any()))
            .thenAnswer(inv -> ((java.util.function.Supplier<?>) inv.getArgument(1)).get());
        // UAT.2 AC3: every create-path fixture in this class books exactly one hour, which is the
        // platform default. Lenient because the tests that fail before reaching the duration check
        // (unknown player, wrong parent, suspended coach, reversed range) never call it.
        lenient().when(sessionDurationResolver.resolve(COACH_ID)).thenReturn(Duration.ofHours(1));
        // fix/booking-price-and-excused-race: a card/credit request is refused when the coach has no
        // pricing, so the create-path fixtures default to a priced coach. Lenient for the same reason as
        // above; tests that need a different (or no) price override it.
        lenient().when(coachPricingRepository.findByCoachId(COACH_ID))
            .thenReturn(Optional.of(makeCoachPricing(new BigDecimal("50.00"))));
        // skillars-deferred-155 AC5: passthrough default — tests exercising the actual redaction/
        // truncation wiring override this explicitly.
        lenient().when(contactDetailSanitizer.sanitize(anyString())).thenAnswer(
            inv -> new com.softropic.skillars.infrastructure.sanitizer.ContactDetailSanitizer.SanitizerResult(
                inv.getArgument(0), false));
        lenient().when(configService.getBoundedLong(
                com.softropic.skillars.platform.config.service.ConfigBounds.BOOKING_LATE_CANCEL_GRACE_MINUTES))
            .thenReturn(30L);
        // fix/booking-request-consent (M2): the coded defaults, so the create-path fixtures (a few days
        // out, nothing else pending) pass the horizon and pending-request cap unless a test overrides them.
        lenient().when(configService.getBoundedLong(
                com.softropic.skillars.platform.config.service.ConfigBounds.BOOKING_REQUEST_MAX_HORIZON_DAYS))
            .thenReturn(120L);
        lenient().when(configService.getBoundedLong(
                com.softropic.skillars.platform.config.service.ConfigBounds.BOOKING_REQUEST_MAX_PENDING_PER_COACH))
            .thenReturn(10L);
        lenient().when(configService.getBoundedLong(
                com.softropic.skillars.platform.config.service.ConfigBounds.BOOKING_PLAYER_NO_SHOW_GRACE_MINUTES))
            .thenReturn(15L);
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
        when(coachAvailabilityWindowRepository.findByCoachIdOrderByDayOfWeekAscStartTimeAscIdAsc(COACH_ID)).thenReturn(List.of(window));
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
        // B2: the authoritative erasure re-check ran under the player row lock.
        verify(developmentDataErasureGuard).isErasedUnderLock(PLAYER_ID, "BookingService.createBookingRequest");
        verify(bookingRepository).existsInFlightChildErasureRequest(PLAYER_ID);
    }

    // ---- B2: erased / erasure-pending players cannot be booked ----

    @Test
    void createBookingRequest_playerAlreadyErased_isRejectedBeforeAnyLock() {
        PlayerProfile player = makePlayer(PLAYER_ID, PARENT_ID);
        player.setDevelopmentDataErasedAt(Instant.now().minusSeconds(60));
        when(playerProfileRepository.findById(PLAYER_ID)).thenReturn(Optional.of(player));

        CreateBookingRequest req = makeValidRequest(COACH_ID, PLAYER_ID, makeCoveringWindow(COACH_ID));
        assertThatThrownBy(() -> bookingService.createBookingRequest(PARENT_ID, req))
            .isInstanceOf(OperationNotAllowedException.class)
            .satisfies(e -> assertThat(((OperationNotAllowedException) e).getErrorCode())
                .isEqualTo(BookingError.PLAYER_NOT_BOOKABLE));
        verify(coachProfileRepository, never()).findByIdForUpdate(any());
        verify(bookingRepository, never()).save(any());
    }

    @Test
    void createBookingRequest_playerErasedUnderLock_isRejected() {
        stubCreatePathUpToPlayerLock();
        when(developmentDataErasureGuard.isErasedUnderLock(PLAYER_ID, "BookingService.createBookingRequest"))
            .thenReturn(true);

        CreateBookingRequest req = makeValidRequest(COACH_ID, PLAYER_ID, makeCoveringWindow(COACH_ID));
        assertThatThrownBy(() -> bookingService.createBookingRequest(PARENT_ID, req))
            .isInstanceOf(OperationNotAllowedException.class)
            .satisfies(e -> assertThat(((OperationNotAllowedException) e).getErrorCode())
                .isEqualTo(BookingError.PLAYER_NOT_BOOKABLE));
        verify(bookingRepository, never()).save(any());
    }

    @Test
    void createBookingRequest_childErasureRequestInFlight_isRejected() {
        stubCreatePathUpToPlayerLock();
        when(bookingRepository.existsInFlightChildErasureRequest(PLAYER_ID)).thenReturn(true);

        CreateBookingRequest req = makeValidRequest(COACH_ID, PLAYER_ID, makeCoveringWindow(COACH_ID));
        assertThatThrownBy(() -> bookingService.createBookingRequest(PARENT_ID, req))
            .isInstanceOf(OperationNotAllowedException.class)
            .satisfies(e -> assertThat(((OperationNotAllowedException) e).getErrorCode())
                .isEqualTo(BookingError.PLAYER_NOT_BOOKABLE));
        verify(bookingRepository, never()).save(any());
    }

    private void stubCreatePathUpToPlayerLock() {
        CoachProfile coach = makeActiveCoach(COACH_ID, COACH_USER_ID);
        when(playerProfileRepository.findById(PLAYER_ID)).thenReturn(Optional.of(makePlayer(PLAYER_ID, PARENT_ID)));
        when(coachProfileRepository.findById(COACH_ID)).thenReturn(Optional.of(coach));
        when(paymentGateway.isCoachPaymentReady(COACH_ID)).thenReturn(true);
        when(coachProfileRepository.findByIdForUpdate(COACH_ID)).thenReturn(Optional.of(coach));
    }

    // ---- B2/C2: admin cancellation (coach suspension / safety hold) goes through the state machine ----

    private java.util.List<org.springframework.context.ApplicationEvent> publishedEvents() {
        org.mockito.ArgumentCaptor<org.springframework.context.ApplicationEvent> events =
            org.mockito.ArgumentCaptor.forClass(org.springframework.context.ApplicationEvent.class);
        verify(eventPublisher, org.mockito.Mockito.atLeastOnce()).publishEvent(events.capture());
        return events.getAllValues();
    }

    private BookingCancelledByAdminEvent adminEvent() {
        return publishedEvents().stream()
            .filter(BookingCancelledByAdminEvent.class::isInstance)
            .map(BookingCancelledByAdminEvent.class::cast)
            .findFirst().orElseThrow();
    }

    @Test
    void cancelBookingAsAdmin_requestedBooking_cancelsWithoutARefund_andPublishesStatusChange() {
        Booking booking = makeBooking(PARENT_ID, PLAYER_ID, COACH_ID, "REQUESTED");
        when(bookingRepository.findByIdForUpdate(booking.getId())).thenReturn(Optional.of(booking));

        AdminBookingCancellationOutcome outcome =
            bookingService.cancelBookingAsAdmin(booking.getId(), 1L, "COACH_SUSPENDED_BY_ADMIN");

        assertThat(outcome).isEqualTo(AdminBookingCancellationOutcome.CANCELLED);
        assertThat(booking.getStatus()).isEqualTo("CANCELLED");
        assertThat(booking.getCancelReason()).isEqualTo("COACH_SUSPENDED_BY_ADMIN");
        assertThat(publishedEvents()).anySatisfy(e -> assertThat(e).isInstanceOfSatisfying(
            com.softropic.skillars.platform.booking.contract.BookingStatusChangedEvent.class,
            sc -> assertThat(sc.newStatus()).isEqualTo("CANCELLED")));
        // C2: nothing was captured for a REQUESTED booking — no refund, no pack restore.
        BookingCancelledByAdminEvent event = adminEvent();
        assertThat(event.isRefundEligible()).isFalse();
        assertThat(event.isWalletRefund()).isFalse();
        assertThat(event.isPackRestore()).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"ACCEPTED", "PAYMENT_PENDING"})
    void cancelBookingAsAdmin_preCaptureBooking_cancelsWithoutARefund(String status) {
        Booking booking = makeBooking(PARENT_ID, PLAYER_ID, COACH_ID, status);
        when(bookingRepository.findByIdForUpdate(booking.getId())).thenReturn(Optional.of(booking));

        assertThat(bookingService.cancelBookingAsAdmin(booking.getId(), 1L, "COACH_SUSPENDED_BY_ADMIN"))
            .isEqualTo(AdminBookingCancellationOutcome.CANCELLED);

        assertThat(booking.getStatus()).isEqualTo("CANCELLED");
        assertThat(adminEvent().isRefundEligible()).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"CONFIRMED", "UPCOMING"})
    void cancelBookingAsAdmin_capturedCardBooking_refundsWhatWasActuallyPaid(String status) {
        Booking booking = makeBooking(PARENT_ID, PLAYER_ID, COACH_ID, status);
        when(bookingRepository.findByIdForUpdate(booking.getId())).thenReturn(Optional.of(booking));
        BookingPayment bp = new BookingPayment();
        bp.setBookingId(booking.getId());
        bp.setStatus("CAPTURED");
        bp.setCreditDebited(new BigDecimal("12.00"));
        bp.setStripeCharged(new BigDecimal("28.00"));
        when(bookingPaymentRepository.findById(booking.getId())).thenReturn(Optional.of(bp));

        assertThat(bookingService.cancelBookingAsAdmin(booking.getId(), 1L, "COACH_UNAVAILABLE"))
            .isEqualTo(AdminBookingCancellationOutcome.CANCELLED);

        BookingCancelledByAdminEvent event = adminEvent();
        assertThat(event.isWalletRefund()).isTrue();
        assertThat(event.getRefundAmount()).isEqualByComparingTo("40.00");
        // The coach's current price is never consulted when the payment row says what was paid.
        verify(coachPricingRepository, never()).findByCoachId(any());
    }

    @Test
    void cancelBookingAsAdmin_capturedPackBooking_restoresTheSession_orRefundsAnExpiredPack() {
        UUID packId = UUID.randomUUID();
        Booking live = makeBooking(PARENT_ID, PLAYER_ID, COACH_ID, "CONFIRMED");
        live.setSessionPackPurchaseId(packId);
        com.softropic.skillars.platform.payment.repo.SessionPackPurchase pack =
            new com.softropic.skillars.platform.payment.repo.SessionPackPurchase();
        pack.setExpiresAt(Instant.now().minusSeconds(60));
        pack.setPricePerSession(new BigDecimal("30.00"));
        when(sessionPackPurchaseRepository.findById(packId)).thenReturn(Optional.of(pack));
        when(bookingRepository.findByIdForUpdate(live.getId())).thenReturn(Optional.of(live));

        bookingService.cancelBookingAsAdmin(live.getId(), 1L, "COACH_SUSPENDED_BY_ADMIN");

        BookingCancelledByAdminEvent event = adminEvent();
        assertThat(event.isPackExpiredAtCancellation()).isTrue();
        assertThat(event.isPackRestore()).isFalse();
        assertThat(event.isWalletRefund()).isTrue();
        assertThat(event.getRefundAmount()).isEqualByComparingTo("30.00");
    }

    @Test
    void cancelBookingAsAdmin_paymentPendingWithACaptureInFlight_isLeftUntouched() {
        Booking booking = makeBooking(PARENT_ID, PLAYER_ID, COACH_ID, "PAYMENT_PENDING");
        when(bookingRepository.findByIdForUpdate(booking.getId())).thenReturn(Optional.of(booking));
        BookingPayment bp = new BookingPayment();
        bp.setBookingId(booking.getId());
        bp.setStatus("CAPTURE_PENDING");
        when(bookingPaymentRepository.findById(booking.getId())).thenReturn(Optional.of(bp));

        assertThat(bookingService.cancelBookingAsAdmin(booking.getId(), 1L, "COACH_SUSPENDED_BY_ADMIN"))
            .isEqualTo(AdminBookingCancellationOutcome.PAYMENT_IN_PROGRESS);

        assertThat(booking.getStatus()).isEqualTo("PAYMENT_PENDING");
        verify(bookingRepository, never()).save(any());
        verify(eventPublisher, never()).publishEvent(any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"IN_PROGRESS", "PAUSED"})
    void cancelBookingAsAdmin_liveSession_isReportedNotCancelled(String status) {
        Booking booking = makeBooking(PARENT_ID, PLAYER_ID, COACH_ID, status);
        when(bookingRepository.findByIdForUpdate(booking.getId())).thenReturn(Optional.of(booking));

        assertThat(bookingService.cancelBookingAsAdmin(booking.getId(), 1L, "COACH_UNAVAILABLE"))
            .isEqualTo(AdminBookingCancellationOutcome.LIVE_SESSION);

        assertThat(booking.getStatus()).isEqualTo(status);
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    void cancelBookingAsAdmin_bookingAlreadyTerminal_isSkippedWithoutThrowing() {
        Booking booking = makeBooking(PARENT_ID, PLAYER_ID, COACH_ID, "CANCELLED_PARENT");
        when(bookingRepository.findByIdForUpdate(booking.getId())).thenReturn(Optional.of(booking));

        assertThat(bookingService.cancelBookingAsAdmin(booking.getId(), 1L, "COACH_SUSPENDED_BY_ADMIN"))
            .isEqualTo(AdminBookingCancellationOutcome.NOT_CANCELLABLE);

        assertThat(booking.getStatus()).isEqualTo("CANCELLED_PARENT");
        verify(bookingRepository, never()).save(any());
        verify(eventPublisher, never()).publishEvent(any());
    }

    // skillars-deferred-156 AC2: notes previously reached both the DB write AND the coach's
    // booking-request email completely unsanitized, unlike the sibling playerRequest column —
    // assert the sanitized value (not the raw input) reaches both the saved Booking and the
    // published BookingRequestedEvent.
    @Test
    void createBookingRequest_notesContainsContactDetails_persistsAndPublishesSanitizedNotes() {
        PlayerProfile player = makePlayer(PLAYER_ID, PARENT_ID);
        CoachProfile coach = makeActiveCoach(COACH_ID, COACH_USER_ID);
        CoachAvailabilityWindow window = makeCoveringWindow(COACH_ID);
        Booking savedBooking = makeBooking(PARENT_ID, PLAYER_ID, COACH_ID, "REQUESTED");

        when(playerProfileRepository.findById(PLAYER_ID)).thenReturn(Optional.of(player));
        when(coachProfileRepository.findById(COACH_ID)).thenReturn(Optional.of(coach));
        when(paymentGateway.isCoachPaymentReady(COACH_ID)).thenReturn(true);
        when(coachAvailabilityWindowRepository.findByCoachIdOrderByDayOfWeekAscStartTimeAscIdAsc(COACH_ID)).thenReturn(List.of(window));
        when(coachProfileRepository.findByIdForUpdate(COACH_ID)).thenReturn(Optional.of(coach));
        when(bookingRepository.findOverlappingBookings(eq(COACH_ID), any(Instant.class), any(Instant.class), anyList(), any()))
            .thenReturn(List.of());
        when(bookingRepository.save(any(Booking.class))).thenReturn(savedBooking);
        when(userRepository.findById(COACH_USER_ID)).thenReturn(Optional.of(makeUser("coach@test.com")));
        when(contactDetailSanitizer.sanitize("call me at 555-123-4567"))
            .thenReturn(new com.softropic.skillars.infrastructure.sanitizer.ContactDetailSanitizer.SanitizerResult(
                "call me at [contact details removed]", true));

        ZonedDateTime slotStart = ZonedDateTime.now(ZoneId.of("Europe/Berlin"))
            .plusDays(1).withHour(10).withMinute(0).withSecond(0).withNano(0);
        CreateBookingRequest req = new CreateBookingRequest(COACH_ID, PLAYER_ID, slotStart.toInstant(),
            slotStart.plusHours(1).toInstant(), "call me at 555-123-4567", null, null);

        bookingService.createBookingRequest(PARENT_ID, req);

        ArgumentCaptor<Booking> bookingCaptor = ArgumentCaptor.forClass(Booking.class);
        verify(bookingRepository).save(bookingCaptor.capture());
        assertThat(bookingCaptor.getValue().getNotes()).isEqualTo("call me at [contact details removed]");

        ArgumentCaptor<BookingRequestedEvent> eventCaptor = ArgumentCaptor.forClass(BookingRequestedEvent.class);
        verify(eventPublisher).publishEvent(eventCaptor.capture());
        assertThat(eventCaptor.getValue().getNotes()).isEqualTo("call me at [contact details removed]");
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
        when(coachAvailabilityWindowRepository.findByCoachIdOrderByDayOfWeekAscStartTimeAscIdAsc(COACH_ID)).thenReturn(List.of(window));
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

    /** fix/booking-price-and-excused-race: no price to lock → refused, nothing written or announced. */
    @Test
    void createBookingRequest_payPerSession_coachWithoutPricing_isRefused() {
        PlayerProfile player = makePlayer(PLAYER_ID, PARENT_ID);
        CoachProfile coach = makeActiveCoach(COACH_ID, COACH_USER_ID);
        CoachAvailabilityWindow window = makeCoveringWindow(COACH_ID);

        when(playerProfileRepository.findById(PLAYER_ID)).thenReturn(Optional.of(player));
        when(coachProfileRepository.findById(COACH_ID)).thenReturn(Optional.of(coach));
        when(paymentGateway.isCoachPaymentReady(COACH_ID)).thenReturn(true);
        when(coachAvailabilityWindowRepository.findByCoachIdOrderByDayOfWeekAscStartTimeAscIdAsc(COACH_ID)).thenReturn(List.of(window));
        when(coachProfileRepository.findByIdForUpdate(COACH_ID)).thenReturn(Optional.of(coach));
        when(bookingRepository.findOverlappingBookings(eq(COACH_ID), any(Instant.class), any(Instant.class), anyList(), any()))
            .thenReturn(List.of());
        when(coachPricingRepository.findByCoachId(COACH_ID)).thenReturn(Optional.empty());

        CreateBookingRequest req = makeValidRequest(COACH_ID, PLAYER_ID, window);
        assertThatThrownBy(() -> bookingService.createBookingRequest(PARENT_ID, req))
            .isInstanceOf(OperationNotAllowedException.class)
            .satisfies(e -> assertThat(((OperationNotAllowedException) e).getErrorCode())
                .isEqualTo(BookingError.COACH_PRICING_UNAVAILABLE));
        verify(bookingRepository, never()).save(any(Booking.class));
        verify(eventPublisher, never()).publishEvent(any(BookingRequestedEvent.class));
    }

    @Test
    void createBookingRequest_payPerSession_locksTheCurrentPriceAsAgreedPrice() {
        PlayerProfile player = makePlayer(PLAYER_ID, PARENT_ID);
        CoachProfile coach = makeActiveCoach(COACH_ID, COACH_USER_ID);
        CoachAvailabilityWindow window = makeCoveringWindow(COACH_ID);

        when(playerProfileRepository.findById(PLAYER_ID)).thenReturn(Optional.of(player));
        when(coachProfileRepository.findById(COACH_ID)).thenReturn(Optional.of(coach));
        when(paymentGateway.isCoachPaymentReady(COACH_ID)).thenReturn(true);
        when(coachAvailabilityWindowRepository.findByCoachIdOrderByDayOfWeekAscStartTimeAscIdAsc(COACH_ID)).thenReturn(List.of(window));
        when(coachProfileRepository.findByIdForUpdate(COACH_ID)).thenReturn(Optional.of(coach));
        when(bookingRepository.findOverlappingBookings(eq(COACH_ID), any(Instant.class), any(Instant.class), anyList(), any()))
            .thenReturn(List.of());
        when(bookingRepository.save(any(Booking.class))).thenAnswer(inv -> inv.getArgument(0));
        when(userRepository.findById(COACH_USER_ID)).thenReturn(Optional.of(makeUser("coach@test.com")));
        when(coachPricingRepository.findByCoachId(COACH_ID)).thenReturn(Optional.of(makeCoachPricing(new BigDecimal("45.00"))));

        bookingService.createBookingRequest(PARENT_ID, makeValidRequest(COACH_ID, PLAYER_ID, window));

        org.mockito.ArgumentCaptor<Booking> saved = org.mockito.ArgumentCaptor.forClass(Booking.class);
        verify(bookingRepository).save(saved.capture());
        assertThat(saved.getValue().getAgreedPrice()).isEqualByComparingTo("45.00");
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
        when(coachAvailabilityWindowRepository.findByCoachIdOrderByDayOfWeekAscStartTimeAscIdAsc(COACH_ID)).thenReturn(List.of(window));
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
        when(coachAvailabilityWindowRepository.findByCoachIdOrderByDayOfWeekAscStartTimeAscIdAsc(COACH_ID)).thenReturn(List.of(window));
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

    // ---- Deferred-79 AC1: CoachAvailabilityBlock enforcement ----

    @Test
    void createBookingRequest_slotOverlapsActiveBlock_throwsSlotBlockedByCoach() {
        PlayerProfile player = makePlayer(PLAYER_ID, PARENT_ID);
        CoachProfile coach = makeActiveCoach(COACH_ID, COACH_USER_ID);
        CoachAvailabilityWindow window = makeCoveringWindow(COACH_ID);

        when(playerProfileRepository.findById(PLAYER_ID)).thenReturn(Optional.of(player));
        when(coachProfileRepository.findById(COACH_ID)).thenReturn(Optional.of(coach));
        when(paymentGateway.isCoachPaymentReady(COACH_ID)).thenReturn(true);
        when(coachAvailabilityWindowRepository.findByCoachIdOrderByDayOfWeekAscStartTimeAscIdAsc(COACH_ID)).thenReturn(List.of(window));
        when(coachProfileRepository.findByIdForUpdate(COACH_ID)).thenReturn(Optional.of(coach));
        when(coachAvailabilityBlockRepository.findByCoachIdAndEndDatetimeAfterAndStartDatetimeBefore(
                eq(COACH_ID), any(Instant.class), any(Instant.class)))
            .thenReturn(List.of(new com.softropic.skillars.platform.booking.repo.CoachAvailabilityBlock()));

        CreateBookingRequest req = makeValidRequest(COACH_ID, PLAYER_ID, window);

        assertThatThrownBy(() -> bookingService.createBookingRequest(PARENT_ID, req))
            .isInstanceOf(OperationNotAllowedException.class)
            .satisfies(ex -> assertThat(((OperationNotAllowedException) ex).getErrorCode())
                .isEqualTo(BookingError.SLOT_BLOCKED_BY_COACH));
        verify(bookingRepository, never()).save(any(Booking.class));
        // Confirms the block check runs before the overlap check (window, then block, then overlap).
        verify(bookingRepository, never()).findOverlappingBookings(any(), any(), any(), any(), any());
    }

    @Test
    void createBookingRequest_slotOutsideAnyBlock_createsRequestedBooking() {
        PlayerProfile player = makePlayer(PLAYER_ID, PARENT_ID);
        CoachProfile coach = makeActiveCoach(COACH_ID, COACH_USER_ID);
        CoachAvailabilityWindow window = makeCoveringWindow(COACH_ID);
        Booking savedBooking = makeBooking(PARENT_ID, PLAYER_ID, COACH_ID, "REQUESTED");

        when(playerProfileRepository.findById(PLAYER_ID)).thenReturn(Optional.of(player));
        when(coachProfileRepository.findById(COACH_ID)).thenReturn(Optional.of(coach));
        when(paymentGateway.isCoachPaymentReady(COACH_ID)).thenReturn(true);
        when(coachAvailabilityWindowRepository.findByCoachIdOrderByDayOfWeekAscStartTimeAscIdAsc(COACH_ID)).thenReturn(List.of(window));
        when(coachProfileRepository.findByIdForUpdate(COACH_ID)).thenReturn(Optional.of(coach));
        when(coachAvailabilityBlockRepository.findByCoachIdAndEndDatetimeAfterAndStartDatetimeBefore(
                eq(COACH_ID), any(Instant.class), any(Instant.class)))
            .thenReturn(List.of());
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
    void createBookingRequest_playerNotOwnedByParent_throwsOperationNotAllowedException() {
        PlayerProfile player = makePlayer(PLAYER_ID, 999L);  // owned by different parent

        when(playerProfileRepository.findById(PLAYER_ID)).thenReturn(Optional.of(player));

        CreateBookingRequest req = makeValidRequest(COACH_ID, PLAYER_ID, makeCoveringWindow(COACH_ID));

        assertThatThrownBy(() -> bookingService.createBookingRequest(PARENT_ID, req))
            .isInstanceOf(OperationNotAllowedException.class);
    }

    // ---- AC2: availability-staleness guard ----

    @Test
    void createBookingRequest_nullAvailabilitySignature_succeedsUnchanged() {
        PlayerProfile player = makePlayer(PLAYER_ID, PARENT_ID);
        CoachProfile coach = makeActiveCoach(COACH_ID, COACH_USER_ID);
        CoachAvailabilityWindow window = makeCoveringWindow(COACH_ID);
        Booking savedBooking = makeBooking(PARENT_ID, PLAYER_ID, COACH_ID, "REQUESTED");

        when(playerProfileRepository.findById(PLAYER_ID)).thenReturn(Optional.of(player));
        when(coachProfileRepository.findById(COACH_ID)).thenReturn(Optional.of(coach));
        when(paymentGateway.isCoachPaymentReady(COACH_ID)).thenReturn(true);
        when(coachAvailabilityWindowRepository.findByCoachIdOrderByDayOfWeekAscStartTimeAscIdAsc(COACH_ID)).thenReturn(List.of(window));
        when(coachProfileRepository.findByIdForUpdate(COACH_ID)).thenReturn(Optional.of(coach));
        when(bookingRepository.findOverlappingBookings(eq(COACH_ID), any(Instant.class), any(Instant.class), anyList(), any()))
            .thenReturn(List.of());
        when(bookingRepository.save(any(Booking.class))).thenReturn(savedBooking);
        when(userRepository.findById(COACH_USER_ID)).thenReturn(Optional.of(makeUser("coach@test.com")));

        // makeValidRequest's underlying record leaves availabilitySignature null — callers not yet
        // sending it must be unaffected.
        CreateBookingRequest req = makeValidRequest(COACH_ID, PLAYER_ID, window);
        BookingResponse response = bookingService.createBookingRequest(PARENT_ID, req);

        assertThat(response).isNotNull();
        verify(bookingRepository).save(any(Booking.class));
    }

    @Test
    void createBookingRequest_matchingAvailabilitySignature_succeeds() {
        PlayerProfile player = makePlayer(PLAYER_ID, PARENT_ID);
        CoachProfile coach = makeActiveCoach(COACH_ID, COACH_USER_ID);
        CoachAvailabilityWindow window = makeCoveringWindow(COACH_ID);
        Booking savedBooking = makeBooking(PARENT_ID, PLAYER_ID, COACH_ID, "REQUESTED");

        when(playerProfileRepository.findById(PLAYER_ID)).thenReturn(Optional.of(player));
        when(coachProfileRepository.findById(COACH_ID)).thenReturn(Optional.of(coach));
        when(paymentGateway.isCoachPaymentReady(COACH_ID)).thenReturn(true);
        when(coachAvailabilityWindowRepository.findByCoachIdOrderByDayOfWeekAscStartTimeAscIdAsc(COACH_ID)).thenReturn(List.of(window));
        when(coachProfileRepository.findByIdForUpdate(COACH_ID)).thenReturn(Optional.of(coach));
        when(bookingRepository.findOverlappingBookings(eq(COACH_ID), any(Instant.class), any(Instant.class), anyList(), any()))
            .thenReturn(List.of());
        when(bookingRepository.save(any(Booking.class))).thenReturn(savedBooking);
        when(userRepository.findById(COACH_USER_ID)).thenReturn(Optional.of(makeUser("coach@test.com")));

        String currentSignature =
            AvailabilityService.computeAvailabilitySignature(List.of(window), Duration.ofHours(1));
        CreateBookingRequest base = makeValidRequest(COACH_ID, PLAYER_ID, window);
        CreateBookingRequest req = new CreateBookingRequest(base.coachId(), base.playerId(),
            base.requestedStartTime(), base.requestedEndTime(), base.notes(), base.sessionPackPurchaseId(),
            currentSignature);

        BookingResponse response = bookingService.createBookingRequest(PARENT_ID, req);

        assertThat(response).isNotNull();
        verify(bookingRepository).save(any(Booking.class));
    }

    @Test
    void createBookingRequest_staleAvailabilitySignature_throwsAvailabilityChangedBeforeWindowCheck() {
        PlayerProfile player = makePlayer(PLAYER_ID, PARENT_ID);
        CoachProfile coach = makeActiveCoach(COACH_ID, COACH_USER_ID);
        // A window that DOES cover the requested slot — proves the staleness check fires before
        // (and independent of) the window-fit check, not as a side effect of an otherwise-invalid slot.
        CoachAvailabilityWindow window = makeCoveringWindow(COACH_ID);

        when(playerProfileRepository.findById(PLAYER_ID)).thenReturn(Optional.of(player));
        when(coachProfileRepository.findById(COACH_ID)).thenReturn(Optional.of(coach));
        when(paymentGateway.isCoachPaymentReady(COACH_ID)).thenReturn(true);
        // Deferred-78 AC1: the lock is now acquired BEFORE the window fetch/signature check, not
        // after — this staleness check runs inside the locked section.
        when(coachProfileRepository.findByIdForUpdate(COACH_ID)).thenReturn(Optional.of(coach));
        when(coachAvailabilityWindowRepository.findByCoachIdOrderByDayOfWeekAscStartTimeAscIdAsc(COACH_ID)).thenReturn(List.of(window));

        CreateBookingRequest base = makeValidRequest(COACH_ID, PLAYER_ID, window);
        CreateBookingRequest req = new CreateBookingRequest(base.coachId(), base.playerId(),
            base.requestedStartTime(), base.requestedEndTime(), base.notes(), base.sessionPackPurchaseId(),
            "a-stale-signature-that-cannot-match");

        assertThatThrownBy(() -> bookingService.createBookingRequest(PARENT_ID, req))
            .isInstanceOf(OperationNotAllowedException.class)
            .satisfies(ex -> assertThat(((OperationNotAllowedException) ex).getErrorCode())
                .isEqualTo(BookingError.AVAILABILITY_CHANGED));
        // Never reaches the overlap-check machinery downstream of the window-fit check.
        verify(bookingRepository, never()).save(any(Booking.class));
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
        when(coachAvailabilityWindowRepository.findByCoachIdOrderByDayOfWeekAscStartTimeAscIdAsc(COACH_ID)).thenReturn(List.of(window));
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

    /**
     * skillars-deferred-69 AC4: getParentPlayerSchedule's ownership check used to assume every caller
     * has a parent (player.getParentId().equals(parentId)) — for a self-registered adult player,
     * player.getParentId() is null, so that comparison could never succeed and the endpoint was
     * unreachable for exactly the callers AC4's widened @PreAuthorize now lets through.
     */
    @Test
    void getParentPlayerSchedule_selfRegisteredPlayer_succeeds() {
        Long selfPlayerUserId = 500L;
        PlayerProfile selfPlayer = makeSelfPlayer(PLAYER_ID, selfPlayerUserId);
        when(playerProfileRepository.findById(PLAYER_ID)).thenReturn(Optional.of(selfPlayer));
        when(bookingRepository.findByParentIdAndPlayerIdAndStatusIn(eq(selfPlayerUserId), eq(PLAYER_ID), anyList()))
            .thenReturn(List.of());

        assertThat(bookingService.getParentPlayerSchedule(selfPlayerUserId, PLAYER_ID)).isNotNull();
    }

    @Test
    void getParentPlayerSchedule_selfRegisteredPlayerUsesSomeoneElsesPlayerId_throwsResourceNotFound() {
        PlayerProfile someoneElsesPlayer = makeSelfPlayer(PLAYER_ID, 999L);
        when(playerProfileRepository.findById(PLAYER_ID)).thenReturn(Optional.of(someoneElsesPlayer));

        assertThatThrownBy(() -> bookingService.getParentPlayerSchedule(500L, PLAYER_ID))
            .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void getParentPlayerSchedule_realParent_stillSucceeds() {
        PlayerProfile player = makePlayer(PLAYER_ID, PARENT_ID);
        when(playerProfileRepository.findById(PLAYER_ID)).thenReturn(Optional.of(player));
        when(bookingRepository.findByParentIdAndPlayerIdAndStatusIn(eq(PARENT_ID), eq(PLAYER_ID), anyList()))
            .thenReturn(List.of());

        assertThat(bookingService.getParentPlayerSchedule(PARENT_ID, PLAYER_ID)).isNotNull();
    }

    // ---- Deferred-81 AC1: batched player/parent/coach name lookups ----

    /**
     * Three distinct players (not two — a 2-row fixture can pass an accidentally-still-N+1
     * implementation that merely fails to break on the second row). Asserts the per-row
     * playerProfileRepository.findById N+1 is gone, replaced by exactly one findAllById call
     * regardless of booking-row count.
     */
    @Test
    void getParentBookings_threeBookingsDistinctPlayers_batchesPlayerNameLookupIntoOneFindAllByIdCall() {
        Long player1 = 201L;
        Long player2 = 202L;
        Long player3 = 203L;
        Booking b1 = makeBooking(PARENT_ID, player1, COACH_ID, "REQUESTED");
        Booking b2 = makeBooking(PARENT_ID, player2, COACH_ID, "REQUESTED");
        Booking b3 = makeBooking(PARENT_ID, player3, COACH_ID, "REQUESTED");

        when(bookingRepository.findAllByParentIdOrderByRequestedStartTimeAsc(PARENT_ID))
            .thenReturn(List.of(b1, b2, b3));
        when(coachProfileRepository.findAllById(any())).thenReturn(List.of(makeActiveCoach(COACH_ID, COACH_USER_ID)));
        when(playerProfileRepository.findAllById(any())).thenReturn(List.of(
            makePlayerNamed(player1, PARENT_ID, "Player One"),
            makePlayerNamed(player2, PARENT_ID, "Player Two"),
            makePlayerNamed(player3, PARENT_ID, "Player Three")));
        when(rescheduleRequestRepository.findPendingByBookingIdIn(any())).thenReturn(List.of());

        List<BookingResponse> responses = bookingService.getParentBookings(PARENT_ID);

        assertThat(responses).extracting(BookingResponse::playerName)
            .containsExactlyInAnyOrder("Player One", "Player Two", "Player Three");
        verify(playerProfileRepository, org.mockito.Mockito.times(1)).findAllById(any());
        verify(playerProfileRepository, never()).findById(any());
    }

    /**
     * feat/coach-workflow-gaps A23: receiptAvailable is true exactly for the rows the batched
     * CAPTURED-payment lookup returns — one query for the whole list, never a per-row findById.
     */
    @Test
    void getParentBookings_flagsReceiptAvailableFromOneBatchedCapturedLookup() {
        Booking paid = makeBooking(PARENT_ID, PLAYER_ID, COACH_ID, "COMPLETED");
        Booking unpaid = makeBooking(PARENT_ID, PLAYER_ID, COACH_ID, "REQUESTED");

        when(bookingRepository.findAllByParentIdOrderByRequestedStartTimeAsc(PARENT_ID))
            .thenReturn(List.of(paid, unpaid));
        when(coachProfileRepository.findAllById(any())).thenReturn(List.of(makeActiveCoach(COACH_ID, COACH_USER_ID)));
        when(playerProfileRepository.findAllById(any())).thenReturn(List.of());
        when(rescheduleRequestRepository.findPendingByBookingIdIn(any())).thenReturn(List.of());
        when(bookingPaymentRepository.findCapturedBookingIds(any())).thenReturn(List.of(paid.getId()));

        List<BookingResponse> responses = bookingService.getParentBookings(PARENT_ID);

        assertThat(responses).extracting(BookingResponse::id, BookingResponse::receiptAvailable)
            .containsExactly(
                org.assertj.core.groups.Tuple.tuple(paid.getId(), true),
                org.assertj.core.groups.Tuple.tuple(unpaid.getId(), false));
        verify(bookingPaymentRepository, org.mockito.Mockito.times(1)).findCapturedBookingIds(any());
        verify(bookingPaymentRepository, never()).findById(any());
    }

    /** A player deleted after booking creation must still fall back to "Unknown Player", not throw. */
    @Test
    void getParentBookings_playerMissingFromBatchResult_fallsBackToUnknownPlayer() {
        Booking b1 = makeBooking(PARENT_ID, PLAYER_ID, COACH_ID, "REQUESTED");

        when(bookingRepository.findAllByParentIdOrderByRequestedStartTimeAsc(PARENT_ID)).thenReturn(List.of(b1));
        when(coachProfileRepository.findAllById(any())).thenReturn(List.of(makeActiveCoach(COACH_ID, COACH_USER_ID)));
        when(playerProfileRepository.findAllById(any())).thenReturn(List.of());
        when(rescheduleRequestRepository.findPendingByBookingIdIn(any())).thenReturn(List.of());

        List<BookingResponse> responses = bookingService.getParentBookings(PARENT_ID);

        assertThat(responses).extracting(BookingResponse::playerName).containsExactly("Unknown Player");
    }

    /**
     * Three distinct players split across two singles and one two-booking batch group — the
     * playerIds set must be built from BOTH branches before either loop runs, not just singles.
     */
    @Test
    void getCoachBookingRequests_singlesAndBatchedBookings_batchesPlayerNameLookupAcrossBoth() {
        CoachProfile coach = makeActiveCoach(COACH_ID, COACH_USER_ID);
        Long singlePlayer = 201L;
        Long batchPlayer1 = 202L;
        Long batchPlayer2 = 203L;
        Long singleParent = PARENT_ID;
        Long batchParent = 9001L;
        UUID batchId = UUID.randomUUID();

        Booking single = makeBooking(singleParent, singlePlayer, COACH_ID, "REQUESTED");
        Booking batched1 = makeBooking(batchParent, batchPlayer1, COACH_ID, "REQUESTED");
        batched1.setBatchId(batchId);
        Booking batched2 = makeBooking(batchParent, batchPlayer2, COACH_ID, "REQUESTED");
        batched2.setBatchId(batchId);

        when(coachProfileRepository.findByUserId(COACH_USER_ID)).thenReturn(Optional.of(coach));
        when(bookingRepository.findByCoachIdAndStatusOrderByRequestedStartTimeAsc(COACH_ID, "REQUESTED"))
            .thenReturn(List.of(single, batched1, batched2));
        when(playerProfileRepository.findAllById(any())).thenReturn(List.of(
            makePlayerNamed(singlePlayer, singleParent, "Single Player"),
            makePlayerNamed(batchPlayer1, batchParent, "Batch Player One"),
            makePlayerNamed(batchPlayer2, batchParent, "Batch Player Two")));
        // Deferred-81 AC1 code review finding: the singles loop's own parent-name lookup was still
        // unbatched (only the once-per-batch-group call was) — two distinct parentIds here (one for
        // the single, one shared by the batch group) proves both branches now resolve off one
        // findAllById call rather than a per-row/per-group findById.
        when(userRepository.findAllById(any())).thenReturn(List.of(
            makeUserWithNameAndId(singleParent, "Single", "Parent"),
            makeUserWithNameAndId(batchParent, "Batch", "Parent")));

        CoachInboxResponse response = bookingService.getCoachBookingRequests(COACH_USER_ID);

        assertThat(response.singleBookings()).extracting(BookingResponse::playerName)
            .containsExactly("Single Player");
        assertThat(response.singleBookings()).extracting(BookingResponse::parentName)
            .containsExactly("Single Parent");
        assertThat(response.batchGroups()).hasSize(1);
        assertThat(response.batchGroups().get(0).bookings()).extracting(BookingResponse::playerName)
            .containsExactlyInAnyOrder("Batch Player One", "Batch Player Two");
        assertThat(response.batchGroups().get(0).parentName()).isEqualTo("Batch Parent");
        verify(playerProfileRepository, org.mockito.Mockito.times(1)).findAllById(any());
        verify(playerProfileRepository, never()).findById(any());
        verify(userRepository, org.mockito.Mockito.times(1)).findAllById(any());
        verify(userRepository, never()).findById(any());
    }

    /**
     * Three distinct coaches for one player's schedule. getParentPlayerSchedule's per-row
     * coachProfileRepository.findById N+1 (the sibling pattern one line away from the
     * resolvePlayerName call this AC also targets) must collapse to one findAllById call.
     */
    @Test
    void getParentPlayerSchedule_threeBookingsDistinctCoaches_batchesCoachNameLookupIntoOneFindAllByIdCall() {
        PlayerProfile player = makePlayer(PLAYER_ID, PARENT_ID);
        UUID coach1 = UUID.randomUUID();
        UUID coach2 = UUID.randomUUID();
        UUID coach3 = UUID.randomUUID();
        Booking b1 = makeBooking(PARENT_ID, PLAYER_ID, coach1, "CONFIRMED");
        Booking b2 = makeBooking(PARENT_ID, PLAYER_ID, coach2, "CONFIRMED");
        Booking b3 = makeBooking(PARENT_ID, PLAYER_ID, coach3, "CONFIRMED");

        when(playerProfileRepository.findById(PLAYER_ID)).thenReturn(Optional.of(player));
        when(bookingRepository.findByParentIdAndPlayerIdAndStatusIn(eq(PARENT_ID), eq(PLAYER_ID), anyList()))
            .thenReturn(List.of(b1, b2, b3));
        when(coachProfileRepository.findAllById(any())).thenReturn(List.of(
            makeCoachNamed(coach1, 301L, "Coach One"),
            makeCoachNamed(coach2, 302L, "Coach Two"),
            makeCoachNamed(coach3, 303L, "Coach Three")));

        ParentScheduleResponse response = bookingService.getParentPlayerSchedule(PARENT_ID, PLAYER_ID);

        assertThat(response.sessions()).extracting(ParentScheduleItem::coachDisplayName)
            .containsExactlyInAnyOrder("Coach One", "Coach Two", "Coach Three");
        verify(coachProfileRepository, org.mockito.Mockito.times(1)).findAllById(any());
        verify(coachProfileRepository, never()).findById(any());
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
        // Deferred-78 AC1: the lock is now acquired BEFORE the window fetch, not after.
        when(coachProfileRepository.findByIdForUpdate(COACH_ID)).thenReturn(Optional.of(coach));
        when(coachAvailabilityWindowRepository.findByCoachIdOrderByDayOfWeekAscStartTimeAscIdAsc(COACH_ID)).thenReturn(Collections.emptyList());

        CreateBookingRequest req = makeValidRequest(COACH_ID, PLAYER_ID, makeCoveringWindow(COACH_ID));

        assertThatThrownBy(() -> bookingService.createBookingRequest(PARENT_ID, req))
            .isInstanceOf(OperationNotAllowedException.class);
    }

    /**
     * skillars-deferred-140 code review (D1): replaces three tests that asserted the per-window
     * invalid-timezone skip and its all-windows-invalid summary WARN. Those behaviours are gone —
     * the zone is now resolved ONCE from the coach's profile, not per window, so a window can no
     * longer carry an individually-invalid zone through this path at all. What remains to pin is the
     * coach-level fallback that replaced them.
     */
    @Test
    void isSlotWithinAvailabilityWindow_coachTimezoneInvalid_fallsBackToUtcAndWarns() {
        // A window whose own stored zone is deliberately divergent AND valid, to prove the method no
        // longer consults it: the slot is expressed in UTC, matching the fallback, not Europe/Berlin.
        ZonedDateTime slotStart = ZonedDateTime.now(ZoneId.of("UTC"))
            .plusDays(1).withHour(10).withMinute(0).withSecond(0).withNano(0);
        CoachAvailabilityWindow window = new CoachAvailabilityWindow();
        window.setCoachId(COACH_ID);
        window.setDayOfWeek((short) slotStart.getDayOfWeek().getValue());
        window.setStartTime(LocalTime.of(8, 0));
        window.setEndTime(LocalTime.of(18, 0));
        window.setCanonicalTimezone("Europe/Berlin");

        Instant start = slotStart.toInstant();
        Instant end = slotStart.plusHours(1).toInstant();

        Logger serviceLogger = (Logger) LoggerFactory.getLogger(BookingService.class);
        ListAppender<ILoggingEvent> logCapture = new ListAppender<>();
        logCapture.start();
        serviceLogger.addAppender(logCapture);
        boolean result;
        try {
            result = bookingService.isSlotWithinAvailabilityWindow(start, end, List.of(window), COACH_ID,
                "not-a-zone");
        } finally {
            serviceLogger.detachAppender(logCapture);
        }

        assertThat(result)
            .as("an unparseable coach zone degrades to UTC rather than propagating out of the slot check")
            .isTrue();
        assertThat(logCapture.list)
            .as("the coach-level fallback must be reported once, naming the coach and the bad value")
            .anySatisfy(event -> {
                assertThat(event.getLevel()).isEqualTo(Level.WARN);
                assertThat(event.getFormattedMessage())
                    .contains(COACH_ID.toString())
                    .contains("not-a-zone")
                    .contains("falling back to UTC");
            });
    }

    @Test
    void isSlotWithinAvailabilityWindow_coachTimezoneNull_fallsBackToUtcWithoutThrowing() {
        // ZoneId.of(null) throws NullPointerException, not DateTimeException, so a catch alone would
        // propagate out. Guarded explicitly ahead of the parse.
        ZonedDateTime slotStart = ZonedDateTime.now(ZoneId.of("UTC"))
            .plusDays(1).withHour(10).withMinute(0).withSecond(0).withNano(0);
        CoachAvailabilityWindow window = new CoachAvailabilityWindow();
        window.setCoachId(COACH_ID);
        window.setDayOfWeek((short) slotStart.getDayOfWeek().getValue());
        window.setStartTime(LocalTime.of(8, 0));
        window.setEndTime(LocalTime.of(18, 0));
        window.setCanonicalTimezone("Europe/Berlin");

        Instant start = slotStart.toInstant();
        Instant end = slotStart.plusHours(1).toInstant();

        assertThat(bookingService.isSlotWithinAvailabilityWindow(start, end, List.of(window), COACH_ID, null))
            .isTrue();
        assertThat(bookingService.isSlotWithinAvailabilityWindow(start, end, List.of(window), COACH_ID, "  "))
            .isTrue();
    }

    /**
     * skillars-deferred-140 code review (D1): the window's own stored zone must no longer influence
     * the slot decision. Pins the divergence directly — a stale/divergent stored value (simulating an
     * un-backfilled pre-deferred-140 row, which V155 now reconciles but which the code must tolerate
     * regardless) is ignored in favour of the coach's profile zone. Before the fix this method and
     * {@code AvailabilityService.getAvailabilityCalendar} disagreed on exactly this row, so the
     * calendar advertised slots this check then rejected.
     */
    @Test
    void isSlotWithinAvailabilityWindow_windowStoredZoneDivergesFromProfile_ignoredInFavourOfProfileZone() {
        // 10:00 Tokyo on the slot's own day. In Tokyo (UTC+9) that is inside an 08:00-18:00 window;
        // read in the window's stale stored Europe/Berlin it would be ~02:00-03:00 and outside it.
        ZonedDateTime slotStart = ZonedDateTime.now(ZoneId.of("Asia/Tokyo"))
            .plusDays(1).withHour(10).withMinute(0).withSecond(0).withNano(0);
        CoachAvailabilityWindow window = new CoachAvailabilityWindow();
        window.setCoachId(COACH_ID);
        window.setDayOfWeek((short) slotStart.getDayOfWeek().getValue());
        window.setStartTime(LocalTime.of(8, 0));
        window.setEndTime(LocalTime.of(18, 0));
        window.setCanonicalTimezone("Europe/Berlin");

        Instant start = slotStart.toInstant();
        Instant end = slotStart.plusHours(1).toInstant();

        assertThat(bookingService.isSlotWithinAvailabilityWindow(start, end, List.of(window), COACH_ID,
            "Asia/Tokyo"))
            .as("the profile zone decides; the window's divergent stored column is not consulted")
            .isTrue();
    }

    @Test
    void isSlotWithinAvailabilityWindow_sessionCrossesMidnightAgainstWideOpenEveryDayWindow_throwsSessionCrossesMidnight() {
        // The flagship bug this AC fixes: even a coach's wide-open 00:00:00-23:59:59 window rejects a
        // session whose end falls on the next calendar day, since windowStart/windowEnd are anchored to
        // the session's own start date and no other window entry is ever tried.
        ZonedDateTime slotStart = ZonedDateTime.now(ZoneId.of("Europe/Berlin"))
            .plusDays(1).withHour(23).withMinute(30).withSecond(0).withNano(0);
        CoachAvailabilityWindow wideOpenWindow = new CoachAvailabilityWindow();
        wideOpenWindow.setCoachId(COACH_ID);
        wideOpenWindow.setDayOfWeek((short) slotStart.getDayOfWeek().getValue());
        wideOpenWindow.setStartTime(LocalTime.of(0, 0, 0));
        wideOpenWindow.setEndTime(LocalTime.of(23, 59, 59));
        wideOpenWindow.setCanonicalTimezone("Europe/Berlin");

        Instant start = slotStart.toInstant();
        Instant end = slotStart.plusHours(1).toInstant();

        assertThatThrownBy(() ->
            bookingService.isSlotWithinAvailabilityWindow(start, end, List.of(wideOpenWindow), COACH_ID, "Europe/Berlin"))
            .isInstanceOf(OperationNotAllowedException.class)
            .hasMessageContaining("cannot cross midnight")
            .extracting(ex -> ((OperationNotAllowedException) ex).getErrorCode())
            .isEqualTo(BookingError.SESSION_CROSSES_MIDNIGHT);
    }

    @Test
    void isSlotWithinAvailabilityWindow_sessionEndsExactlyAtMidnightSameCalendarDay_doesNotThrow() {
        // toLocalDate() on an end time of exactly midnight the same day it started (i.e. duration
        // ends before the day rolls over) must not be misclassified as crossing — sanity check for the
        // toLocalDate() equality guard's boundary.
        ZonedDateTime slotStart = ZonedDateTime.now(ZoneId.of("Europe/Berlin"))
            .plusDays(1).withHour(10).withMinute(0).withSecond(0).withNano(0);
        CoachAvailabilityWindow window = makeCoveringWindow(COACH_ID);
        window.setDayOfWeek((short) slotStart.getDayOfWeek().getValue());

        Instant start = slotStart.toInstant();
        Instant end = slotStart.plusHours(1).toInstant();

        boolean result = bookingService.isSlotWithinAvailabilityWindow(start, end, List.of(window), COACH_ID, "Europe/Berlin");

        assertThat(result).isTrue();
    }

    /**
     * skillars-deferred-164 AC2 (code review): the five call sites' unit tests all mock
     * BookingService, so the helper's own check-and-throw is pinned only here.
     */
    @Test
    void requireSlotWithinAvailabilityWindow_slotOutsideWindow_throwsWithCallerMessageAndDetails() {
        ZonedDateTime slotStart = ZonedDateTime.now(ZoneId.of("Europe/Berlin"))
            .plusDays(1).withHour(20).withMinute(0).withSecond(0).withNano(0);
        CoachAvailabilityWindow window = new CoachAvailabilityWindow();
        window.setCoachId(COACH_ID);
        window.setDayOfWeek((short) slotStart.getDayOfWeek().getValue());
        window.setStartTime(LocalTime.of(8, 0));
        window.setEndTime(LocalTime.of(12, 0));

        Instant start = slotStart.toInstant();
        Instant end = slotStart.plusHours(1).toInstant();
        Map<String, Object> details = Map.of("proposed start time", start, "proposed end time", end);

        assertThatThrownBy(() -> bookingService.requireSlotWithinAvailabilityWindow(start, end, List.of(window),
                COACH_ID, "Europe/Berlin", "Proposed slot is not within coach availability", details))
            .isInstanceOf(OperationNotAllowedException.class)
            .hasMessageContaining("Proposed slot is not within coach availability")
            .satisfies(ex -> {
                OperationNotAllowedException onae = (OperationNotAllowedException) ex;
                assertThat(onae.getErrorCode()).isEqualTo(BookingError.SLOT_OUTSIDE_AVAILABILITY);
                assertThat(onae.getLogContext()).isEqualTo(details);
            });
    }

    @Test
    void requireSlotWithinAvailabilityWindow_slotInsideWindow_doesNotThrow() {
        ZonedDateTime slotStart = ZonedDateTime.now(ZoneId.of("Europe/Berlin"))
            .plusDays(1).withHour(10).withMinute(0).withSecond(0).withNano(0);
        CoachAvailabilityWindow window = makeCoveringWindow(COACH_ID);
        window.setDayOfWeek((short) slotStart.getDayOfWeek().getValue());

        Instant start = slotStart.toInstant();
        Instant end = slotStart.plusHours(1).toInstant();

        assertThatCode(() -> bookingService.requireSlotWithinAvailabilityWindow(start, end, List.of(window),
                COACH_ID, "Europe/Berlin", "Requested slot is not within coach availability",
                Map.of("requested start time", start, "requested end time", end)))
            .doesNotThrowAnyException();
    }

    @Test
    void createBookingRequest_sessionCrossesMidnight_throwsSessionCrossesMidnight() {
        PlayerProfile player = makePlayer(PLAYER_ID, PARENT_ID);
        CoachProfile coach = makeActiveCoach(COACH_ID, COACH_USER_ID);

        when(playerProfileRepository.findById(PLAYER_ID)).thenReturn(Optional.of(player));
        when(coachProfileRepository.findById(COACH_ID)).thenReturn(Optional.of(coach));
        when(paymentGateway.isCoachPaymentReady(COACH_ID)).thenReturn(true);
        // Deferred-78 AC1: the lock is now acquired BEFORE the window fetch, not after.
        when(coachProfileRepository.findByIdForUpdate(COACH_ID)).thenReturn(Optional.of(coach));

        ZonedDateTime slotStart = ZonedDateTime.now(ZoneId.of("Europe/Berlin"))
            .plusDays(1).withHour(23).withMinute(30).withSecond(0).withNano(0);
        CoachAvailabilityWindow wideOpenWindow = new CoachAvailabilityWindow();
        wideOpenWindow.setCoachId(COACH_ID);
        wideOpenWindow.setDayOfWeek((short) slotStart.getDayOfWeek().getValue());
        wideOpenWindow.setStartTime(LocalTime.of(0, 0, 0));
        wideOpenWindow.setEndTime(LocalTime.of(23, 59, 59));
        wideOpenWindow.setCanonicalTimezone("Europe/Berlin");
        when(coachAvailabilityWindowRepository.findByCoachIdOrderByDayOfWeekAscStartTimeAscIdAsc(COACH_ID)).thenReturn(List.of(wideOpenWindow));

        CreateBookingRequest req = new CreateBookingRequest(
            COACH_ID, PLAYER_ID, slotStart.toInstant(), slotStart.plusHours(1).toInstant(),
            "test notes", null, null);

        assertThatThrownBy(() -> bookingService.createBookingRequest(PARENT_ID, req))
            .isInstanceOf(OperationNotAllowedException.class)
            .extracting(ex -> ((OperationNotAllowedException) ex).getErrorCode())
            .isEqualTo(BookingError.SESSION_CROSSES_MIDNIGHT);
        verify(bookingRepository, never()).save(any(Booking.class));
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
            null, null, null
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
            COACH_ID, PLAYER_ID, slotStart.toInstant(), slotStart.plusHours(8).toInstant(), null, null, null);

        assertThatThrownBy(() -> bookingService.createBookingRequest(PARENT_ID, req))
            .isInstanceOf(OperationNotAllowedException.class)
            .hasMessageContaining("session length");
        // Rejected before the window query and before the pessimistic lock, so a malformed request
        // costs neither. Both verifications fail if the check is moved after the window lookup.
        verify(coachAvailabilityWindowRepository, never()).findByCoachIdOrderByDayOfWeekAscStartTimeAscIdAsc(COACH_ID);
        verify(coachProfileRepository, never()).findByIdForUpdate(COACH_ID);
    }

    @Test
    void createBookingRequest_shorterThanTheCoachSessionLength_isRejected() {
        stubUpToDurationCheck();

        ZonedDateTime slotStart = ZonedDateTime.now(ZoneId.of("Europe/Berlin"))
            .plusDays(1).withHour(10).withMinute(0).withSecond(0).withNano(0);
        CreateBookingRequest req = new CreateBookingRequest(
            COACH_ID, PLAYER_ID, slotStart.toInstant(), slotStart.plusMinutes(30).toInstant(), null, null, null);

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
        when(coachAvailabilityWindowRepository.findByCoachIdOrderByDayOfWeekAscStartTimeAscIdAsc(COACH_ID)).thenReturn(List.of(window));
        when(coachProfileRepository.findByIdForUpdate(COACH_ID)).thenReturn(Optional.of(coach));
        when(bookingRepository.findOverlappingBookings(eq(COACH_ID), any(Instant.class), any(Instant.class), anyList(), any()))
            .thenReturn(List.of());
        when(bookingRepository.save(any(Booking.class))).thenReturn(savedBooking);
        when(userRepository.findById(COACH_USER_ID)).thenReturn(Optional.of(makeUser("coach@test.com")));

        ZonedDateTime slotStart = ZonedDateTime.now(ZoneId.of("Europe/Berlin"))
            .plusDays(1).withHour(10).withMinute(0).withSecond(0).withNano(0);

        assertThat(bookingService.createBookingRequest(PARENT_ID, new CreateBookingRequest(
            COACH_ID, PLAYER_ID, slotStart.toInstant(), slotStart.plusMinutes(90).toInstant(), null, null, null)))
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
            COACH_ID, PLAYER_ID, slotStart.toInstant(), slotStart.plusMinutes(60).toInstant(), null, null, null)))
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

    // ---- fix/booking-request-consent: coach proposals, parent confirm / withdraw, request quota ----

    /** H3: the coach can no longer accept (and charge) a booking they proposed themselves. */
    @Test
    void acceptBooking_coachProposal_isRefusedParentConfirmationRequired() {
        Booking booking = makeProposal("REQUESTED");
        CoachProfile coach = makeActiveCoach(COACH_ID, COACH_USER_ID);
        stubAcceptPath(booking, coach);
        when(coachProfileRepository.findByUserId(COACH_USER_ID)).thenReturn(Optional.of(coach));

        assertThatThrownBy(() -> bookingService.acceptBooking(booking.getId(), COACH_USER_ID))
            .isInstanceOf(OperationNotAllowedException.class)
            .satisfies(e -> assertThat(((OperationNotAllowedException) e).getErrorCode())
                .isEqualTo(BookingError.PARENT_CONFIRMATION_REQUIRED));
        assertThat(booking.getStatus()).isEqualTo("REQUESTED");
        verify(bookingRepository, never()).save(any(Booking.class));
        verify(eventPublisher, never()).publishEvent(any(BookingAcceptedEvent.class));
    }

    @Test
    void confirmProposedBooking_parentConfirms_movesToPaymentPendingAndPublishesAccepted() {
        Booking booking = makeProposal("REQUESTED");
        CoachProfile coach = makeActiveCoach(COACH_ID, COACH_USER_ID);
        stubAcceptPath(booking, coach);
        when(bookingRepository.save(any(Booking.class))).thenReturn(booking);
        when(userRepository.findById(PARENT_ID)).thenReturn(Optional.of(makeUser("parent@test.com")));
        when(playerProfileRepository.findById(PLAYER_ID)).thenReturn(Optional.of(makePlayer(PLAYER_ID, PARENT_ID)));

        BookingResponse response = bookingService.confirmProposedBooking(booking.getId(), PARENT_ID);

        assertThat(booking.getStatus()).isEqualTo("PAYMENT_PENDING");
        assertThat(response.proposedByCoach()).isTrue();
        ArgumentCaptor<ApplicationEvent> ev = ArgumentCaptor.forClass(ApplicationEvent.class);
        verify(eventPublisher, org.mockito.Mockito.atLeastOnce()).publishEvent(ev.capture());
        assertThat(ev.getAllValues()).filteredOn(BookingAcceptedEvent.class::isInstance).singleElement()
            .satisfies(e -> {
                BookingAcceptedEvent accepted = (BookingAcceptedEvent) e;
                assertThat(accepted.getSessionPrice()).isEqualByComparingTo("50.00");
                assertThat(accepted.getParentId()).isEqualTo(PARENT_ID);
            });
    }

    /** Ownership is checked on Booking.parentId (a user id) before any row lock is taken. */
    @Test
    void confirmProposedBooking_otherParent_isRejectedBeforeAnyLock() {
        Booking booking = makeProposal("REQUESTED");
        when(bookingRepository.findById(booking.getId())).thenReturn(Optional.of(booking));

        assertThatThrownBy(() -> bookingService.confirmProposedBooking(booking.getId(), 999L))
            .isInstanceOf(OperationNotAllowedException.class)
            .satisfies(e -> assertThat(((OperationNotAllowedException) e).getErrorCode())
                .isEqualTo(com.softropic.skillars.infrastructure.security.SecurityError.MISSING_RIGHTS));
        verify(coachProfileRepository, never()).findByIdForUpdate(any());
        verify(bookingRepository, never()).findByIdForUpdate(any());
    }

    /** A parent's own request is accepted by the coach — the parent cannot accept it for them. */
    @Test
    void confirmProposedBooking_ownRequestNotAProposal_isRefused() {
        Booking booking = makeBooking(PARENT_ID, PLAYER_ID, COACH_ID, "REQUESTED");
        booking.setAgreedPrice(new BigDecimal("50.00"));
        when(bookingRepository.findById(booking.getId())).thenReturn(Optional.of(booking));

        assertThatThrownBy(() -> bookingService.confirmProposedBooking(booking.getId(), PARENT_ID))
            .isInstanceOf(OperationNotAllowedException.class)
            .satisfies(e -> assertThat(((OperationNotAllowedException) e).getErrorCode())
                .isEqualTo(BookingError.NOT_A_COACH_PROPOSAL));
        verify(bookingRepository, never()).save(any(Booking.class));
    }

    @Test
    void confirmProposedBooking_alreadyAnswered_isRefusedRequestNotPending() {
        Booking booking = makeProposal("DECLINED");
        when(bookingRepository.findById(booking.getId())).thenReturn(Optional.of(booking));

        assertThatThrownBy(() -> bookingService.confirmProposedBooking(booking.getId(), PARENT_ID))
            .isInstanceOf(OperationNotAllowedException.class)
            .satisfies(e -> assertThat(((OperationNotAllowedException) e).getErrorCode())
                .isEqualTo(BookingError.REQUEST_NOT_PENDING));
    }

    @Test
    void confirmProposedBooking_startHasPassed_isRefused() {
        Booking booking = makeProposal("REQUESTED");
        Instant start = Instant.now().minusSeconds(60);
        booking.setRequestedStartTime(start);
        booking.setRequestedEndTime(start.plusSeconds(3600));
        when(bookingRepository.findById(booking.getId())).thenReturn(Optional.of(booking));

        assertThatThrownBy(() -> bookingService.confirmProposedBooking(booking.getId(), PARENT_ID))
            .isInstanceOf(OperationNotAllowedException.class)
            .satisfies(e -> assertThat(((OperationNotAllowedException) e).getErrorCode())
                .isEqualTo(BookingError.REQUEST_EXPIRED));
        verify(coachProfileRepository, never()).findByIdForUpdate(any());
    }

    @Test
    void confirmProposedBooking_suspendedCoach_isRefusedCoachUnavailable() {
        Booking booking = makeProposal("REQUESTED");
        CoachProfile suspended = makeActiveCoach(COACH_ID, COACH_USER_ID);
        suspended.setStatus(CoachProfileStatus.SUSPENDED);
        when(bookingRepository.findById(booking.getId())).thenReturn(Optional.of(booking));
        when(coachProfileRepository.findByIdForUpdate(COACH_ID)).thenReturn(Optional.of(suspended));

        assertThatThrownBy(() -> bookingService.confirmProposedBooking(booking.getId(), PARENT_ID))
            .isInstanceOf(OperationNotAllowedException.class)
            .satisfies(e -> assertThat(((OperationNotAllowedException) e).getErrorCode())
                .isEqualTo(BookingError.COACH_UNAVAILABLE));
        assertThat(booking.getStatus()).isEqualTo("REQUESTED");
    }

    /** M1/H3: withdraw (own request) and decline (coach proposal) — no money moves, no forfeit. */
    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void withdrawBookingRequest_requested_cancelsWithNothingRefundedOrForfeited(boolean proposal) {
        Booking booking = proposal ? makeProposal("REQUESTED") : makeBooking(PARENT_ID, PLAYER_ID, COACH_ID, "REQUESTED");
        when(bookingRepository.findById(booking.getId())).thenReturn(Optional.of(booking));
        when(bookingRepository.findByIdForUpdate(booking.getId())).thenReturn(Optional.of(booking));
        when(bookingRepository.save(any(Booking.class))).thenReturn(booking);

        bookingService.withdrawBookingRequest(booking.getId(), PARENT_ID);

        assertThat(booking.getStatus()).isEqualTo("CANCELLED_PARENT");
        ArgumentCaptor<ApplicationEvent> ev = ArgumentCaptor.forClass(ApplicationEvent.class);
        verify(eventPublisher, org.mockito.Mockito.atLeastOnce()).publishEvent(ev.capture());
        assertThat(ev.getAllValues()).filteredOn(BookingCancelledByParentEvent.class::isInstance).singleElement()
            .satisfies(e -> {
                BookingCancelledByParentEvent cancelled = (BookingCancelledByParentEvent) e;
                assertThat(cancelled.isWalletRefund()).isFalse();
                assertThat(cancelled.isPackRestore()).isFalse();
                assertThat(cancelled.isForfeit()).isFalse();
                assertThat(cancelled.getSessionPrice()).isEqualByComparingTo("0");
            });
    }

    /** A withdraw racing a coach accept never turns into the cancellation of a charged booking. */
    @ParameterizedTest
    @ValueSource(strings = {"PAYMENT_PENDING", "CONFIRMED", "UPCOMING", "DECLINED"})
    void withdrawBookingRequest_noLongerRequested_isRefusedAndLeavesTheBookingAlone(String status) {
        Booking booking = makeBooking(PARENT_ID, PLAYER_ID, COACH_ID, status);
        when(bookingRepository.findById(booking.getId())).thenReturn(Optional.of(booking));
        when(bookingRepository.findByIdForUpdate(booking.getId())).thenReturn(Optional.of(booking));

        assertThatThrownBy(() -> bookingService.withdrawBookingRequest(booking.getId(), PARENT_ID))
            .isInstanceOf(OperationNotAllowedException.class)
            .satisfies(e -> assertThat(((OperationNotAllowedException) e).getErrorCode())
                .isEqualTo(BookingError.REQUEST_NOT_PENDING));
        assertThat(booking.getStatus()).isEqualTo(status);
        verify(bookingRepository, never()).save(any(Booking.class));
        verify(eventPublisher, never()).publishEvent(any(BookingCancelledByParentEvent.class));
    }

    @Test
    void withdrawBookingRequest_otherParent_isRejectedBeforeTheRowLock() {
        Booking booking = makeBooking(PARENT_ID, PLAYER_ID, COACH_ID, "REQUESTED");
        when(bookingRepository.findById(booking.getId())).thenReturn(Optional.of(booking));

        assertThatThrownBy(() -> bookingService.withdrawBookingRequest(booking.getId(), 999L))
            .isInstanceOf(OperationNotAllowedException.class);
        verify(bookingRepository, never()).findByIdForUpdate(any());
    }

    /** M2: the parent's pending requests to this coach plus the new ones may not exceed the cap. */
    @Test
    void requireRequestQuota_atTheCap_isRefusedTooManyPendingRequests() {
        when(bookingRepository.countPendingParentRequests(PARENT_ID, COACH_ID)).thenReturn(9L);
        Instant soon = Instant.now().plus(Duration.ofDays(2));

        assertThatCode(() -> bookingService.requireRequestQuota(PARENT_ID, COACH_ID, List.of(soon)))
            .doesNotThrowAnyException();
        assertThatThrownBy(() -> bookingService.requireRequestQuota(PARENT_ID, COACH_ID,
                List.of(soon, soon.plus(Duration.ofDays(1)))))
            .isInstanceOf(OperationNotAllowedException.class)
            .satisfies(e -> assertThat(((OperationNotAllowedException) e).getErrorCode())
                .isEqualTo(BookingError.TOO_MANY_PENDING_REQUESTS));
    }

    @Test
    void requireRequestQuota_beyondTheHorizon_isRefused() {
        Instant beyond = Instant.now().plus(Duration.ofDays(121));

        assertThatThrownBy(() -> bookingService.requireRequestQuota(PARENT_ID, COACH_ID, List.of(beyond)))
            .isInstanceOf(OperationNotAllowedException.class)
            .satisfies(e -> assertThat(((OperationNotAllowedException) e).getErrorCode())
                .isEqualTo(BookingError.BEYOND_BOOKING_HORIZON));
        verify(bookingRepository, never()).countPendingParentRequests(any(), any());
    }

    /** M2 wiring: the single-request path runs the quota under the coach lock and writes nothing when over it. */
    @Test
    void createBookingRequest_overThePendingRequestCap_isRefusedAndWritesNothing() {
        CoachAvailabilityWindow window = makeCoveringWindow(COACH_ID);
        CoachProfile coach = makeActiveCoach(COACH_ID, COACH_USER_ID);
        when(playerProfileRepository.findById(PLAYER_ID)).thenReturn(Optional.of(makePlayer(PLAYER_ID, PARENT_ID)));
        when(coachProfileRepository.findById(COACH_ID)).thenReturn(Optional.of(coach));
        when(paymentGateway.isCoachPaymentReady(COACH_ID)).thenReturn(true);
        when(coachProfileRepository.findByIdForUpdate(COACH_ID)).thenReturn(Optional.of(coach));
        when(bookingRepository.countPendingParentRequests(PARENT_ID, COACH_ID)).thenReturn(10L);

        assertThatThrownBy(() -> bookingService.createBookingRequest(PARENT_ID,
                makeValidRequest(COACH_ID, PLAYER_ID, window)))
            .isInstanceOf(OperationNotAllowedException.class)
            .satisfies(e -> assertThat(((OperationNotAllowedException) e).getErrorCode())
                .isEqualTo(BookingError.TOO_MANY_PENDING_REQUESTS));
        verify(bookingRepository, never()).save(any(Booking.class));
    }

    private Booking makeProposal(String status) {
        Booking b = makeBooking(PARENT_ID, PLAYER_ID, COACH_ID, status);
        b.setProposedByCoach(true);
        b.setAgreedPrice(new BigDecimal("50.00"));
        return b;
    }

    private void stubAcceptPath(Booking booking, CoachProfile coach) {
        when(bookingRepository.findById(booking.getId())).thenReturn(Optional.of(booking));
        when(bookingRepository.findByIdForUpdate(booking.getId())).thenReturn(Optional.of(booking));
        when(coachProfileRepository.findByIdForUpdate(COACH_ID)).thenReturn(Optional.of(coach));
        when(bookingRepository.findOverlappingBookings(eq(COACH_ID), any(Instant.class), any(Instant.class), anyList(), any()))
            .thenReturn(List.of());
    }

    // ---- acceptBooking tests ----

    @Test
    void acceptBooking_requestedBooking_transitionsToPaymentPending() {
        Booking booking = makeBooking(PARENT_ID, PLAYER_ID, COACH_ID, "REQUESTED");
        booking.setAgreedPrice(new BigDecimal("50.00"));
        CoachProfile coach = makeActiveCoach(COACH_ID, COACH_USER_ID);

        when(bookingRepository.findById(booking.getId())).thenReturn(Optional.of(booking));
        when(bookingRepository.findByIdForUpdate(booking.getId())).thenReturn(Optional.of(booking));
        when(coachProfileRepository.findByUserId(COACH_USER_ID)).thenReturn(Optional.of(coach));
        when(coachProfileRepository.findByIdForUpdate(COACH_ID)).thenReturn(Optional.of(coach));
        when(bookingRepository.findOverlappingBookings(eq(COACH_ID), any(Instant.class), any(Instant.class), anyList(), any()))
            .thenReturn(List.of());
        when(bookingRepository.save(any(Booking.class))).thenReturn(booking);
        when(userRepository.findById(PARENT_ID)).thenReturn(Optional.of(makeUser("parent@test.com")));
        when(playerProfileRepository.findById(PLAYER_ID)).thenReturn(Optional.of(makePlayer(PLAYER_ID, PARENT_ID)));

        bookingService.acceptBooking(booking.getId(), COACH_USER_ID);

        assertThat(booking.getStatus()).isEqualTo("PAYMENT_PENDING");
        verify(eventPublisher).publishEvent(any(BookingAcceptedEvent.class));
    }

    /** fix/payment-agreed-price-lock: the accept event carries the price locked at request time. */
    @Test
    void acceptBooking_agreedPriceLocked_eventCarriesItNotTheRaisedCurrentPrice() {
        Booking booking = makeBooking(PARENT_ID, PLAYER_ID, COACH_ID, "REQUESTED");
        booking.setAgreedPrice(new BigDecimal("50.00"));
        CoachProfile coach = makeActiveCoach(COACH_ID, COACH_USER_ID);

        when(bookingRepository.findById(booking.getId())).thenReturn(Optional.of(booking));
        when(bookingRepository.findByIdForUpdate(booking.getId())).thenReturn(Optional.of(booking));
        when(coachProfileRepository.findByUserId(COACH_USER_ID)).thenReturn(Optional.of(coach));
        when(coachProfileRepository.findByIdForUpdate(COACH_ID)).thenReturn(Optional.of(coach));
        when(bookingRepository.findOverlappingBookings(eq(COACH_ID), any(Instant.class), any(Instant.class), anyList(), any()))
            .thenReturn(List.of());
        when(bookingRepository.save(any(Booking.class))).thenReturn(booking);
        when(userRepository.findById(PARENT_ID)).thenReturn(Optional.of(makeUser("parent@test.com")));
        when(playerProfileRepository.findById(PLAYER_ID)).thenReturn(Optional.of(makePlayer(PLAYER_ID, PARENT_ID)));
        lenient().when(coachPricingRepository.findByCoachId(COACH_ID))
            .thenReturn(Optional.of(makeCoachPricing(new BigDecimal("80.00"))));

        bookingService.acceptBooking(booking.getId(), COACH_USER_ID);

        org.mockito.ArgumentCaptor<org.springframework.context.ApplicationEvent> ev =
            org.mockito.ArgumentCaptor.forClass(org.springframework.context.ApplicationEvent.class);
        verify(eventPublisher, org.mockito.Mockito.atLeastOnce()).publishEvent(ev.capture());
        assertThat(ev.getAllValues()).filteredOn(BookingAcceptedEvent.class::isInstance).singleElement()
            .satisfies(e -> assertThat(((BookingAcceptedEvent) e).getSessionPrice()).isEqualByComparingTo("50.00"));
    }

    /**
     * fix/booking-price-and-excused-race: a REQUESTED booking with no agreed_price (pre-V192, or requested
     * while the coach had no pricing) is refused — never charged at the coach's current price.
     */
    @Test
    void acceptBooking_requestedWithoutAgreedPrice_isRefused_evenWhenTheCoachHasPricingNow() {
        Booking booking = makeBooking(PARENT_ID, PLAYER_ID, COACH_ID, "REQUESTED");
        CoachProfile coach = makeActiveCoach(COACH_ID, COACH_USER_ID);

        when(bookingRepository.findById(booking.getId())).thenReturn(Optional.of(booking));
        when(coachProfileRepository.findByUserId(COACH_USER_ID)).thenReturn(Optional.of(coach));
        when(coachProfileRepository.findByIdForUpdate(COACH_ID)).thenReturn(Optional.of(coach));
        when(bookingRepository.findOverlappingBookings(eq(COACH_ID), any(Instant.class), any(Instant.class), anyList(), any()))
            .thenReturn(List.of());

        assertThatThrownBy(() -> bookingService.acceptBooking(booking.getId(), COACH_USER_ID))
            .isInstanceOf(OperationNotAllowedException.class)
            .satisfies(e -> assertThat(((OperationNotAllowedException) e).getErrorCode())
                .isEqualTo(BookingError.PRICE_NOT_LOCKED));
        assertThat(booking.getStatus()).isEqualTo("REQUESTED");
        verify(bookingRepository, never()).save(any(Booking.class));
        verify(eventPublisher, never()).publishEvent(any(BookingAcceptedEvent.class));
    }

    /**
     * fix/booking-completion-integrity (H2): a request whose start time has passed is refused — accepting it
     * charged the parent for a session that was already over.
     */
    @Test
    void acceptBooking_startTimeAlreadyPassed_isRefusedAsExpired() {
        Booking booking = makeBooking(PARENT_ID, PLAYER_ID, COACH_ID, "REQUESTED");
        booking.setAgreedPrice(new BigDecimal("50.00"));
        Instant start = Instant.now().minus(5, java.time.temporal.ChronoUnit.HOURS);
        booking.setRequestedStartTime(start);
        booking.setRequestedEndTime(start.plus(1, java.time.temporal.ChronoUnit.HOURS));
        CoachProfile coach = makeActiveCoach(COACH_ID, COACH_USER_ID);

        when(bookingRepository.findById(booking.getId())).thenReturn(Optional.of(booking));
        when(coachProfileRepository.findByUserId(COACH_USER_ID)).thenReturn(Optional.of(coach));
        when(coachProfileRepository.findByIdForUpdate(COACH_ID)).thenReturn(Optional.of(coach));
        when(bookingRepository.findOverlappingBookings(eq(COACH_ID), any(Instant.class), any(Instant.class), anyList(), any()))
            .thenReturn(List.of());

        assertThatThrownBy(() -> bookingService.acceptBooking(booking.getId(), COACH_USER_ID))
            .isInstanceOf(OperationNotAllowedException.class)
            .satisfies(e -> assertThat(((OperationNotAllowedException) e).getErrorCode())
                .isEqualTo(BookingError.REQUEST_EXPIRED));
        assertThat(booking.getStatus()).isEqualTo("REQUESTED");
        verify(bookingRepository, never()).save(any(Booking.class));
        verify(eventPublisher, never()).publishEvent(any(BookingAcceptedEvent.class));
    }

    @Test
    void acceptBooking_concurrentModification_throwsRetryableException() {
        Booking booking = makeBooking(PARENT_ID, PLAYER_ID, COACH_ID, "REQUESTED");
        booking.setAgreedPrice(new BigDecimal("50.00"));
        CoachProfile coach = makeActiveCoach(COACH_ID, COACH_USER_ID);

        when(bookingRepository.findById(booking.getId())).thenReturn(Optional.of(booking));
        when(bookingRepository.findByIdForUpdate(booking.getId())).thenReturn(Optional.of(booking));
        when(coachProfileRepository.findByUserId(COACH_USER_ID)).thenReturn(Optional.of(coach));
        when(coachProfileRepository.findByIdForUpdate(COACH_ID)).thenReturn(Optional.of(coach));
        when(bookingRepository.findOverlappingBookings(eq(COACH_ID), any(Instant.class), any(Instant.class), anyList(), any()))
            .thenReturn(List.of());
        when(bookingRepository.save(any(Booking.class))).thenThrow(new OptimisticLockingFailureException("test"));

        assertThatThrownBy(() -> bookingService.acceptBooking(booking.getId(), COACH_USER_ID))
            .isInstanceOf(OperationNotAllowedException.class)
            .hasCauseInstanceOf(OptimisticLockingFailureException.class)
            .satisfies(e -> assertThat(((OperationNotAllowedException) e).getErrorCode())
                .isEqualTo(BookingError.CONCURRENT_MODIFICATION));
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
        when(bookingRepository.findByIdForUpdate(booking.getId())).thenReturn(Optional.of(booking));
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
        when(bookingRepository.findByIdForUpdate(booking.getId())).thenReturn(Optional.of(booking));
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

    /**
     * Deferred-64 AC4: a cancel arriving after the booking's own start time is refund-eligible,
     * even though it arrives far less than 24h before that time (it is, in fact, already past) —
     * this was previously silently no-refund because a past instant can never be >24h in the
     * future. Refund-only: no BookingStateMachine change, no ReliabilityStrikeService interaction
     * — BookingService has no dependency on that service at all, so none is possible from this
     * path.
     */
    @Test
    void cancelBookingAsParent_confirmedBookingPastStartTime_becomesRefundEligible() {
        Booking booking = makeBooking(PARENT_ID, PLAYER_ID, COACH_ID, "CONFIRMED");
        booking.setRequestedStartTime(Instant.now().minus(1, java.time.temporal.ChronoUnit.HOURS));
        booking.setRequestedEndTime(Instant.now().minus(30, java.time.temporal.ChronoUnit.MINUTES));

        when(bookingRepository.findById(booking.getId())).thenReturn(Optional.of(booking));
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

    /** Deferred-64 AC4: same widening, UPCOMING status (the other paymentWasCaptured member). */
    @Test
    void cancelBookingAsParent_upcomingBookingPastStartTime_becomesRefundEligible() {
        Booking booking = makeBooking(PARENT_ID, PLAYER_ID, COACH_ID, "UPCOMING");
        booking.setRequestedStartTime(Instant.now().minus(1, java.time.temporal.ChronoUnit.HOURS));
        booking.setRequestedEndTime(Instant.now().minus(30, java.time.temporal.ChronoUnit.MINUTES));

        when(bookingRepository.findById(booking.getId())).thenReturn(Optional.of(booking));
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

    /**
     * Regression guard: the widening must not turn every near-term cancel into a refund. A booking
     * that starts in 2 hours (less than 24h out) but has NOT started yet stays no-refund, exactly
     * as before this AC.
     */
    @Test
    void cancelBookingAsParent_confirmedBookingLessThan24hOutButNotYetStarted_staysNotRefundEligible() {
        Booking booking = makeBooking(PARENT_ID, PLAYER_ID, COACH_ID, "CONFIRMED");
        booking.setRequestedStartTime(Instant.now().plus(2, java.time.temporal.ChronoUnit.HOURS));
        booking.setRequestedEndTime(Instant.now().plus(3, java.time.temporal.ChronoUnit.HOURS));

        when(bookingRepository.findById(booking.getId())).thenReturn(Optional.of(booking));
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

    /**
     * Deferred-64 AC4: paymentWasCaptured still gates the whole expression regardless of timing —
     * a booking whose payment never captured stays refund-ineligible even once its start time has
     * passed.
     */
    @Test
    void cancelBookingAsParent_paymentPendingBookingPastStartTime_staysNotRefundEligible() {
        Booking booking = makeBooking(PARENT_ID, PLAYER_ID, COACH_ID, "PAYMENT_PENDING");
        booking.setRequestedStartTime(Instant.now().minus(1, java.time.temporal.ChronoUnit.HOURS));
        booking.setRequestedEndTime(Instant.now().minus(30, java.time.temporal.ChronoUnit.MINUTES));

        when(bookingRepository.findById(booking.getId())).thenReturn(Optional.of(booking));
        when(bookingRepository.findByIdForUpdate(booking.getId())).thenReturn(Optional.of(booking));
        when(bookingPaymentRepository.findById(booking.getId())).thenReturn(Optional.empty());
        when(userRepository.findById(PARENT_ID)).thenReturn(Optional.of(makeUser("parent@test.com")));
        when(coachProfileRepository.findById(COACH_ID)).thenReturn(Optional.of(makeActiveCoach(COACH_ID, COACH_USER_ID)));
        when(userRepository.findById(COACH_USER_ID)).thenReturn(Optional.of(makeUser("coach@test.com")));
        when(bookingRepository.save(any(Booking.class))).thenReturn(booking);

        bookingService.cancelBookingAsParent(booking.getId(), PARENT_ID);

        assertThat(booking.getStatus()).isEqualTo("CANCELLED_PARENT");
        assertThat(capturedParentCancellation().isRefundEligible()).isFalse();
    }

    // ---- fix/booking-cancel-policy-loopholes: grace window + reschedule anchor ----

    @Test
    void cancelBookingAsParent_29MinutesAfterStart_isAnOrdinaryForfeit() {
        Instant now = Instant.now();
        assertThat(cancelConfirmedAsParent(now.minus(29, java.time.temporal.ChronoUnit.MINUTES), null))
            .as("inside the 30-minute grace the coach may still start; cancelling now is a <24h forfeit")
            .isFalse();
    }

    @Test
    void cancelBookingAsParent_31MinutesAfterStartStillUnstarted_isRefundEligible() {
        Instant now = Instant.now();
        assertThat(cancelConfirmedAsParent(now.minus(31, java.time.temporal.ChronoUnit.MINUTES), null)).isTrue();
    }

    @Test
    void cancelBookingAsParent_rescheduledOutOfThe24hWindow_staysForfeitedFromTheOriginalStart() {
        // Confirmed for +2h, rescheduled (coach accepted) to +5 days, cancelled now: >24h before the
        // new time, but only 2h before the original — no refund.
        Instant now = Instant.now();
        assertThat(cancelConfirmedAsParent(now.plus(5, java.time.temporal.ChronoUnit.DAYS),
            now.plus(2, java.time.temporal.ChronoUnit.HOURS))).isFalse();
    }

    @Test
    void cancelBookingAsParent_rescheduledWithAmpleNoticeOnBothTimes_staysRefundEligible() {
        Instant now = Instant.now();
        assertThat(cancelConfirmedAsParent(now.plus(5, java.time.temporal.ChronoUnit.DAYS),
            now.plus(3, java.time.temporal.ChronoUnit.DAYS))).isTrue();
    }

    @Test
    void parentCancelRefundRule_boundaries() {
        Instant now = Instant.parse("2026-10-28T12:00:00Z");
        Duration grace = Duration.ofMinutes(30);
        java.time.temporal.ChronoUnit min = java.time.temporal.ChronoUnit.MINUTES;
        java.time.temporal.ChronoUnit hrs = java.time.temporal.ChronoUnit.HOURS;
        // Grace window: exactly start+grace is still a forfeit; strictly after it is a refund.
        assertThat(BookingService.isParentCancelRefundEligible(now.minus(30, min), null, now, grace)).isFalse();
        assertThat(BookingService.isParentCancelRefundEligible(now.minus(31, min), null, now, grace)).isTrue();
        assertThat(BookingService.isParentCancelRefundEligible(now.minusSeconds(1), null, now, grace)).isFalse();
        // 24h notice: strictly more than 24h.
        assertThat(BookingService.isParentCancelRefundEligible(now.plus(24, hrs), null, now, grace)).isFalse();
        assertThat(BookingService.isParentCancelRefundEligible(now.plus(25, hrs), null, now, grace)).isTrue();
        // Anchor = earlier of original and current: an earlier-rescheduled booking uses the new time.
        assertThat(BookingService.isParentCancelRefundEligible(now.plus(2, hrs), now.plus(48, hrs), now, grace))
            .isFalse();
        assertThat(BookingService.isParentCancelRefundEligible(now.plus(48, hrs), now.plus(2, hrs), now, grace))
            .isFalse();
        // A rescheduled booking whose CURRENT time is long past and never started is still refundable.
        assertThat(BookingService.isParentCancelRefundEligible(now.minus(2, hrs), now.minus(50, hrs), now, grace))
            .isTrue();
    }

    // ---- feat/payment-forfeit-payouts-and-clawback: forfeit flag + coach no-show grace ----

    @Test
    void cancelBookingAsParent_capturedLessThan24hOut_isAForfeitThatPaysTheCoach() {
        cancelConfirmedAsParent(Instant.now().plus(2, java.time.temporal.ChronoUnit.HOURS), null);
        BookingCancelledByParentEvent event = capturedParentCancellation();
        assertThat(event.isPaymentCaptured()).isTrue();
        assertThat(event.isForfeit()).isTrue();
    }

    @Test
    void cancelBookingAsParent_refundEligible_isNotAForfeit() {
        cancelConfirmedAsParent(Instant.now().plus(5, java.time.temporal.ChronoUnit.DAYS), null);
        assertThat(capturedParentCancellation().isForfeit()).isFalse();
    }

    // ---- fix/payment-pack-clawback-and-expired-cancel: >24h parent cancel on an expired pack ----

    @Test
    void cancelBookingAsParent_refundEligibleOnAnExpiredPack_isAWalletRefund_notARestore() {
        BookingCancelledByParentEvent event = cancelPackBookingAsParent(Instant.now().minus(1, java.time.temporal.ChronoUnit.DAYS));
        assertThat(event.isRefundEligible()).isTrue();
        assertThat(event.isPackExpiredAtCancellation()).isTrue();
        assertThat(event.isWalletRefund()).isTrue();
        assertThat(event.isPackRestore()).isFalse();
    }

    @Test
    void cancelBookingAsParent_refundEligibleOnAValidPack_restoresTheSession() {
        BookingCancelledByParentEvent event = cancelPackBookingAsParent(Instant.now().plus(10, java.time.temporal.ChronoUnit.DAYS));
        assertThat(event.isPackExpiredAtCancellation()).isFalse();
        assertThat(event.isPackRestore()).isTrue();
        assertThat(event.isWalletRefund()).isFalse();
    }

    private BookingCancelledByParentEvent cancelPackBookingAsParent(Instant packExpiresAt) {
        UUID packId = UUID.randomUUID();
        com.softropic.skillars.platform.payment.repo.SessionPackPurchase pack =
            new com.softropic.skillars.platform.payment.repo.SessionPackPurchase();
        pack.setPurchaseId(packId);
        pack.setAmountCharged(new BigDecimal("100.00"));
        pack.setSessionCount(2);
        pack.setPricePerSession(new BigDecimal("50.00"));
        pack.setExpiresAt(packExpiresAt);
        Booking booking = makeBooking(PARENT_ID, PLAYER_ID, COACH_ID, "CONFIRMED");
        Instant start = Instant.now().plus(5, java.time.temporal.ChronoUnit.DAYS);
        booking.setRequestedStartTime(start);
        booking.setRequestedEndTime(start.plus(1, java.time.temporal.ChronoUnit.HOURS));
        booking.setSessionPackPurchaseId(packId);
        when(bookingRepository.findById(booking.getId())).thenReturn(Optional.of(booking));
        when(bookingRepository.findByIdForUpdate(booking.getId())).thenReturn(Optional.of(booking));
        when(sessionPackPurchaseRepository.findById(packId)).thenReturn(Optional.of(pack));
        when(userRepository.findById(PARENT_ID)).thenReturn(Optional.of(makeUser("parent@test.com")));
        when(coachProfileRepository.findById(COACH_ID)).thenReturn(Optional.of(makeActiveCoach(COACH_ID, COACH_USER_ID)));
        when(userRepository.findById(COACH_USER_ID)).thenReturn(Optional.of(makeUser("coach@test.com")));
        when(bookingRepository.save(any(Booking.class))).thenReturn(booking);

        bookingService.cancelBookingAsParent(booking.getId(), PARENT_ID);

        return capturedParentCancellation();
    }

    /**
     * fix/booking-completion-integrity: a started session (IN_PROGRESS / PAUSED) is not parent-cancellable — a
     * PAUSED cancel used to be a forfeit that paid the coach for a session they had stopped. Dispute instead.
     */
    @ParameterizedTest
    @ValueSource(strings = {"IN_PROGRESS", "PAUSED"})
    void cancelBookingAsParent_startedSession_isRefused_noForfeit(String status) {
        Booking booking = makeBooking(PARENT_ID, PLAYER_ID, COACH_ID, status);
        booking.setRequestedStartTime(Instant.now().minus(20, java.time.temporal.ChronoUnit.MINUTES));
        booking.setRequestedEndTime(Instant.now().plus(40, java.time.temporal.ChronoUnit.MINUTES));
        when(bookingRepository.findById(booking.getId())).thenReturn(Optional.of(booking));
        when(bookingRepository.findByIdForUpdate(booking.getId())).thenReturn(Optional.of(booking));

        assertThatThrownBy(() -> bookingService.cancelBookingAsParent(booking.getId(), PARENT_ID))
            .isInstanceOf(OperationNotAllowedException.class)
            .satisfies(e -> assertThat(((OperationNotAllowedException) e).getErrorCode())
                .isEqualTo(BookingError.SESSION_IN_PROGRESS));
        assertThat(booking.getStatus()).isEqualTo(status);
        verify(bookingRepository, never()).save(any(Booking.class));
        verify(eventPublisher, never()).publishEvent(any(BookingCancelledByParentEvent.class));
    }

    @Test
    void cancelBookingAsParent_paymentPending_isNotAForfeit() {
        Booking booking = makeBooking(PARENT_ID, PLAYER_ID, COACH_ID, "PAYMENT_PENDING");
        booking.setRequestedStartTime(Instant.now().plus(2, java.time.temporal.ChronoUnit.HOURS));
        booking.setRequestedEndTime(Instant.now().plus(3, java.time.temporal.ChronoUnit.HOURS));
        when(bookingRepository.findById(booking.getId())).thenReturn(Optional.of(booking));
        when(bookingRepository.findByIdForUpdate(booking.getId())).thenReturn(Optional.of(booking));
        when(bookingPaymentRepository.findById(booking.getId())).thenReturn(Optional.empty());
        when(userRepository.findById(PARENT_ID)).thenReturn(Optional.of(makeUser("parent@test.com")));
        when(coachProfileRepository.findById(COACH_ID)).thenReturn(Optional.of(makeActiveCoach(COACH_ID, COACH_USER_ID)));
        when(userRepository.findById(COACH_USER_ID)).thenReturn(Optional.of(makeUser("coach@test.com")));
        when(bookingRepository.save(any(Booking.class))).thenReturn(booking);

        bookingService.cancelBookingAsParent(booking.getId(), PARENT_ID);

        assertThat(capturedParentCancellation().isForfeit()).as("nothing captured, nothing paid").isFalse();
    }

    @Test
    void recordNoShowCoach_insideTheLateCancelGrace_throwsNoShowTooEarly() {
        Booking booking = makeBooking(PARENT_ID, PLAYER_ID, COACH_ID, "UPCOMING");
        // 29 minutes after start, grace is 30 (setUp): the coach can still press Start.
        booking.setRequestedStartTime(Instant.now().minus(29, java.time.temporal.ChronoUnit.MINUTES));
        when(bookingRepository.findById(booking.getId())).thenReturn(Optional.of(booking));

        assertThatThrownBy(() -> bookingService.recordNoShowCoach(booking.getId(), PARENT_ID))
            .isInstanceOf(OperationNotAllowedException.class)
            .satisfies(e -> assertThat(((OperationNotAllowedException) e).getErrorCode())
                .isEqualTo(BookingError.NO_SHOW_TOO_EARLY));
        verify(eventPublisher, never()).publishEvent(any(com.softropic.skillars.platform.booking.contract.CoachNoShowEvent.class));
    }

    @Test
    void coachNoShowReportableAt_isStartPlusTheLateCancelGrace() {
        Instant start = Instant.parse("2026-10-28T12:00:00Z");
        assertThat(bookingService.coachNoShowReportableAt(start)).isEqualTo(Instant.parse("2026-10-28T12:30:00Z"));
    }

    private boolean cancelConfirmedAsParent(Instant start, Instant originalStart) {
        Booking booking = makeBooking(PARENT_ID, PLAYER_ID, COACH_ID, "CONFIRMED");
        booking.setRequestedStartTime(start);
        booking.setRequestedEndTime(start.plus(1, java.time.temporal.ChronoUnit.HOURS));
        booking.setOriginalStartTime(originalStart);

        when(bookingRepository.findById(booking.getId())).thenReturn(Optional.of(booking));
        when(bookingRepository.findByIdForUpdate(booking.getId())).thenReturn(Optional.of(booking));
        when(coachPricingRepository.findByCoachId(COACH_ID)).thenReturn(Optional.of(makeCoachPricing(new BigDecimal("50.00"))));
        when(userRepository.findById(PARENT_ID)).thenReturn(Optional.of(makeUser("parent@test.com")));
        when(coachProfileRepository.findById(COACH_ID)).thenReturn(Optional.of(makeActiveCoach(COACH_ID, COACH_USER_ID)));
        when(userRepository.findById(COACH_USER_ID)).thenReturn(Optional.of(makeUser("coach@test.com")));
        when(bookingRepository.save(any(Booking.class))).thenReturn(booking);

        bookingService.cancelBookingAsParent(booking.getId(), PARENT_ID);

        assertThat(booking.getStatus()).isEqualTo("CANCELLED_PARENT");
        return capturedParentCancellation().isRefundEligible();
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
        when(bookingRepository.findByIdForUpdate(booking.getId())).thenReturn(Optional.of(booking));
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
        when(bookingRepository.findByIdForUpdate(booking.getId())).thenReturn(Optional.of(booking));
        when(coachProfileRepository.findByUserId(COACH_USER_ID)).thenReturn(Optional.of(coach));

        assertThatThrownBy(() -> bookingService.declineBooking(booking.getId(), COACH_USER_ID))
            .isInstanceOf(BookingStateTransitionException.class);
    }

    @Test
    void declineBooking_upcomingBooking_throwsBookingStateTransitionException() {
        Booking booking = makeBooking(PARENT_ID, PLAYER_ID, COACH_ID, "UPCOMING");
        CoachProfile coach = makeActiveCoach(COACH_ID, COACH_USER_ID);

        when(bookingRepository.findById(booking.getId())).thenReturn(Optional.of(booking));
        when(bookingRepository.findByIdForUpdate(booking.getId())).thenReturn(Optional.of(booking));
        when(coachProfileRepository.findByUserId(COACH_USER_ID)).thenReturn(Optional.of(coach));

        assertThatThrownBy(() -> bookingService.declineBooking(booking.getId(), COACH_USER_ID))
            .isInstanceOf(BookingStateTransitionException.class);
    }

    @Test
    void declineBooking_concurrentModification_throwsRetryableException() {
        Booking booking = makeBooking(PARENT_ID, PLAYER_ID, COACH_ID, "REQUESTED");
        CoachProfile coach = makeActiveCoach(COACH_ID, COACH_USER_ID);

        when(bookingRepository.findById(booking.getId())).thenReturn(Optional.of(booking));
        when(bookingRepository.findByIdForUpdate(booking.getId())).thenReturn(Optional.of(booking));
        when(coachProfileRepository.findByUserId(COACH_USER_ID)).thenReturn(Optional.of(coach));
        when(bookingRepository.save(any(Booking.class))).thenThrow(new OptimisticLockingFailureException("test"));

        assertThatThrownBy(() -> bookingService.declineBooking(booking.getId(), COACH_USER_ID))
            .isInstanceOf(OperationNotAllowedException.class)
            .hasCauseInstanceOf(OptimisticLockingFailureException.class)
            .satisfies(e -> assertThat(((OperationNotAllowedException) e).getErrorCode())
                .isEqualTo(BookingError.CONCURRENT_MODIFICATION));
    }

    // ---- cancelBookingAsCoach tests ----

    @Test
    void cancelBookingAsCoach_concurrentModification_throwsRetryableException() {
        Booking booking = makeBooking(PARENT_ID, PLAYER_ID, COACH_ID, "UPCOMING");
        CoachProfile coach = makeActiveCoach(COACH_ID, COACH_USER_ID);

        when(bookingRepository.findById(booking.getId())).thenReturn(Optional.of(booking));
        when(bookingRepository.findByIdForUpdate(booking.getId())).thenReturn(Optional.of(booking));
        when(coachProfileRepository.findByUserId(COACH_USER_ID)).thenReturn(Optional.of(coach));
        when(coachPricingRepository.findByCoachId(COACH_ID)).thenReturn(Optional.of(makeCoachPricing(new BigDecimal("50.00"))));
        when(userRepository.findById(PARENT_ID)).thenReturn(Optional.of(makeUser("parent@test.com")));
        when(bookingRepository.save(any(Booking.class))).thenThrow(new OptimisticLockingFailureException("test"));

        assertThatThrownBy(() -> bookingService.cancelBookingAsCoach(booking.getId(), COACH_USER_ID, "MUTUAL_AGREEMENT"))
            .isInstanceOf(OperationNotAllowedException.class)
            .hasCauseInstanceOf(OptimisticLockingFailureException.class)
            .satisfies(e -> assertThat(((OperationNotAllowedException) e).getErrorCode())
                .isEqualTo(BookingError.CONCURRENT_MODIFICATION));
    }

    /**
     * C6: ACCEPTED has taken no money (a card booking is charged, a pack session deducted, only at
     * settle). The coach cancelling it must say so on the event — the refund listeners used to refund
     * (or restore a pack session) for it — and must not look up a price at all.
     */
    @Test
    void cancelBookingAsCoach_acceptedBooking_isNotCaptured_andOwesNothing() {
        Booking booking = makeBooking(PARENT_ID, PLAYER_ID, COACH_ID, "ACCEPTED");
        CoachProfile coach = makeActiveCoach(COACH_ID, COACH_USER_ID);
        when(bookingRepository.findById(booking.getId())).thenReturn(Optional.of(booking));
        when(bookingRepository.findByIdForUpdate(booking.getId())).thenReturn(Optional.of(booking));
        when(coachProfileRepository.findByUserId(COACH_USER_ID)).thenReturn(Optional.of(coach));
        when(userRepository.findById(PARENT_ID)).thenReturn(Optional.of(makeUser("parent@test.com")));
        when(bookingRepository.save(any(Booking.class))).thenReturn(booking);

        bookingService.cancelBookingAsCoach(booking.getId(), COACH_USER_ID, "SCHEDULING_PREFERENCE");

        BookingCancelledByCoachEvent event = capturedEvent(BookingCancelledByCoachEvent.class);
        assertThat(event.isPaymentCaptured()).isFalse();
        assertThat(event.isWalletRefund()).isFalse();
        assertThat(event.getSessionPrice()).isEqualByComparingTo("0");
        verify(coachPricingRepository, never()).findByCoachId(any());
    }

    @Test
    void cancelBookingAsCoach_confirmedBooking_refundsWhatWasPaid_notTheCurrentPrice() {
        Booking booking = makeBooking(PARENT_ID, PLAYER_ID, COACH_ID, "CONFIRMED");
        CoachProfile coach = makeActiveCoach(COACH_ID, COACH_USER_ID);
        when(bookingRepository.findById(booking.getId())).thenReturn(Optional.of(booking));
        when(bookingRepository.findByIdForUpdate(booking.getId())).thenReturn(Optional.of(booking));
        when(coachProfileRepository.findByUserId(COACH_USER_ID)).thenReturn(Optional.of(coach));
        BookingPayment paid = new BookingPayment();
        paid.setBookingId(booking.getId());
        paid.setStatus("CAPTURED");
        paid.setCreditDebited(new BigDecimal("10.00"));
        paid.setStripeCharged(new BigDecimal("30.00"));
        when(bookingPaymentRepository.findById(booking.getId())).thenReturn(Optional.of(paid));
        when(userRepository.findById(PARENT_ID)).thenReturn(Optional.of(makeUser("parent@test.com")));
        when(bookingRepository.save(any(Booking.class))).thenReturn(booking);

        bookingService.cancelBookingAsCoach(booking.getId(), COACH_USER_ID, "HEALTH_MEDICAL");

        BookingCancelledByCoachEvent event = capturedEvent(BookingCancelledByCoachEvent.class);
        assertThat(event.isPaymentCaptured()).isTrue();
        assertThat(event.isWalletRefund()).isTrue();
        assertThat(event.getSessionPrice()).isEqualByComparingTo("40.00");
    }

    private <T extends ApplicationEvent> T capturedEvent(Class<T> type) {
        ArgumentCaptor<ApplicationEvent> captor = ArgumentCaptor.forClass(ApplicationEvent.class);
        verify(eventPublisher, org.mockito.Mockito.atLeastOnce()).publishEvent(captor.capture());
        return captor.getAllValues().stream().filter(type::isInstance).map(type::cast).findFirst().orElseThrow();
    }

    // ---- recordNoShowPlayer tests ----

    @Test
    void recordNoShowPlayer_concurrentModification_throwsRetryableException() {
        Booking booking = makeBooking(PARENT_ID, PLAYER_ID, COACH_ID, "UPCOMING");
        // skillars-deferred-151 AC6: makeBooking's own shared default (Instant.now().plusSeconds(7200))
        // is now rejected by the new SESSION_NOT_YET_STARTED-equivalent too-early guard this AC adds
        // to recordNoShowPlayer — override to a past start/end here only, not in the shared helper
        // (which many other tests rely on defaulting to the future), so this test still reaches the
        // optimistic-lock path its assertion actually checks.
        booking.setRequestedStartTime(Instant.now().minusSeconds(7200));
        booking.setRequestedEndTime(Instant.now().minusSeconds(3600));
        CoachProfile coach = makeActiveCoach(COACH_ID, COACH_USER_ID);

        when(bookingRepository.findById(booking.getId())).thenReturn(Optional.of(booking));
        when(bookingRepository.findByIdForUpdate(booking.getId())).thenReturn(Optional.of(booking));
        when(coachProfileRepository.findByUserId(COACH_USER_ID)).thenReturn(Optional.of(coach));
        when(coachProfileRepository.findById(COACH_ID)).thenReturn(Optional.of(coach));
        when(userRepository.findById(COACH_USER_ID)).thenReturn(Optional.of(makeUser("coach@test.com")));
        when(userRepository.findById(PARENT_ID)).thenReturn(Optional.of(makeUser("parent@test.com")));
        when(bookingRepository.save(any(Booking.class))).thenThrow(new OptimisticLockingFailureException("test"));

        assertThatThrownBy(() -> bookingService.recordNoShowPlayer(booking.getId(), COACH_USER_ID))
            .isInstanceOf(OperationNotAllowedException.class)
            .hasCauseInstanceOf(OptimisticLockingFailureException.class)
            .satisfies(e -> assertThat(((OperationNotAllowedException) e).getErrorCode())
                .isEqualTo(BookingError.CONCURRENT_MODIFICATION));
    }

    // skillars-deferred-151 AC6: USER DECISION — reverses spec-coach-booking-cancel-no-show.md's
    // prior "Never add a client-side too-early guard on no-show reporting" note for this specific
    // endpoint. Mirrors recordNoShowCoach_beforeScheduledStartTime_throwsNoShowTooEarly below
    // character-for-character.
    @Test
    void recordNoShowPlayer_beforeScheduledStartTime_throwsNoShowTooEarly() {
        Booking booking = makeBooking(PARENT_ID, PLAYER_ID, COACH_ID, "UPCOMING");
        booking.setRequestedStartTime(Instant.now().plusSeconds(3600));
        CoachProfile coach = makeActiveCoach(COACH_ID, COACH_USER_ID);

        when(bookingRepository.findById(booking.getId())).thenReturn(Optional.of(booking));
        when(coachProfileRepository.findByUserId(COACH_USER_ID)).thenReturn(Optional.of(coach));

        assertThatThrownBy(() -> bookingService.recordNoShowPlayer(booking.getId(), COACH_USER_ID))
            .isInstanceOf(OperationNotAllowedException.class)
            .satisfies(e -> assertThat(((OperationNotAllowedException) e).getErrorCode())
                .isEqualTo(BookingError.NO_SHOW_TOO_EARLY));

        verify(bookingRepository, never()).save(any(Booking.class));
    }

    /**
     * fix/booking-completion-integrity (owner decision 2026-10-29): not before start +
     * booking.playerNoShow.graceMinutes (15 in setUp) — a family a few minutes late is not a no-show.
     */
    @Test
    void recordNoShowPlayer_insideTheGraceAfterStart_throwsNoShowTooEarly() {
        Booking booking = makeBooking(PARENT_ID, PLAYER_ID, COACH_ID, "UPCOMING");
        Instant start = Instant.now().minus(10, java.time.temporal.ChronoUnit.MINUTES);
        booking.setRequestedStartTime(start);
        booking.setRequestedEndTime(start.plus(1, java.time.temporal.ChronoUnit.HOURS));
        CoachProfile coach = makeActiveCoach(COACH_ID, COACH_USER_ID);

        when(bookingRepository.findById(booking.getId())).thenReturn(Optional.of(booking));
        when(coachProfileRepository.findByUserId(COACH_USER_ID)).thenReturn(Optional.of(coach));

        assertThatThrownBy(() -> bookingService.recordNoShowPlayer(booking.getId(), COACH_USER_ID))
            .isInstanceOf(OperationNotAllowedException.class)
            .satisfies(e -> assertThat(((OperationNotAllowedException) e).getErrorCode())
                .isEqualTo(BookingError.NO_SHOW_TOO_EARLY));
        verify(bookingRepository, never()).save(any(Booking.class));
    }

    @Test
    void recordNoShowPlayer_afterTheGrace_isRecorded() {
        Booking booking = makeBooking(PARENT_ID, PLAYER_ID, COACH_ID, "UPCOMING");
        Instant start = Instant.now().minus(16, java.time.temporal.ChronoUnit.MINUTES);
        booking.setRequestedStartTime(start);
        booking.setRequestedEndTime(start.plus(1, java.time.temporal.ChronoUnit.HOURS));
        CoachProfile coach = makeActiveCoach(COACH_ID, COACH_USER_ID);

        when(bookingRepository.findById(booking.getId())).thenReturn(Optional.of(booking));
        when(bookingRepository.findByIdForUpdate(booking.getId())).thenReturn(Optional.of(booking));
        when(coachProfileRepository.findByUserId(COACH_USER_ID)).thenReturn(Optional.of(coach));
        when(coachProfileRepository.findById(COACH_ID)).thenReturn(Optional.of(coach));
        // makeUser's reflection targets User's own fields, but email lives on the Customer superclass — so its
        // users have a null email. Set it through the setter: this test asserts the resolved addresses.
        User coachUser = new User();
        coachUser.setEmail("coach@test.com");
        User parentUser = new User();
        parentUser.setEmail("parent@test.com");
        when(userRepository.findById(COACH_USER_ID)).thenReturn(Optional.of(coachUser));
        when(userRepository.findById(PARENT_ID)).thenReturn(Optional.of(parentUser));
        when(bookingRepository.save(any(Booking.class))).thenReturn(booking);

        bookingService.recordNoShowPlayer(booking.getId(), COACH_USER_ID);

        assertThat(booking.getStatus()).isEqualTo("NO_SHOW_PLAYER");
        // fix/forfeit-family-emails: the family notice goes to the payer — Booking.parentId (a User id) —
        // never to anything resolved from playerId (a PlayerProfile key).
        PlayerNoShowEvent event = capturedEvent(PlayerNoShowEvent.class);
        assertThat(event.getCoachEmail()).isEqualTo("coach@test.com");
        assertThat(event.getParentEmail()).isEqualTo("parent@test.com");
        verify(userRepository, never()).findById(PLAYER_ID);
    }

    // ---- cancelDueToPause tests ----

    @Test
    void cancelDueToPause_concurrentModification_throwsRetryableException() {
        UUID packId = UUID.randomUUID();
        Booking booking = makeBooking(PARENT_ID, PLAYER_ID, COACH_ID, "REQUESTED");
        booking.setSessionPackPurchaseId(packId);

        when(bookingRepository.findByIdForUpdate(booking.getId())).thenReturn(Optional.of(booking));
        when(bookingRepository.save(any(Booking.class))).thenThrow(new OptimisticLockingFailureException("test"));

        assertThatThrownBy(() -> bookingService.cancelDueToPause(booking.getId(), packId, COACH_ID, PARENT_ID))
            .isInstanceOf(OperationNotAllowedException.class)
            .hasCauseInstanceOf(OptimisticLockingFailureException.class)
            .satisfies(e -> assertThat(((OperationNotAllowedException) e).getErrorCode())
                .isEqualTo(BookingError.CONCURRENT_MODIFICATION));
    }

    // ---- fix/payment-session-pack-integrity: a pause cancelling a paid booking returns what was paid ----

    private com.softropic.skillars.platform.booking.contract.BookingCancelledDueToPauseEvent pauseEvent() {
        return publishedEvents().stream()
            .filter(com.softropic.skillars.platform.booking.contract.BookingCancelledDueToPauseEvent.class::isInstance)
            .map(com.softropic.skillars.platform.booking.contract.BookingCancelledDueToPauseEvent.class::cast)
            .findFirst().orElseThrow();
    }

    /**
     * fix/payment-pack-integrity: a card-paid booking is not the pause's business — pausing a pack used to cancel
     * it with a full refund, even inside the 24h forfeit window.
     */
    @Test
    void cancelDueToPause_cardBooking_notDrawnOnThePack_isRefused() {
        Booking booking = makeBooking(PARENT_ID, PLAYER_ID, COACH_ID, "CONFIRMED");
        booking.setRequestedStartTime(Instant.now().plus(3, java.time.temporal.ChronoUnit.DAYS));
        when(bookingRepository.findByIdForUpdate(booking.getId())).thenReturn(Optional.of(booking));

        assertThatThrownBy(() -> bookingService.cancelDueToPause(booking.getId(), UUID.randomUUID(), COACH_ID, PARENT_ID))
            .isInstanceOf(OperationNotAllowedException.class);

        verify(eventPublisher, never()).publishEvent(any());
        verify(bookingRepository, never()).save(any(Booking.class));
    }

    /** fix/payment-pack-integrity: a paid pack booking inside the 24h notice window is the parent's to cancel. */
    @Test
    void cancelDueToPause_paidPackBookingInside24h_isRefused() {
        UUID packId = UUID.randomUUID();
        Booking booking = makeBooking(PARENT_ID, PLAYER_ID, COACH_ID, "UPCOMING"); // starts in 2h
        booking.setSessionPackPurchaseId(packId);
        when(bookingRepository.findByIdForUpdate(booking.getId())).thenReturn(Optional.of(booking));

        assertThatThrownBy(() -> bookingService.cancelDueToPause(booking.getId(), packId, COACH_ID, PARENT_ID))
            .isInstanceOf(com.softropic.skillars.platform.booking.contract.BatchRuleViolationException.class)
            .hasMessageContaining("booking.pauseInsideCancellationNotice");

        verify(bookingRepository, never()).save(any(Booking.class));
    }

    /** The notice is measured from the earlier of the original and the current start (reschedule anchor). */
    @Test
    void cancelDueToPause_rescheduledOutOfTheWindow_stillInsideNoticeOfTheOriginalStart_isRefused() {
        UUID packId = UUID.randomUUID();
        Booking booking = makeBooking(PARENT_ID, PLAYER_ID, COACH_ID, "CONFIRMED");
        booking.setSessionPackPurchaseId(packId);
        booking.setOriginalStartTime(Instant.now().plus(10, java.time.temporal.ChronoUnit.HOURS));
        booking.setRequestedStartTime(Instant.now().plus(5, java.time.temporal.ChronoUnit.DAYS));
        when(bookingRepository.findByIdForUpdate(booking.getId())).thenReturn(Optional.of(booking));

        assertThatThrownBy(() -> bookingService.cancelDueToPause(booking.getId(), packId, COACH_ID, PARENT_ID))
            .isInstanceOf(com.softropic.skillars.platform.booking.contract.BatchRuleViolationException.class);
    }

    @Test
    void cancelDueToPause_capturedPackBooking_restoresTheSession() {
        UUID packId = UUID.randomUUID();
        Booking booking = makeBooking(PARENT_ID, PLAYER_ID, COACH_ID, "UPCOMING");
        booking.setRequestedStartTime(Instant.now().plus(3, java.time.temporal.ChronoUnit.DAYS));
        booking.setSessionPackPurchaseId(packId);
        com.softropic.skillars.platform.payment.repo.SessionPackPurchase pack =
            new com.softropic.skillars.platform.payment.repo.SessionPackPurchase();
        pack.setExpiresAt(Instant.now().plusSeconds(86_400));
        when(sessionPackPurchaseRepository.findById(packId)).thenReturn(Optional.of(pack));
        when(bookingRepository.findByIdForUpdate(booking.getId())).thenReturn(Optional.of(booking));

        bookingService.cancelDueToPause(booking.getId(), packId, COACH_ID, PARENT_ID);

        var event = pauseEvent();
        assertThat(event.isPackRestore()).isTrue();
        assertThat(event.isWalletRefund()).isFalse();
        assertThat(event.getSessionPackPurchaseId()).isEqualTo(packId);
    }

    @ParameterizedTest
    @ValueSource(strings = {"REQUESTED", "ACCEPTED"})
    void cancelDueToPause_notYetCaptured_returnsNothing(String status) {
        UUID packId = UUID.randomUUID();
        Booking booking = makeBooking(PARENT_ID, PLAYER_ID, COACH_ID, status);
        booking.setSessionPackPurchaseId(packId);
        when(bookingRepository.findByIdForUpdate(booking.getId())).thenReturn(Optional.of(booking));

        bookingService.cancelDueToPause(booking.getId(), packId, COACH_ID, PARENT_ID);

        var event = pauseEvent();
        assertThat(event.isPaymentCaptured()).isFalse();
        assertThat(event.isPackRestore()).isFalse();
        assertThat(event.isWalletRefund()).isFalse();
        verify(bookingPaymentRepository, never()).findById(any());
    }

    // ---- recordNoShowCoach tests (Deferred-63 AC4) ----

    @Test
    void recordNoShowCoach_beforeScheduledStartTime_throwsNoShowTooEarly() {
        Booking booking = makeBooking(PARENT_ID, PLAYER_ID, COACH_ID, "UPCOMING");
        booking.setRequestedStartTime(Instant.now().plusSeconds(3600));

        when(bookingRepository.findById(booking.getId())).thenReturn(Optional.of(booking));

        assertThatThrownBy(() -> bookingService.recordNoShowCoach(booking.getId(), PARENT_ID))
            .isInstanceOf(OperationNotAllowedException.class)
            .satisfies(e -> assertThat(((OperationNotAllowedException) e).getErrorCode())
                .isEqualTo(BookingError.NO_SHOW_TOO_EARLY));

        verify(bookingRepository, never()).save(any(Booking.class));
        verify(eventPublisher, never()).publishEvent(any(com.softropic.skillars.platform.booking.contract.CoachNoShowEvent.class));
    }

    @Test
    void recordNoShowCoach_afterScheduledStartTime_transitionsAndPublishesEvent() {
        Booking booking = makeBooking(PARENT_ID, PLAYER_ID, COACH_ID, "UPCOMING");
        booking.setRequestedStartTime(Instant.now().minusSeconds(3600));
        booking.setRequestedEndTime(Instant.now().minusSeconds(1800));

        when(bookingRepository.findById(booking.getId())).thenReturn(Optional.of(booking));
        when(bookingRepository.findByIdForUpdate(booking.getId())).thenReturn(Optional.of(booking));
        when(bookingRepository.save(any(Booking.class))).thenReturn(booking);
        when(coachPricingRepository.findByCoachId(COACH_ID)).thenReturn(Optional.of(makeCoachPricing(new BigDecimal("40.00"))));
        when(userRepository.findById(PARENT_ID)).thenReturn(Optional.of(makeUser("parent@test.com")));

        bookingService.recordNoShowCoach(booking.getId(), PARENT_ID);

        assertThat(booking.getStatus()).isEqualTo("NO_SHOW_COACH");
        verify(eventPublisher).publishEvent(any(com.softropic.skillars.platform.booking.contract.CoachNoShowEvent.class));
    }

    @Test
    void recordNoShowCoach_concurrentModification_throwsRetryableException() {
        Booking booking = makeBooking(PARENT_ID, PLAYER_ID, COACH_ID, "UPCOMING");
        booking.setRequestedStartTime(Instant.now().minusSeconds(3600));
        booking.setRequestedEndTime(Instant.now().minusSeconds(1800));

        when(bookingRepository.findById(booking.getId())).thenReturn(Optional.of(booking));
        when(bookingRepository.findByIdForUpdate(booking.getId())).thenReturn(Optional.of(booking));
        when(coachPricingRepository.findByCoachId(COACH_ID)).thenReturn(Optional.of(makeCoachPricing(new BigDecimal("40.00"))));
        when(userRepository.findById(PARENT_ID)).thenReturn(Optional.of(makeUser("parent@test.com")));
        when(bookingRepository.save(any(Booking.class))).thenThrow(new OptimisticLockingFailureException("test"));

        assertThatThrownBy(() -> bookingService.recordNoShowCoach(booking.getId(), PARENT_ID))
            .isInstanceOf(OperationNotAllowedException.class)
            .hasCauseInstanceOf(OptimisticLockingFailureException.class)
            .satisfies(e -> assertThat(((OperationNotAllowedException) e).getErrorCode())
                .isEqualTo(BookingError.CONCURRENT_MODIFICATION));
    }

    // ---- fix/audit3-completion-dispute-integrity ----

    /** Exploit 1: a Quick Complete at start+0 (never started) no longer shuts the family out of a no-show report. */
    @Test
    void recordNoShowCoach_onANeverStartedQuickComplete_transitionsAndPublishesEvent() {
        Booking booking = makeBooking(PARENT_ID, PLAYER_ID, COACH_ID, "COMPLETED_PENDING_CONFIRMATION");
        booking.setRequestedStartTime(Instant.now().minusSeconds(3600));
        booking.setRequestedEndTime(Instant.now().minusSeconds(1800));
        booking.setLiveStartedAt(null);

        when(bookingRepository.findById(booking.getId())).thenReturn(Optional.of(booking));
        when(bookingRepository.findByIdForUpdate(booking.getId())).thenReturn(Optional.of(booking));
        when(bookingRepository.save(any(Booking.class))).thenReturn(booking);
        when(coachPricingRepository.findByCoachId(COACH_ID)).thenReturn(Optional.of(makeCoachPricing(new BigDecimal("40.00"))));
        when(userRepository.findById(PARENT_ID)).thenReturn(Optional.of(makeUser("parent@test.com")));

        bookingService.recordNoShowCoach(booking.getId(), PARENT_ID);

        assertThat(booking.getStatus()).isEqualTo("NO_SHOW_COACH");
        verify(eventPublisher).publishEvent(any(com.softropic.skillars.platform.booking.contract.CoachNoShowEvent.class));
    }

    /** A session the coach actually started is disputed, never reported as a coach no-show. */
    @Test
    void recordNoShowCoach_onAStartedSessionAwaitingConfirmation_isRefused() {
        Booking booking = makeBooking(PARENT_ID, PLAYER_ID, COACH_ID, "COMPLETED_PENDING_CONFIRMATION");
        booking.setRequestedStartTime(Instant.now().minusSeconds(3600));
        booking.setRequestedEndTime(Instant.now().minusSeconds(1800));
        booking.setLiveStartedAt(Instant.now().minusSeconds(3500));

        when(bookingRepository.findById(booking.getId())).thenReturn(Optional.of(booking));
        when(bookingRepository.findByIdForUpdate(booking.getId())).thenReturn(Optional.of(booking));

        assertThatThrownBy(() -> bookingService.recordNoShowCoach(booking.getId(), PARENT_ID))
            .isInstanceOf(OperationNotAllowedException.class)
            .satisfies(e -> assertThat(((OperationNotAllowedException) e).getErrorCode())
                .isEqualTo(BookingError.SESSION_WAS_STARTED));
        assertThat(booking.getStatus()).isEqualTo("COMPLETED_PENDING_CONFIRMATION");
        verify(eventPublisher, never()).publishEvent(any(com.softropic.skillars.platform.booking.contract.CoachNoShowEvent.class));
    }

    /** Exploit 3: a coach cancel at or after the start is flagged so it always strikes, whatever the reason. */
    @Test
    void cancelBookingAsCoach_afterTheStart_isFlaggedAfterStart() {
        Booking booking = makeBooking(PARENT_ID, PLAYER_ID, COACH_ID, "UPCOMING");
        booking.setRequestedStartTime(Instant.now().minusSeconds(600));
        booking.setRequestedEndTime(Instant.now().plusSeconds(3000));
        CoachProfile coach = makeActiveCoach(COACH_ID, COACH_USER_ID);
        when(bookingRepository.findById(booking.getId())).thenReturn(Optional.of(booking));
        when(bookingRepository.findByIdForUpdate(booking.getId())).thenReturn(Optional.of(booking));
        when(coachProfileRepository.findByUserId(COACH_USER_ID)).thenReturn(Optional.of(coach));
        when(coachPricingRepository.findByCoachId(COACH_ID)).thenReturn(Optional.of(makeCoachPricing(new BigDecimal("50.00"))));
        when(userRepository.findById(PARENT_ID)).thenReturn(Optional.of(makeUser("parent@test.com")));
        when(bookingRepository.save(any(Booking.class))).thenReturn(booking);

        bookingService.cancelBookingAsCoach(booking.getId(), COACH_USER_ID, "WEATHER");

        assertThat(capturedEvent(BookingCancelledByCoachEvent.class).isCancelledAfterStart()).isTrue();
    }

    /** Owner decision (re-review MINOR 3): an ACCEPTED booking never started either — cancelled after its start, it strikes. */
    @Test
    void cancelBookingAsCoach_acceptedBookingAfterTheStart_isFlaggedAfterStart() {
        Booking booking = makeBooking(PARENT_ID, PLAYER_ID, COACH_ID, "ACCEPTED");
        booking.setRequestedStartTime(Instant.now().minusSeconds(600));
        booking.setRequestedEndTime(Instant.now().plusSeconds(3000));
        CoachProfile coach = makeActiveCoach(COACH_ID, COACH_USER_ID);
        when(bookingRepository.findById(booking.getId())).thenReturn(Optional.of(booking));
        when(bookingRepository.findByIdForUpdate(booking.getId())).thenReturn(Optional.of(booking));
        when(coachProfileRepository.findByUserId(COACH_USER_ID)).thenReturn(Optional.of(coach));
        when(userRepository.findById(PARENT_ID)).thenReturn(Optional.of(makeUser("parent@test.com")));
        when(bookingRepository.save(any(Booking.class))).thenReturn(booking);

        bookingService.cancelBookingAsCoach(booking.getId(), COACH_USER_ID, "WEATHER");

        assertThat(capturedEvent(BookingCancelledByCoachEvent.class).isCancelledAfterStart()).isTrue();
    }

    /** Owner decision (review MINOR 5): a PAUSED session was actually run — no forced no-show strike. */
    @Test
    void cancelBookingAsCoach_pausedSession_isNotFlaggedAfterStart() {
        Booking booking = makeBooking(PARENT_ID, PLAYER_ID, COACH_ID, "PAUSED");
        booking.setRequestedStartTime(Instant.now().minusSeconds(600));
        booking.setRequestedEndTime(Instant.now().plusSeconds(3000));
        booking.setLiveStartedAt(Instant.now().minusSeconds(500));
        CoachProfile coach = makeActiveCoach(COACH_ID, COACH_USER_ID);
        when(bookingRepository.findById(booking.getId())).thenReturn(Optional.of(booking));
        when(bookingRepository.findByIdForUpdate(booking.getId())).thenReturn(Optional.of(booking));
        when(coachProfileRepository.findByUserId(COACH_USER_ID)).thenReturn(Optional.of(coach));
        when(coachPricingRepository.findByCoachId(COACH_ID)).thenReturn(Optional.of(makeCoachPricing(new BigDecimal("50.00"))));
        when(userRepository.findById(PARENT_ID)).thenReturn(Optional.of(makeUser("parent@test.com")));
        when(bookingRepository.save(any(Booking.class))).thenReturn(booking);

        bookingService.cancelBookingAsCoach(booking.getId(), COACH_USER_ID, "WEATHER");

        assertThat(capturedEvent(BookingCancelledByCoachEvent.class).isCancelledAfterStart()).isFalse();
    }

    @Test
    void cancelBookingAsCoach_beforeTheStart_isNotFlaggedAfterStart() {
        Booking booking = makeBooking(PARENT_ID, PLAYER_ID, COACH_ID, "UPCOMING");
        CoachProfile coach = makeActiveCoach(COACH_ID, COACH_USER_ID);
        when(bookingRepository.findById(booking.getId())).thenReturn(Optional.of(booking));
        when(bookingRepository.findByIdForUpdate(booking.getId())).thenReturn(Optional.of(booking));
        when(coachProfileRepository.findByUserId(COACH_USER_ID)).thenReturn(Optional.of(coach));
        when(coachPricingRepository.findByCoachId(COACH_ID)).thenReturn(Optional.of(makeCoachPricing(new BigDecimal("50.00"))));
        when(userRepository.findById(PARENT_ID)).thenReturn(Optional.of(makeUser("parent@test.com")));
        when(bookingRepository.save(any(Booking.class))).thenReturn(booking);

        bookingService.cancelBookingAsCoach(booking.getId(), COACH_USER_ID, "WEATHER");

        assertThat(capturedEvent(BookingCancelledByCoachEvent.class).isCancelledAfterStart()).isFalse();
    }

    /** Exploit 4: the refund-eligible late cancel after start + grace is the coach's no-show. */
    @Test
    void cancelBookingAsParent_31MinutesAfterStartStillUnstarted_isACoachNoShow() {
        cancelConfirmedAsParent(Instant.now().minus(31, java.time.temporal.ChronoUnit.MINUTES), null);
        assertThat(capturedParentCancellation().isCoachNoShow()).isTrue();
    }

    @Test
    void cancelBookingAsParent_withAmpleNotice_isNotACoachNoShow() {
        cancelConfirmedAsParent(Instant.now().plus(3, java.time.temporal.ChronoUnit.DAYS), null);
        assertThat(capturedParentCancellation().isRefundEligible()).isTrue();
        assertThat(capturedParentCancellation().isCoachNoShow()).isFalse();
    }

    @Test
    void cancelBookingAsParent_insideTheGrace_isNotACoachNoShow() {
        cancelConfirmedAsParent(Instant.now().minus(10, java.time.temporal.ChronoUnit.MINUTES), null);
        assertThat(capturedParentCancellation().isCoachNoShow()).isFalse();
    }

    // ---- setPlayerRequest tests (skillars-deferred-155 AC5/AC8) ----

    @Test
    void setPlayerRequest_selfRegisteredPlayerOwnsBooking_savesAndReturnsIt() {
        Booking booking = makeBooking(PARENT_ID, PLAYER_ID, COACH_ID, "CONFIRMED");
        when(bookingRepository.findById(booking.getId())).thenReturn(Optional.of(booking));

        PlayerRequestResponse response = bookingService.setPlayerRequest(booking.getId(), PARENT_ID, "Work on free kicks");

        assertThat(response.bookingId()).isEqualTo(booking.getId());
        assertThat(response.playerRequest()).isEqualTo("Work on free kicks");
        assertThat(booking.getPlayerRequest()).isEqualTo("Work on free kicks");
    }

    @Test
    void setPlayerRequest_upcomingStatus_isAlsoEditable() {
        Booking booking = makeBooking(PARENT_ID, PLAYER_ID, COACH_ID, "UPCOMING");
        when(bookingRepository.findById(booking.getId())).thenReturn(Optional.of(booking));

        assertThatCode(() -> bookingService.setPlayerRequest(booking.getId(), PARENT_ID, "Shooting drills"))
            .doesNotThrowAnyException();
    }

    @Test
    void setPlayerRequest_blankValue_clearsExistingRequest() {
        Booking booking = makeBooking(PARENT_ID, PLAYER_ID, COACH_ID, "CONFIRMED");
        booking.setPlayerRequest("old request");
        when(bookingRepository.findById(booking.getId())).thenReturn(Optional.of(booking));

        bookingService.setPlayerRequest(booking.getId(), PARENT_ID, "   ");

        assertThat(booking.getPlayerRequest()).isNull();
    }

    @Test
    void setPlayerRequest_callerDoesNotOwnBooking_throwsOperationNotAllowed() {
        Booking booking = makeBooking(PARENT_ID, PLAYER_ID, COACH_ID, "CONFIRMED");
        when(bookingRepository.findById(booking.getId())).thenReturn(Optional.of(booking));

        Long impostorId = PARENT_ID + 999;
        assertThatThrownBy(() -> bookingService.setPlayerRequest(booking.getId(), impostorId, "not mine"))
            .isInstanceOf(OperationNotAllowedException.class);
    }

    @Test
    void setPlayerRequest_bookingStatusOutsideEditableWindow_throwsBookingNotEditable() {
        Booking booking = makeBooking(PARENT_ID, PLAYER_ID, COACH_ID, "REQUESTED");
        when(bookingRepository.findById(booking.getId())).thenReturn(Optional.of(booking));

        assertThatThrownBy(() -> bookingService.setPlayerRequest(booking.getId(), PARENT_ID, "too early"))
            .isInstanceOf(OperationNotAllowedException.class)
            .satisfies(e -> assertThat(((OperationNotAllowedException) e).getErrorCode())
                .isEqualTo(BookingError.BOOKING_NOT_EDITABLE));
    }

    /**
     * skillars-deferred-155 code review, Decision 4(a): the status allow-list alone left a
     * never-completed UPCOMING booking editable forever — nothing sweeps UPCOMING
     * ({@code StuckCompletionSweeperService} only handles COMPLETED_PENDING_CONFIRMATION,
     * {@code BookingExpiryScheduler} only stale pre-confirmation requests), so a session the coach
     * simply never wrapped up kept accepting "work on X next time" edits long after it had happened.
     * Status is still UPCOMING here — only the clock has moved past requestedEndTime — so this case
     * fails unless the clock bound is actually present.
     */
    @Test
    void setPlayerRequest_upcomingButSessionAlreadyEnded_throwsSessionAlreadyEnded() {
        Booking booking = makeBooking(PARENT_ID, PLAYER_ID, COACH_ID, "UPCOMING");
        booking.setRequestedStartTime(Instant.now().minusSeconds(10800));
        booking.setRequestedEndTime(Instant.now().minusSeconds(7200));
        when(bookingRepository.findById(booking.getId())).thenReturn(Optional.of(booking));

        assertThatThrownBy(() -> bookingService.setPlayerRequest(booking.getId(), PARENT_ID, "after the fact"))
            .isInstanceOf(OperationNotAllowedException.class)
            .satisfies(e -> assertThat(((OperationNotAllowedException) e).getErrorCode())
                .isEqualTo(BookingError.SESSION_ALREADY_ENDED));
        assertThat(booking.getPlayerRequest()).isNull();
    }

    /**
     * The clock bound is on requestedEndTime, not requestedStartTime, deliberately: a parent who
     * submits while the session is under way must still succeed, so an edit landing between start and
     * end is allowed rather than rejected.
     */
    @Test
    void setPlayerRequest_sessionUnderWayButNotYetEnded_isStillEditable() {
        Booking booking = makeBooking(PARENT_ID, PLAYER_ID, COACH_ID, "UPCOMING");
        booking.setRequestedStartTime(Instant.now().minusSeconds(600));
        booking.setRequestedEndTime(Instant.now().plusSeconds(600));
        when(bookingRepository.findById(booking.getId())).thenReturn(Optional.of(booking));

        bookingService.setPlayerRequest(booking.getId(), PARENT_ID, "Work on crossing");

        assertThat(booking.getPlayerRequest()).isEqualTo("Work on crossing");
    }

    @Test
    void setPlayerRequest_completedBooking_throwsBookingNotEditable() {
        Booking booking = makeBooking(PARENT_ID, PLAYER_ID, COACH_ID, "COMPLETED");
        when(bookingRepository.findById(booking.getId())).thenReturn(Optional.of(booking));

        assertThatThrownBy(() -> bookingService.setPlayerRequest(booking.getId(), PARENT_ID, "too late"))
            .isInstanceOf(OperationNotAllowedException.class)
            .satisfies(e -> assertThat(((OperationNotAllowedException) e).getErrorCode())
                .isEqualTo(BookingError.BOOKING_NOT_EDITABLE));
    }

    /**
     * skillars-deferred-163 AC3.3: rejectIfNotEditablePlayerRequest's clock source moved to
     * ClockProvider, re-using Booking.isPrepNoteEditable's time half (its status half is already
     * guaranteed true by the status check above it) — the two distinct error codes
     * (BOOKING_NOT_EDITABLE for status, SESSION_ALREADY_ENDED for the clock) must still fire
     * independently after that change.
     */
    @Test
    void setPlayerRequest_bothErrorCodesStillFireIndependently_afterClockSourceChange() {
        Booking statusRejected = makeBooking(PARENT_ID, PLAYER_ID, COACH_ID, "REQUESTED");
        when(bookingRepository.findById(statusRejected.getId())).thenReturn(Optional.of(statusRejected));
        assertThatThrownBy(() -> bookingService.setPlayerRequest(statusRejected.getId(), PARENT_ID, "too early"))
            .isInstanceOf(OperationNotAllowedException.class)
            .satisfies(e -> assertThat(((OperationNotAllowedException) e).getErrorCode())
                .isEqualTo(BookingError.BOOKING_NOT_EDITABLE));

        Booking clockRejected = makeBooking(PARENT_ID, PLAYER_ID, COACH_ID, "UPCOMING");
        clockRejected.setRequestedStartTime(Instant.now().minusSeconds(10800));
        clockRejected.setRequestedEndTime(Instant.now().minusSeconds(7200));
        when(bookingRepository.findById(clockRejected.getId())).thenReturn(Optional.of(clockRejected));
        assertThatThrownBy(() -> bookingService.setPlayerRequest(clockRejected.getId(), PARENT_ID, "after the fact"))
            .isInstanceOf(OperationNotAllowedException.class)
            .satisfies(e -> assertThat(((OperationNotAllowedException) e).getErrorCode())
                .isEqualTo(BookingError.SESSION_ALREADY_ENDED));
    }

    @Test
    void setPlayerRequest_concurrentModification_throwsRetryableException() {
        Booking booking = makeBooking(PARENT_ID, PLAYER_ID, COACH_ID, "CONFIRMED");
        when(bookingRepository.findById(booking.getId())).thenReturn(Optional.of(booking));
        when(bookingRepository.save(any(Booking.class))).thenThrow(new OptimisticLockingFailureException("test"));

        assertThatThrownBy(() -> bookingService.setPlayerRequest(booking.getId(), PARENT_ID, "race"))
            .isInstanceOf(OperationNotAllowedException.class)
            .hasCauseInstanceOf(OptimisticLockingFailureException.class)
            .satisfies(e -> assertThat(((OperationNotAllowedException) e).getErrorCode())
                .isEqualTo(BookingError.CONCURRENT_MODIFICATION));
    }

    // ---- setCoachPlanNote tests (skillars-12-6 AC2/AC15) ----

    private Booking planNoteBooking(String status) {
        Booking booking = makeBooking(PARENT_ID, PLAYER_ID, COACH_ID, status);
        when(bookingRepository.findById(booking.getId())).thenReturn(Optional.of(booking));
        when(coachProfileRepository.findByUserId(COACH_USER_ID))
            .thenReturn(Optional.of(makeActiveCoach(COACH_ID, COACH_USER_ID)));
        return booking;
    }

    @Test
    void setCoachPlanNote_owningCoach_savesAndReturnsIt() {
        Booking booking = planNoteBooking("CONFIRMED");

        CoachPlanNoteResponse response = bookingService.setCoachPlanNote(booking.getId(), COACH_USER_ID, "Plan A");

        assertThat(response.bookingId()).isEqualTo(booking.getId());
        assertThat(response.planNote()).isEqualTo("Plan A");
        assertThat(booking.getCoachPlanNote()).isEqualTo("Plan A");
    }

    @Test
    void setCoachPlanNote_blankClearsExistingNote() {
        Booking booking = planNoteBooking("UPCOMING");
        booking.setCoachPlanNote("old");

        bookingService.setCoachPlanNote(booking.getId(), COACH_USER_ID, "   ");

        assertThat(booking.getCoachPlanNote()).isNull();
    }

    @Test
    void setCoachPlanNote_otherCoach_throwsMissingRights() {
        Booking booking = planNoteBooking("CONFIRMED");
        booking.setCoachId(UUID.randomUUID());

        assertThatThrownBy(() -> bookingService.setCoachPlanNote(booking.getId(), COACH_USER_ID, "x"))
            .isInstanceOf(OperationNotAllowedException.class);
        assertThat(booking.getCoachPlanNote()).isNull();
    }

    @Test
    void setCoachPlanNote_unknownCoachProfile_throwsNotFound() {
        Booking booking = makeBooking(PARENT_ID, PLAYER_ID, COACH_ID, "CONFIRMED");
        when(bookingRepository.findById(booking.getId())).thenReturn(Optional.of(booking));
        when(coachProfileRepository.findByUserId(COACH_USER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> bookingService.setCoachPlanNote(booking.getId(), COACH_USER_ID, "x"))
            .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void setCoachPlanNote_statusNotEditable_throwsBookingNotEditable() {
        Booking booking = planNoteBooking("IN_PROGRESS");

        assertThatThrownBy(() -> bookingService.setCoachPlanNote(booking.getId(), COACH_USER_ID, "x"))
            .isInstanceOf(OperationNotAllowedException.class)
            .satisfies(e -> assertThat(((OperationNotAllowedException) e).getErrorCode())
                .isEqualTo(BookingError.BOOKING_NOT_EDITABLE));
    }

    @Test
    void setCoachPlanNote_sessionAlreadyEnded_throwsSessionAlreadyEnded() {
        Booking booking = planNoteBooking("UPCOMING");
        Instant start = Instant.now().minusSeconds(7200);
        booking.setRequestedStartTime(start);
        booking.setRequestedEndTime(start.plusSeconds(3600));

        assertThatThrownBy(() -> bookingService.setCoachPlanNote(booking.getId(), COACH_USER_ID, "x"))
            .isInstanceOf(OperationNotAllowedException.class)
            .satisfies(e -> assertThat(((OperationNotAllowedException) e).getErrorCode())
                .isEqualTo(BookingError.SESSION_ALREADY_ENDED));
    }

    @Test
    void setCoachPlanNote_concurrentModification_mapsToConcurrentModification() {
        Booking booking = planNoteBooking("CONFIRMED");
        when(bookingRepository.saveAndFlush(any(Booking.class))).thenThrow(new OptimisticLockingFailureException("test"));

        assertThatThrownBy(() -> bookingService.setCoachPlanNote(booking.getId(), COACH_USER_ID, "race"))
            .isInstanceOf(OperationNotAllowedException.class)
            .hasCauseInstanceOf(OptimisticLockingFailureException.class)
            .satisfies(e -> assertThat(((OperationNotAllowedException) e).getErrorCode())
                .isEqualTo(BookingError.CONCURRENT_MODIFICATION));
    }

    @Test
    void setCoachPlanNote_sanitizerExpandsPastColumnLimit_truncatesTo500Chars() {
        Booking booking = planNoteBooking("CONFIRMED");
        when(contactDetailSanitizer.sanitize("expand"))
            .thenReturn(new com.softropic.skillars.infrastructure.sanitizer.ContactDetailSanitizer.SanitizerResult(
                "x".repeat(600), true));

        bookingService.setCoachPlanNote(booking.getId(), COACH_USER_ID, "expand");

        assertThat(booking.getCoachPlanNote()).hasSize(500);
    }

    @Test
    void isPrepNoteEditable_matchesStatusAndEndTimeBound() {
        Booking booking = makeBooking(PARENT_ID, PLAYER_ID, COACH_ID, "CONFIRMED");
        Instant end = booking.getRequestedEndTime();

        assertThat(booking.isPrepNoteEditable(end.minusSeconds(1))).isTrue();
        assertThat(booking.isPrepNoteEditable(end)).isFalse();
        booking.setStatus("IN_PROGRESS");
        assertThat(booking.isPrepNoteEditable(end.minusSeconds(1))).isFalse();
    }

    @Test
    void setPlayerRequest_sanitizerInvoked_persistsSanitizedResult() {
        Booking booking = makeBooking(PARENT_ID, PLAYER_ID, COACH_ID, "CONFIRMED");
        when(bookingRepository.findById(booking.getId())).thenReturn(Optional.of(booking));
        when(contactDetailSanitizer.sanitize("call me at 555-123-4567"))
            .thenReturn(new com.softropic.skillars.infrastructure.sanitizer.ContactDetailSanitizer.SanitizerResult(
                "call me at [contact details removed]", true));

        bookingService.setPlayerRequest(booking.getId(), PARENT_ID, "call me at 555-123-4567");

        assertThat(booking.getPlayerRequest()).isEqualTo("call me at [contact details removed]");
    }

    // skillars-deferred-155 AC4: REDACTION's replacement text is longer than most of what it
    // matches, so sanitized output can exceed a @Size(max=500)-validated input's length — truncate
    // after sanitizing so a save can never fail on this path.
    @Test
    void setPlayerRequest_sanitizerExpandsPastColumnLimit_truncatesTo500Chars() {
        Booking booking = makeBooking(PARENT_ID, PLAYER_ID, COACH_ID, "CONFIRMED");
        when(bookingRepository.findById(booking.getId())).thenReturn(Optional.of(booking));
        String oversized = "x".repeat(600);
        when(contactDetailSanitizer.sanitize(anyString()))
            .thenReturn(new com.softropic.skillars.infrastructure.sanitizer.ContactDetailSanitizer.SanitizerResult(
                oversized, true));

        bookingService.setPlayerRequest(booking.getId(), PARENT_ID, "short input that expands");

        assertThat(booking.getPlayerRequest()).hasSize(500);
    }

    // ---- sanitizeAndTruncate tests (skillars-deferred-156 AC2) ----

    @Test
    void sanitizeAndTruncate_blankInput_returnsNull() {
        assertThat(BookingService.sanitizeAndTruncate(contactDetailSanitizer, "   ", 500)).isNull();
    }

    @Test
    void sanitizeAndTruncate_nullInput_returnsNull() {
        assertThat(BookingService.sanitizeAndTruncate(contactDetailSanitizer, null, 500)).isNull();
    }

    // commons-lang3 3.21.0's StringUtils.truncate is already surrogate-pair-aware — this documents
    // that guarantee (no new guard code backs it): a sanitized value with an astral-plane character
    // straddling the truncation boundary truncates to maxLength - 1, not a dangling high surrogate.
    // (code review of skillars-deferred-156) Assert on isHighSurrogate, not isLowSurrogate: cutting a
    // surrogate pair at maxLength keeps the pair's FIRST unit, so a split leaves a dangling HIGH
    // surrogate at the tail and can never leave a low one. The original isLowSurrogate assertion was
    // therefore vacuous — it checked the half that cannot occur, and would have passed against a
    // genuinely broken truncate.
    @Test
    void sanitizeAndTruncate_astralCharacterStraddlesBoundary_backsOffRatherThanSplittingSurrogatePair() {
        String prefix = "a".repeat(499);
        String withAstralAtBoundary = prefix + "😀"; // U+1F600, a surrogate pair
        when(contactDetailSanitizer.sanitize(withAstralAtBoundary))
            .thenReturn(new com.softropic.skillars.infrastructure.sanitizer.ContactDetailSanitizer.SanitizerResult(
                withAstralAtBoundary, false));

        String result = BookingService.sanitizeAndTruncate(contactDetailSanitizer, withAstralAtBoundary, 500);

        assertThat(result).hasSize(499).isEqualTo(prefix);
        assertThat(Character.isHighSurrogate(result.charAt(result.length() - 1))).isFalse();
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

    /** Deferred-81 AC1: a player with a caller-chosen name, for batched-lookup test assertions. */
    private PlayerProfile makePlayerNamed(Long id, Long parentId, String name) {
        PlayerProfile p = makePlayer(id, parentId);
        p.setName(name);
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

    /** Deferred-81 AC1: a coach with a caller-chosen display name, for batched-lookup test assertions. */
    private CoachProfile makeCoachNamed(UUID coachId, Long userId, String displayName) {
        CoachProfile c = makeActiveCoach(coachId, userId);
        c.setDisplayName(displayName);
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
        return new CreateBookingRequest(coachId, playerId, start, end, "test notes", null, null);
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

    /** Deferred-81 AC1: a User with an explicit id/name, for resolveParentNames batch-lookup tests. */
    private User makeUserWithNameAndId(Long id, String firstName, String lastName) {
        User u = new User();
        u.setId(id);
        u.setFirstName(firstName);
        u.setLastName(lastName);
        return u;
    }
}
