package com.softropic.skillars.platform.booking.service;

import com.softropic.skillars.infrastructure.exception.ResourceNotFoundException;
import com.softropic.skillars.infrastructure.persistence.PessimisticLockRetryer;
import com.softropic.skillars.platform.booking.contract.BookingError;
import com.softropic.skillars.platform.booking.contract.DuplicateBookingProposedEvent;
import com.softropic.skillars.platform.booking.repo.Booking;
import com.softropic.skillars.platform.booking.repo.BookingRepository;
import com.softropic.skillars.platform.marketplace.contract.CoachProfileStatus;
import com.softropic.skillars.platform.marketplace.repo.CoachAvailabilityWindowRepository;
import com.softropic.skillars.platform.marketplace.repo.CoachProfile;
import com.softropic.skillars.platform.marketplace.repo.CoachProfileRepository;
import com.softropic.skillars.platform.payment.repo.SessionPackPurchase;
import com.softropic.skillars.platform.payment.repo.SessionPackPurchaseRepository;
import com.softropic.skillars.platform.security.contract.exception.OperationNotAllowedException;
import com.softropic.skillars.platform.security.repo.User;
import com.softropic.skillars.platform.security.repo.UserRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class BookingDuplicationServiceTest {

    @Mock private BookingService bookingService;
    @Mock private BookingRepository bookingRepository;
    @Mock private CoachProfileRepository coachProfileRepository;
    @Mock private UserRepository userRepository;
    @Mock private SessionPackPurchaseRepository sessionPackPurchaseRepository;
    @Mock private ApplicationEventPublisher eventPublisher;
    @Mock private CoachAvailabilityWindowRepository coachAvailabilityWindowRepository;
    @Mock private EntityManager entityManager;
    @Mock private PessimisticLockRetryer lockRetryer;

    private BookingDuplicationService service;

    private static final UUID ORIGINAL_BOOKING_ID = UUID.randomUUID();
    private static final UUID COACH_ID = UUID.randomUUID();
    private static final Long COACH_USER_ID = 100L;
    private static final Long PARENT_ID = 300L;
    private static final Long PLAYER_ID = 200L;

    private Booking completedBooking;
    private CoachProfile coach;

    @BeforeEach
    void setUp() {
        service = new BookingDuplicationService(
            bookingService, bookingRepository, coachProfileRepository,
            userRepository, sessionPackPurchaseRepository, eventPublisher, coachAvailabilityWindowRepository,
            entityManager, lockRetryer
        );
        lenient().when(lockRetryer.withBoundedRetry(anyString(), any()))
            .thenAnswer(inv -> ((java.util.function.Supplier<?>) inv.getArgument(1)).get());
        // fix/audit3-booking-scheduling-integrity: a pack with a free (unreserved) session unless a test says otherwise.
        lenient().when(bookingService.hasUnreservedPackSession(any())).thenReturn(true);

        Instant past = Instant.now().minus(8, ChronoUnit.DAYS);
        completedBooking = new Booking();
        completedBooking.setCoachId(COACH_ID);
        completedBooking.setPlayerId(PLAYER_ID);
        completedBooking.setParentId(PARENT_ID);
        completedBooking.setStatus("COMPLETED");
        completedBooking.setRequestedStartTime(past);
        completedBooking.setRequestedEndTime(past.plus(1, ChronoUnit.HOURS));
        completedBooking.setCanonicalTimezone("Europe/Berlin");

        coach = new CoachProfile();
        coach.setId(COACH_ID);
        coach.setUserId(COACH_USER_ID);
        coach.setDisplayName("Test Coach");
    }

    @Test
    void duplicateNextWeek_completedBooking_createsNewRequestedBookingAdvancedBy7DaysAndCarriesOverPack() {
        UUID activePackId = UUID.randomUUID();
        when(bookingService.getBookingOrThrow(ORIGINAL_BOOKING_ID)).thenReturn(completedBooking);
        when(coachProfileRepository.findByUserId(COACH_USER_ID)).thenReturn(Optional.of(coach));
        when(coachProfileRepository.findByIdForUpdate(COACH_ID)).thenReturn(Optional.of(coach));
        when(bookingRepository.findOverlappingBookings(any(), any(), any(), any(), any())).thenReturn(List.of());
        when(sessionPackPurchaseRepository.findActivePacks(eq(PLAYER_ID), eq(COACH_ID), any()))
            .thenReturn(List.of(pack(activePackId, PARENT_ID)));

        // Override the start time to be far enough in the past that +7 days is in the future
        Instant originalStart = Instant.now().minus(6, ChronoUnit.DAYS);
        completedBooking.setRequestedStartTime(originalStart);
        completedBooking.setRequestedEndTime(originalStart.plus(1, ChronoUnit.HOURS));

        User parent = new User();
        parent.setEmail("parent@test.com");
        when(userRepository.findById(PARENT_ID)).thenReturn(Optional.of(parent));
        when(bookingRepository.save(any())).thenAnswer(i -> {
            Booking b = i.getArgument(0);
            return b;
        });

        service.duplicateNextWeek(ORIGINAL_BOOKING_ID, COACH_USER_ID);

        ArgumentCaptor<Booking> bookingCaptor = ArgumentCaptor.forClass(Booking.class);
        verify(bookingRepository).save(bookingCaptor.capture());
        Booking saved = bookingCaptor.getValue();

        // fix/booking-completion-integrity: "next week" in the booking's zone (differs from +7x24h across DST).
        java.time.ZoneId berlin = java.time.ZoneId.of("Europe/Berlin");
        Instant expectedStart = originalStart.atZone(berlin).plusWeeks(1).toInstant();
        Instant expectedEnd = originalStart.plus(1, ChronoUnit.HOURS).atZone(berlin).plusWeeks(1).toInstant();
        // Deferred-50 AC3: verify the actual duplicated-slot times were passed, not (for example)
        // the original booking's pre-duplication times — an argument-swap regression. Also pins
        // the exact message/detail-map wording this call site must keep (skillars-deferred-164 AC2).
        verify(bookingService).requireSlotWithinAvailabilityWindow(eq(expectedStart), eq(expectedEnd),
            any(), any(), any(), eq("Proposed slot is not within coach availability"),
            eq(Map.of("proposed start time", expectedStart, "proposed end time", expectedEnd)));
        assertThat(saved.getRequestedStartTime()).isEqualTo(expectedStart);
        assertThat(saved.getCoachId()).isEqualTo(COACH_ID);
        assertThat(saved.getPlayerId()).isEqualTo(PLAYER_ID);
        assertThat(saved.getParentId()).isEqualTo(PARENT_ID);
        assertThat(saved.getSessionPackPurchaseId()).isEqualTo(activePackId);
        // fix/booking-request-consent (H3): only the parent can confirm it; the coach cannot accept it.
        assertThat(saved.isProposedByCoach()).isTrue();
        assertThat(saved.getStatus()).isNull(); // REQUESTED by @PrePersist, like every new request

        ArgumentCaptor<DuplicateBookingProposedEvent> eventCaptor =
            ArgumentCaptor.forClass(DuplicateBookingProposedEvent.class);
        verify(eventPublisher).publishEvent(eventCaptor.capture());
        assertThat(eventCaptor.getValue().getParentEmail()).isEqualTo("parent@test.com");
        assertThat(eventCaptor.getValue().getCoachDisplayName()).isEqualTo("Test Coach");
    }

    /**
     * fix/booking-completion-integrity: across the spring-forward change (Europe/Berlin, 2027-03-28) the
     * duplicate keeps the local wall-clock time (10:00 → 10:00), i.e. one hour less than a fixed 7 x 24h.
     */
    @Test
    void duplicateNextWeek_acrossADstChange_keepsTheLocalWallClockTime() {
        when(bookingService.getBookingOrThrow(ORIGINAL_BOOKING_ID)).thenReturn(completedBooking);
        when(coachProfileRepository.findByUserId(COACH_USER_ID)).thenReturn(Optional.of(coach));
        when(coachProfileRepository.findByIdForUpdate(COACH_ID)).thenReturn(Optional.of(coach));
        when(bookingRepository.findOverlappingBookings(any(), any(), any(), any(), any())).thenReturn(List.of());
        when(sessionPackPurchaseRepository.findActivePacks(eq(PLAYER_ID), eq(COACH_ID), any())).thenReturn(List.of());
        when(userRepository.findById(PARENT_ID)).thenReturn(Optional.empty());
        when(bookingRepository.save(any())).thenAnswer(i -> i.getArgument(0));
        Instant originalStart = Instant.parse("2027-03-22T09:00:00Z"); // 10:00 CET
        completedBooking.setRequestedStartTime(originalStart);
        completedBooking.setRequestedEndTime(originalStart.plus(1, ChronoUnit.HOURS));

        service.duplicateNextWeek(ORIGINAL_BOOKING_ID, COACH_USER_ID);

        ArgumentCaptor<Booking> saved = ArgumentCaptor.forClass(Booking.class);
        verify(bookingRepository).save(saved.capture());
        assertThat(saved.getValue().getRequestedStartTime()).isEqualTo(Instant.parse("2027-03-29T08:00:00Z")); // 10:00 CEST
        assertThat(saved.getValue().getRequestedEndTime()).isEqualTo(Instant.parse("2027-03-29T09:00:00Z"));
    }

    @Test
    void duplicateNextWeek_wrongCoach_throws403() {
        when(bookingService.getBookingOrThrow(ORIGINAL_BOOKING_ID)).thenReturn(completedBooking);
        CoachProfile wrongCoach = new CoachProfile();
        wrongCoach.setId(UUID.randomUUID());
        wrongCoach.setUserId(COACH_USER_ID);
        when(coachProfileRepository.findByUserId(COACH_USER_ID)).thenReturn(Optional.of(wrongCoach));

        assertThatThrownBy(() -> service.duplicateNextWeek(ORIGINAL_BOOKING_ID, COACH_USER_ID))
            .isInstanceOf(OperationNotAllowedException.class);

        verify(bookingRepository, never()).save(any());
    }

    /** Review-Deferred-58: coach profile deleted/missing between the ownership check and the new lock. */
    @Test
    void duplicateNextWeek_coachProfileMissingAtLockTime_throwsResourceNotFoundException() {
        when(bookingService.getBookingOrThrow(ORIGINAL_BOOKING_ID)).thenReturn(completedBooking);
        when(coachProfileRepository.findByUserId(COACH_USER_ID)).thenReturn(Optional.of(coach));
        when(coachProfileRepository.findByIdForUpdate(COACH_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.duplicateNextWeek(ORIGINAL_BOOKING_ID, COACH_USER_ID))
            .isInstanceOf(ResourceNotFoundException.class);

        verify(bookingRepository, never()).save(any());
    }

    /** Deferred-63 AC2: mirrors acceptReschedule's existing SUSPENDED check on its identical lock pattern. */
    @Test
    void duplicateNextWeek_suspendedCoach_throwsCoachUnavailable() {
        when(bookingService.getBookingOrThrow(ORIGINAL_BOOKING_ID)).thenReturn(completedBooking);
        when(coachProfileRepository.findByUserId(COACH_USER_ID)).thenReturn(Optional.of(coach));
        when(coachProfileRepository.findByIdForUpdate(COACH_ID)).thenReturn(Optional.of(coach));
        // entityManager.refresh(coach, ...) is a no-op mock — mutate coach directly to stand in for
        // the locked, refreshed row's real state, matching this file's existing lock-mock convention.
        coach.setStatus(CoachProfileStatus.SUSPENDED);

        assertThatThrownBy(() -> service.duplicateNextWeek(ORIGINAL_BOOKING_ID, COACH_USER_ID))
            .isInstanceOf(OperationNotAllowedException.class)
            .satisfies(e -> assertThat(((OperationNotAllowedException) e).getErrorCode())
                .isEqualTo(BookingError.COACH_UNAVAILABLE));

        verify(bookingRepository, never()).save(any());
    }

    @Test
    void duplicateNextWeek_notCompletedStatus_throws() {
        completedBooking.setStatus("CONFIRMED");
        when(bookingService.getBookingOrThrow(ORIGINAL_BOOKING_ID)).thenReturn(completedBooking);
        when(coachProfileRepository.findByUserId(COACH_USER_ID)).thenReturn(Optional.of(coach));

        assertThatThrownBy(() -> service.duplicateNextWeek(ORIGINAL_BOOKING_ID, COACH_USER_ID))
            .isInstanceOf(OperationNotAllowedException.class)
            .hasMessageContaining("COMPLETED");
    }

    /**
     * fix/booking-request-consent (packs-subs L2): a card-paid session can be repeated — no active pack used
     * to throw, so the price-lock branch was dead. Now the coach's current price is locked instead.
     */
    @Test
    void duplicateNextWeek_noActivePack_locksTheCoachPriceInstead() {
        Instant originalStart = Instant.now().minus(6, ChronoUnit.DAYS);
        completedBooking.setRequestedStartTime(originalStart);
        completedBooking.setRequestedEndTime(originalStart.plus(1, ChronoUnit.HOURS));
        stubHappyPathUpToPack();
        when(sessionPackPurchaseRepository.findActivePacks(eq(PLAYER_ID), eq(COACH_ID), any())).thenReturn(List.of());
        when(bookingService.requireRequestTimePrice(COACH_ID)).thenReturn(new BigDecimal("45.00"));

        service.duplicateNextWeek(ORIGINAL_BOOKING_ID, COACH_USER_ID);

        ArgumentCaptor<Booking> captor = ArgumentCaptor.forClass(Booking.class);
        verify(bookingRepository).save(captor.capture());
        assertThat(captor.getValue().getSessionPackPurchaseId()).isNull();
        assertThat(captor.getValue().getAgreedPrice()).isEqualByComparingTo("45.00");
        assertThat(captor.getValue().isProposedByCoach()).isTrue();
    }

    /**
     * fix/booking-request-consent (packs-subs L2): the active pack must belong to the booking's own parent —
     * a pack another parent of the same player bought is never attached; the next one of this parent is.
     */
    @Test
    void duplicateNextWeek_skipsAPackBoughtByAnotherParent() {
        Instant originalStart = Instant.now().minus(6, ChronoUnit.DAYS);
        completedBooking.setRequestedStartTime(originalStart);
        completedBooking.setRequestedEndTime(originalStart.plus(1, ChronoUnit.HOURS));
        stubHappyPathUpToPack();
        UUID otherParentsPack = UUID.randomUUID();
        UUID ownPack = UUID.randomUUID();
        when(sessionPackPurchaseRepository.findActivePacks(eq(PLAYER_ID), eq(COACH_ID), any()))
            .thenReturn(List.of(pack(otherParentsPack, 999L), pack(ownPack, PARENT_ID)));

        service.duplicateNextWeek(ORIGINAL_BOOKING_ID, COACH_USER_ID);

        ArgumentCaptor<Booking> captor = ArgumentCaptor.forClass(Booking.class);
        verify(bookingRepository).save(captor.capture());
        assertThat(captor.getValue().getSessionPackPurchaseId()).isEqualTo(ownPack);
        verify(bookingService, never()).requireRequestTimePrice(any());
    }

    @Test
    void duplicateNextWeek_onlyAnotherParentsPack_fallsBackToThePriceLock() {
        Instant originalStart = Instant.now().minus(6, ChronoUnit.DAYS);
        completedBooking.setRequestedStartTime(originalStart);
        completedBooking.setRequestedEndTime(originalStart.plus(1, ChronoUnit.HOURS));
        stubHappyPathUpToPack();
        when(sessionPackPurchaseRepository.findActivePacks(eq(PLAYER_ID), eq(COACH_ID), any()))
            .thenReturn(List.of(pack(UUID.randomUUID(), 999L)));
        when(bookingService.requireRequestTimePrice(COACH_ID)).thenReturn(new BigDecimal("45.00"));

        service.duplicateNextWeek(ORIGINAL_BOOKING_ID, COACH_USER_ID);

        ArgumentCaptor<Booking> captor = ArgumentCaptor.forClass(Booking.class);
        verify(bookingRepository).save(captor.capture());
        assertThat(captor.getValue().getSessionPackPurchaseId()).isNull();
        assertThat(captor.getValue().getAgreedPrice()).isEqualByComparingTo("45.00");
    }

    private void stubHappyPathUpToPack() {
        when(bookingService.getBookingOrThrow(ORIGINAL_BOOKING_ID)).thenReturn(completedBooking);
        when(coachProfileRepository.findByUserId(COACH_USER_ID)).thenReturn(Optional.of(coach));
        when(coachProfileRepository.findByIdForUpdate(COACH_ID)).thenReturn(Optional.of(coach));
        when(bookingRepository.findOverlappingBookings(any(), any(), any(), any(), any())).thenReturn(List.of());
        // Lenient: the refusal tests below stop before the save.
        lenient().when(bookingRepository.save(any())).thenAnswer(i -> i.getArgument(0));
    }

    /**
     * fix/audit3-booking-scheduling-integrity: findActivePacks lists a pack whose pause starts after today, so
     * the duplicate used to attach it even when the new start fell inside that pause.
     */
    @Test
    void duplicateNextWeek_onlyPackIsPausedAtTheNewStart_refusedPackPaused() {
        Instant originalStart = Instant.now().minus(6, ChronoUnit.DAYS);
        completedBooking.setRequestedStartTime(originalStart);
        completedBooking.setRequestedEndTime(originalStart.plus(1, ChronoUnit.HOURS));
        stubHappyPathUpToPack();
        SessionPackPurchase paused = pack(UUID.randomUUID(), PARENT_ID);
        // Starts after today (so findActivePacks lists it) and covers the duplicate's start (now + 1 day).
        paused.setPausedFrom(Instant.now().plus(12, ChronoUnit.HOURS));
        paused.setPausedUntil(Instant.now().plus(30, ChronoUnit.DAYS));
        when(sessionPackPurchaseRepository.findActivePacks(eq(PLAYER_ID), eq(COACH_ID), any()))
            .thenReturn(List.of(paused));

        assertThatThrownBy(() -> service.duplicateNextWeek(ORIGINAL_BOOKING_ID, COACH_USER_ID))
            .isInstanceOf(com.softropic.skillars.platform.payment.contract.exception.PaymentGatewayException.class)
            .hasMessageContaining("payment.packPaused");
        verify(bookingRepository, never()).save(any());
    }

    @Test
    void duplicateNextWeek_firstPackPausedAtTheNewStart_usesTheNextUsablePack() {
        Instant originalStart = Instant.now().minus(6, ChronoUnit.DAYS);
        completedBooking.setRequestedStartTime(originalStart);
        completedBooking.setRequestedEndTime(originalStart.plus(1, ChronoUnit.HOURS));
        stubHappyPathUpToPack();
        SessionPackPurchase paused = pack(UUID.randomUUID(), PARENT_ID);
        // Starts after today (so findActivePacks lists it) and covers the duplicate's start (now + 1 day).
        paused.setPausedFrom(Instant.now().plus(12, ChronoUnit.HOURS));
        paused.setPausedUntil(Instant.now().plus(30, ChronoUnit.DAYS));
        UUID usable = UUID.randomUUID();
        when(sessionPackPurchaseRepository.findActivePacks(eq(PLAYER_ID), eq(COACH_ID), any()))
            .thenReturn(List.of(paused, pack(usable, PARENT_ID)));

        service.duplicateNextWeek(ORIGINAL_BOOKING_ID, COACH_USER_ID);

        ArgumentCaptor<Booking> captor = ArgumentCaptor.forClass(Booking.class);
        verify(bookingRepository).save(captor.capture());
        assertThat(captor.getValue().getSessionPackPurchaseId()).isEqualTo(usable);
    }

    /** fix/audit3-booking-scheduling-integrity: every remaining session already claimed by open bookings. */
    @Test
    void duplicateNextWeek_packSessionsAllReserved_refusedNotSilentlyCardPriced() {
        Instant originalStart = Instant.now().minus(6, ChronoUnit.DAYS);
        completedBooking.setRequestedStartTime(originalStart);
        completedBooking.setRequestedEndTime(originalStart.plus(1, ChronoUnit.HOURS));
        stubHappyPathUpToPack();
        SessionPackPurchase full = pack(UUID.randomUUID(), PARENT_ID);
        when(sessionPackPurchaseRepository.findActivePacks(eq(PLAYER_ID), eq(COACH_ID), any()))
            .thenReturn(List.of(full));
        when(bookingService.hasUnreservedPackSession(full)).thenReturn(false);

        assertThatThrownBy(() -> service.duplicateNextWeek(ORIGINAL_BOOKING_ID, COACH_USER_ID))
            .isInstanceOf(com.softropic.skillars.platform.payment.contract.exception.PaymentGatewayException.class)
            .hasMessageContaining("payment.packSessionsReserved");
        verify(bookingRepository, never()).save(any());
        verify(bookingService, never()).requireRequestTimePrice(any());
    }

    private static SessionPackPurchase pack(UUID purchaseId, Long parentId) {
        SessionPackPurchase p = new SessionPackPurchase();
        p.setPurchaseId(purchaseId);
        p.setParentId(parentId);
        return p;
    }

    /** Deferred-49 AC2: repeat-next-week must fit the coach's CURRENT availability. */
    @Test
    void duplicateNextWeek_slotOutsideAvailabilityWindow_throwsSlotOutsideAvailability() {
        Instant originalStart = Instant.now().minus(6, ChronoUnit.DAYS);
        completedBooking.setRequestedStartTime(originalStart);
        completedBooking.setRequestedEndTime(originalStart.plus(1, ChronoUnit.HOURS));

        when(bookingService.getBookingOrThrow(ORIGINAL_BOOKING_ID)).thenReturn(completedBooking);
        when(coachProfileRepository.findByUserId(COACH_USER_ID)).thenReturn(Optional.of(coach));
        when(coachProfileRepository.findByIdForUpdate(COACH_ID)).thenReturn(Optional.of(coach));
        // skillars-deferred-164 AC2: the boolean check moved behind a void check-and-throw method
        // (requireSlotWithinAvailabilityWindow); a mocked void method is a no-op by default, so the
        // failure case must stub it to throw, not stub isSlotWithinAvailabilityWindow to return false.
        doThrow(new OperationNotAllowedException("Proposed slot is not within coach availability",
                Map.of(), BookingError.SLOT_OUTSIDE_AVAILABILITY))
            .when(bookingService).requireSlotWithinAvailabilityWindow(
                any(), any(), any(), any(), any(), any(), any());

        assertThatThrownBy(() -> service.duplicateNextWeek(ORIGINAL_BOOKING_ID, COACH_USER_ID))
            .isInstanceOf(OperationNotAllowedException.class)
            .satisfies(e -> assertThat(((OperationNotAllowedException) e).getErrorCode())
                .isEqualTo(BookingError.SLOT_OUTSIDE_AVAILABILITY));

        verify(bookingRepository, never()).save(any());
        verify(sessionPackPurchaseRepository, never()).findActivePacks(any(), any(), any());
    }

    /** Deferred-79 AC1: repeat-next-week must not land on a slot the coach has explicitly blocked out. */
    @Test
    void duplicateNextWeek_slotOverlapsActiveBlock_throwsSlotBlockedByCoach() {
        Instant originalStart = Instant.now().minus(6, ChronoUnit.DAYS);
        completedBooking.setRequestedStartTime(originalStart);
        completedBooking.setRequestedEndTime(originalStart.plus(1, ChronoUnit.HOURS));

        when(bookingService.getBookingOrThrow(ORIGINAL_BOOKING_ID)).thenReturn(completedBooking);
        when(coachProfileRepository.findByUserId(COACH_USER_ID)).thenReturn(Optional.of(coach));
        when(coachProfileRepository.findByIdForUpdate(COACH_ID)).thenReturn(Optional.of(coach));
        when(bookingService.isSlotBlocked(any(), any(), any())).thenReturn(true);

        assertThatThrownBy(() -> service.duplicateNextWeek(ORIGINAL_BOOKING_ID, COACH_USER_ID))
            .isInstanceOf(OperationNotAllowedException.class)
            .satisfies(e -> assertThat(((OperationNotAllowedException) e).getErrorCode())
                .isEqualTo(BookingError.SLOT_BLOCKED_BY_COACH));

        verify(bookingRepository, never()).findOverlappingBookings(any(), any(), any(), any(), any());
        verify(bookingRepository, never()).save(any());
        verify(sessionPackPurchaseRepository, never()).findActivePacks(any(), any(), any());
    }

    @Test
    void duplicateNextWeek_slotNotBlocked_succeeds() {
        Instant originalStart = Instant.now().minus(6, ChronoUnit.DAYS);
        completedBooking.setRequestedStartTime(originalStart);
        completedBooking.setRequestedEndTime(originalStart.plus(1, ChronoUnit.HOURS));

        when(bookingService.getBookingOrThrow(ORIGINAL_BOOKING_ID)).thenReturn(completedBooking);
        when(coachProfileRepository.findByUserId(COACH_USER_ID)).thenReturn(Optional.of(coach));
        when(coachProfileRepository.findByIdForUpdate(COACH_ID)).thenReturn(Optional.of(coach));
        when(bookingService.isSlotBlocked(any(), any(), any())).thenReturn(false);
        when(bookingRepository.findOverlappingBookings(any(), any(), any(), any(), any())).thenReturn(List.of());
        when(bookingRepository.save(any())).thenAnswer(i -> {
            Booking b = i.getArgument(0);
            b.setId(UUID.randomUUID());
            return b;
        });
        when(sessionPackPurchaseRepository.findActivePacks(any(), any(), any())).thenReturn(List.of());

        UUID duplicateId = service.duplicateNextWeek(ORIGINAL_BOOKING_ID, COACH_USER_ID);

        assertThat(duplicateId).isNotNull();
        verify(bookingRepository).save(any(Booking.class));
    }

    /** fix/booking-price-and-excused-race: a non-pack duplicate needs a price to lock; none → refused, nothing saved. */
    @Test
    void duplicateNextWeek_noPackAndCoachWithoutPricing_isRefused() {
        Instant originalStart = Instant.now().minus(6, ChronoUnit.DAYS);
        completedBooking.setRequestedStartTime(originalStart);
        completedBooking.setRequestedEndTime(originalStart.plus(1, ChronoUnit.HOURS));

        when(bookingService.getBookingOrThrow(ORIGINAL_BOOKING_ID)).thenReturn(completedBooking);
        when(coachProfileRepository.findByUserId(COACH_USER_ID)).thenReturn(Optional.of(coach));
        when(coachProfileRepository.findByIdForUpdate(COACH_ID)).thenReturn(Optional.of(coach));
        when(bookingService.isSlotBlocked(any(), any(), any())).thenReturn(false);
        when(bookingRepository.findOverlappingBookings(any(), any(), any(), any(), any())).thenReturn(List.of());
        when(sessionPackPurchaseRepository.findActivePacks(any(), any(), any())).thenReturn(List.of());
        when(bookingService.requireRequestTimePrice(COACH_ID)).thenThrow(new OperationNotAllowedException(
            "This coach has not set a session price yet", BookingError.COACH_PRICING_UNAVAILABLE));

        assertThatThrownBy(() -> service.duplicateNextWeek(ORIGINAL_BOOKING_ID, COACH_USER_ID))
            .isInstanceOf(OperationNotAllowedException.class)
            .satisfies(e -> assertThat(((OperationNotAllowedException) e).getErrorCode())
                .isEqualTo(BookingError.COACH_PRICING_UNAVAILABLE));
        verify(bookingRepository, never()).save(any());
    }

    /** Deferred-50 AC1: repeat-next-week must not double-book the coach at the duplicated slot. */
    @Test
    void duplicateNextWeek_overlapsAnotherBooking_throwsSlotUnavailable() {
        Instant originalStart = Instant.now().minus(6, ChronoUnit.DAYS);
        completedBooking.setRequestedStartTime(originalStart);
        completedBooking.setRequestedEndTime(originalStart.plus(1, ChronoUnit.HOURS));

        when(bookingService.getBookingOrThrow(ORIGINAL_BOOKING_ID)).thenReturn(completedBooking);
        when(coachProfileRepository.findByUserId(COACH_USER_ID)).thenReturn(Optional.of(coach));
        when(coachProfileRepository.findByIdForUpdate(COACH_ID)).thenReturn(Optional.of(coach));
        when(bookingRepository.findOverlappingBookings(any(), any(), any(), any(), any()))
            .thenReturn(List.of(new Booking()));

        assertThatThrownBy(() -> service.duplicateNextWeek(ORIGINAL_BOOKING_ID, COACH_USER_ID))
            .isInstanceOf(OperationNotAllowedException.class)
            .satisfies(e -> assertThat(((OperationNotAllowedException) e).getErrorCode())
                .isEqualTo(BookingError.SLOT_UNAVAILABLE));

        verify(bookingRepository).findOverlappingBookings(
            eq(COACH_ID),
            eq(originalStart.atZone(java.time.ZoneId.of("Europe/Berlin")).plusWeeks(1).toInstant()),
            eq(originalStart.plus(1, ChronoUnit.HOURS).atZone(java.time.ZoneId.of("Europe/Berlin")).plusWeeks(1).toInstant()),
            // fix/audit3-booking-scheduling-integrity: REQUESTED included — a pending request or an earlier
            // proposal for the slot blocks the duplicate (a double-click no longer proposes it twice).
            eq(BookingService.ACTIVE_SLOT_STATUSES),
            isNull());
        verify(bookingRepository, never()).save(any());
        verify(sessionPackPurchaseRepository, never()).findActivePacks(any(), any(), any());
    }

    @Test
    void duplicateNextWeek_proposedTimePast_throws() {
        // Original booking 14 days ago → +7 days = 7 days ago (still past)
        Instant originalStart = Instant.now().minus(14, ChronoUnit.DAYS);
        completedBooking.setRequestedStartTime(originalStart);
        completedBooking.setRequestedEndTime(originalStart.plus(1, ChronoUnit.HOURS));

        when(bookingService.getBookingOrThrow(ORIGINAL_BOOKING_ID)).thenReturn(completedBooking);
        when(coachProfileRepository.findByUserId(COACH_USER_ID)).thenReturn(Optional.of(coach));
        when(coachProfileRepository.findByIdForUpdate(COACH_ID)).thenReturn(Optional.of(coach));

        assertThatThrownBy(() -> service.duplicateNextWeek(ORIGINAL_BOOKING_ID, COACH_USER_ID))
            .isInstanceOf(OperationNotAllowedException.class)
            .hasMessageContaining("past");
    }
}
