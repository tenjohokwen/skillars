package com.softropic.skillars.platform.booking.service;

import com.softropic.skillars.infrastructure.exception.ResourceNotFoundException;
import com.softropic.skillars.infrastructure.persistence.PessimisticLockRetryer;
import com.softropic.skillars.platform.booking.contract.BookingError;
import com.softropic.skillars.platform.booking.contract.CreateRescheduleRequest;
import com.softropic.skillars.platform.booking.contract.RescheduleAcceptedEvent;
import com.softropic.skillars.platform.booking.contract.RescheduleDeclinedByParentEvent;
import com.softropic.skillars.platform.booking.contract.RescheduleDeclinedEvent;
import com.softropic.skillars.platform.booking.contract.RescheduleRequestedByCoachEvent;
import com.softropic.skillars.platform.booking.contract.RescheduleRequestedEvent;
import com.softropic.skillars.platform.booking.repo.Booking;
import com.softropic.skillars.platform.booking.repo.BookingRepository;
import com.softropic.skillars.platform.booking.repo.BookingRescheduleRequest;
import com.softropic.skillars.platform.booking.repo.BookingRescheduleRequestRepository;
import com.softropic.skillars.platform.marketplace.repo.CoachAvailabilityWindow;
import com.softropic.skillars.platform.marketplace.repo.CoachAvailabilityWindowRepository;
import com.softropic.skillars.platform.marketplace.repo.CoachProfile;
import com.softropic.skillars.platform.marketplace.repo.CoachProfileRepository;
import com.softropic.skillars.platform.security.contract.exception.OperationNotAllowedException;
import com.softropic.skillars.platform.security.repo.User;
import com.softropic.skillars.platform.security.repo.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.OptimisticLockingFailureException;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RescheduleServiceTest {

    @Mock private BookingService bookingService;
    @Mock private BookingRepository bookingRepository;
    @Mock private BookingRescheduleRequestRepository rescheduleRepo;
    @Mock private CoachProfileRepository coachProfileRepository;
    @Mock private UserRepository userRepository;
    @Mock private ApplicationEventPublisher eventPublisher;
    @Mock private jakarta.persistence.EntityManager entityManager;
    @Mock private CoachAvailabilityWindowRepository coachAvailabilityWindowRepository;
    @Mock private PessimisticLockRetryer lockRetryer;

    private RescheduleService service;

    private static final UUID BOOKING_ID   = UUID.randomUUID();
    private static final UUID COACH_ID     = UUID.randomUUID();
    private static final UUID RESCHEDULE_ID = UUID.randomUUID();
    private static final Long PARENT_ID    = 300L;
    private static final Long COACH_USER_ID = 100L;

    private Booking confirmedBooking;
    private CoachProfile coach;

    @BeforeEach
    void setUp() {
        service = new RescheduleService(
            bookingService, bookingRepository, rescheduleRepo, coachProfileRepository, userRepository,
            eventPublisher, entityManager, coachAvailabilityWindowRepository, lockRetryer
        );
        lenient().when(lockRetryer.withBoundedRetry(any()))
            .thenAnswer(inv -> ((java.util.function.Supplier<?>) inv.getArgument(0)).get());

        confirmedBooking = new Booking();
        confirmedBooking.setParentId(PARENT_ID);
        confirmedBooking.setCoachId(COACH_ID);
        confirmedBooking.setStatus("CONFIRMED");
        // Both bounds derived from ONE instant. Two separate Instant.now() calls made this booking
        // 1 hour plus a few microseconds long, which UAT.2 AC3's exact same-duration rule would
        // reject for every proposal of exactly one hour.
        Instant bookingStart = Instant.now().plus(2, ChronoUnit.DAYS);
        confirmedBooking.setRequestedStartTime(bookingStart);
        confirmedBooking.setRequestedEndTime(bookingStart.plus(1, ChronoUnit.HOURS));
        confirmedBooking.setCanonicalTimezone("Europe/Berlin");

        coach = new CoachProfile();
        coach.setId(COACH_ID);
        coach.setUserId(COACH_USER_ID);
        coach.setDisplayName("Test Coach");
        coach.setStatus(com.softropic.skillars.platform.marketplace.contract.CoachProfileStatus.ACTIVE);

        // Deferred-78 AC1: validateRescheduleProposal (called from requestReschedule/
        // requestRescheduleAsCoach) now locks the coach row before its windows fetch. Lenient
        // because tests that throw earlier (invalid status/time/duration, ownership) never reach it.
        lenient().when(coachProfileRepository.findByIdForUpdate(COACH_ID)).thenReturn(Optional.of(coach));
    }

    @Test
    void requestReschedule_parentOwnsBooking_confirmedStatus_createsRequest() {
        when(bookingService.getBookingOrThrow(BOOKING_ID)).thenReturn(confirmedBooking);
        when(bookingService.isSlotWithinAvailabilityWindow(any(), any(), any(), any())).thenReturn(true);
        when(rescheduleRepo.findFirstByBookingIdAndStatusOrderByCreatedAtDesc(BOOKING_ID, "PENDING"))
            .thenReturn(Optional.empty());
        when(coachProfileRepository.findById(COACH_ID)).thenReturn(Optional.of(coach));
        when(rescheduleRepo.save(any())).thenAnswer(i -> i.getArgument(0));

        Instant proposedStart = Instant.now().plus(3, ChronoUnit.DAYS);
        Instant proposedEnd = proposedStart.plus(1, ChronoUnit.HOURS);
        service.requestReschedule(BOOKING_ID, PARENT_ID, new CreateRescheduleRequest(proposedStart, proposedEnd, null));

        // Deferred-50 AC3: verify the actual proposed times were passed, not (for example) the
        // booking's original requestedStartTime/requestedEndTime — an argument-swap regression.
        verify(bookingService).isSlotWithinAvailabilityWindow(eq(proposedStart), eq(proposedEnd), any(), any());
        verify(rescheduleRepo).save(any(BookingRescheduleRequest.class));
        ArgumentCaptor<RescheduleRequestedEvent> captor = ArgumentCaptor.forClass(RescheduleRequestedEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        assertThat(captor.getValue().getBookingId()).isEqualTo(BOOKING_ID);
        assertThat(captor.getValue().getProposedStartTime()).isEqualTo(proposedStart);
    }

    // ---- UAT.2 AC3: a reschedule is a MOVE, not a resize ----

    /**
     * The regression the same-as-original rule exists to prevent. Duration was unconstrained until
     * this story, so bookings already in any UAT database have arbitrary lengths — and a parent
     * moving one at its own length must still succeed. This test FAILS if the check is ever switched
     * to resolve against the coach's currently-configured session length (which would be 60).
     */
    @Test
    void requestReschedule_legacyThreeHourBooking_movesAtItsOwnLength() {
        Instant legacyStart = Instant.now().plus(2, ChronoUnit.DAYS);
        confirmedBooking.setRequestedStartTime(legacyStart);
        confirmedBooking.setRequestedEndTime(legacyStart.plus(3, ChronoUnit.HOURS));

        when(bookingService.getBookingOrThrow(BOOKING_ID)).thenReturn(confirmedBooking);
        when(bookingService.isSlotWithinAvailabilityWindow(any(), any(), any(), any())).thenReturn(true);
        when(rescheduleRepo.findFirstByBookingIdAndStatusOrderByCreatedAtDesc(BOOKING_ID, "PENDING"))
            .thenReturn(Optional.empty());
        when(coachProfileRepository.findById(COACH_ID)).thenReturn(Optional.of(coach));
        when(rescheduleRepo.save(any())).thenAnswer(i -> i.getArgument(0));

        Instant proposedStart = Instant.now().plus(4, ChronoUnit.DAYS);
        service.requestReschedule(BOOKING_ID, PARENT_ID,
            new CreateRescheduleRequest(proposedStart, proposedStart.plus(3, ChronoUnit.HOURS), null));

        // Not just "save was called": the 3-hour length must round-trip into what is persisted.
        // A save(any()) assertion would still pass if the proposal were silently coerced to the
        // coach's configured length, which is the exact regression this test exists to prevent.
        ArgumentCaptor<BookingRescheduleRequest> captor =
            ArgumentCaptor.forClass(BookingRescheduleRequest.class);
        verify(rescheduleRepo).save(captor.capture());
        BookingRescheduleRequest saved = captor.getValue();
        assertThat(saved.getProposedStartTime()).isEqualTo(proposedStart);
        assertThat(saved.getProposedEndTime()).isEqualTo(proposedStart.plus(3, ChronoUnit.HOURS));
        assertThat(Duration.between(saved.getProposedStartTime(), saved.getProposedEndTime()))
            .isEqualTo(Duration.ofHours(3));
    }

    /** The escalation hole: without this check a 3-hour session is reschedulable into 8 hours. */
    @Test
    void requestReschedule_inflatingTheDuration_isRejected() {
        Instant legacyStart = Instant.now().plus(2, ChronoUnit.DAYS);
        confirmedBooking.setRequestedStartTime(legacyStart);
        confirmedBooking.setRequestedEndTime(legacyStart.plus(3, ChronoUnit.HOURS));

        when(bookingService.getBookingOrThrow(BOOKING_ID)).thenReturn(confirmedBooking);

        Instant proposedStart = Instant.now().plus(4, ChronoUnit.DAYS);
        assertThatThrownBy(() -> service.requestReschedule(BOOKING_ID, PARENT_ID,
            new CreateRescheduleRequest(proposedStart, proposedStart.plus(8, ChronoUnit.HOURS), null)))
            .isInstanceOf(OperationNotAllowedException.class)
            .hasMessageContaining("original length");

        verify(rescheduleRepo, never()).save(any());
    }

    /** Shrinking is a resize too, and equally rejected. */
    @Test
    void requestReschedule_shrinkingTheDuration_isRejected() {
        when(bookingService.getBookingOrThrow(BOOKING_ID)).thenReturn(confirmedBooking);

        Instant proposedStart = Instant.now().plus(4, ChronoUnit.DAYS);
        assertThatThrownBy(() -> service.requestReschedule(BOOKING_ID, PARENT_ID,
            new CreateRescheduleRequest(proposedStart, proposedStart.plus(30, ChronoUnit.MINUTES), null)))
            .isInstanceOf(OperationNotAllowedException.class)
            .hasMessageContaining("original length");
    }

    @Test
    void requestReschedule_wrongParent_throws403() {
        when(bookingService.getBookingOrThrow(BOOKING_ID)).thenReturn(confirmedBooking);
        Long wrongParent = 999L;

        assertThatThrownBy(() -> service.requestReschedule(BOOKING_ID, wrongParent,
            new CreateRescheduleRequest(
                Instant.now().plus(1, ChronoUnit.DAYS),
                Instant.now().plus(1, ChronoUnit.DAYS).plus(1, ChronoUnit.HOURS)
            , null)))
            .isInstanceOf(OperationNotAllowedException.class);

        verify(rescheduleRepo, never()).save(any());
    }

    @Test
    void requestReschedule_invalidStatus_throws() {
        confirmedBooking.setStatus("REQUESTED");
        when(bookingService.getBookingOrThrow(BOOKING_ID)).thenReturn(confirmedBooking);

        assertThatThrownBy(() -> service.requestReschedule(BOOKING_ID, PARENT_ID,
            new CreateRescheduleRequest(
                Instant.now().plus(1, ChronoUnit.DAYS),
                Instant.now().plus(1, ChronoUnit.DAYS).plus(1, ChronoUnit.HOURS)
            , null)))
            .isInstanceOf(OperationNotAllowedException.class);
    }

    @Test
    void requestReschedule_pastProposedTime_throws() {
        when(bookingService.getBookingOrThrow(BOOKING_ID)).thenReturn(confirmedBooking);

        assertThatThrownBy(() -> service.requestReschedule(BOOKING_ID, PARENT_ID,
            new CreateRescheduleRequest(
                Instant.now().minus(1, ChronoUnit.HOURS),
                Instant.now().plus(1, ChronoUnit.HOURS)
            , null)))
            .isInstanceOf(OperationNotAllowedException.class);
    }

    // ---- Deferred-49 AC1: current-availability enforcement ----

    @Test
    void requestReschedule_slotOutsideAvailabilityWindow_throwsSlotOutsideAvailability() {
        when(bookingService.getBookingOrThrow(BOOKING_ID)).thenReturn(confirmedBooking);
        when(bookingService.isSlotWithinAvailabilityWindow(any(), any(), any(), any())).thenReturn(false);

        Instant proposedStart = Instant.now().plus(3, ChronoUnit.DAYS);
        assertThatThrownBy(() -> service.requestReschedule(BOOKING_ID, PARENT_ID,
            new CreateRescheduleRequest(proposedStart, proposedStart.plus(1, ChronoUnit.HOURS), null)))
            .isInstanceOf(OperationNotAllowedException.class)
            .satisfies(e -> assertThat(((OperationNotAllowedException) e).getErrorCode())
                .isEqualTo(BookingError.SLOT_OUTSIDE_AVAILABILITY));

        verify(rescheduleRepo, never()).save(any());
    }

    // ---- Deferred-79 AC1: CoachAvailabilityBlock enforcement ----

    @Test
    void requestReschedule_slotOverlapsActiveBlock_throwsSlotBlockedByCoach() {
        when(bookingService.getBookingOrThrow(BOOKING_ID)).thenReturn(confirmedBooking);
        when(bookingService.isSlotWithinAvailabilityWindow(any(), any(), any(), any())).thenReturn(true);
        when(bookingService.isSlotBlocked(any(), any(), any())).thenReturn(true);

        Instant proposedStart = Instant.now().plus(3, ChronoUnit.DAYS);
        assertThatThrownBy(() -> service.requestReschedule(BOOKING_ID, PARENT_ID,
            new CreateRescheduleRequest(proposedStart, proposedStart.plus(1, ChronoUnit.HOURS), null)))
            .isInstanceOf(OperationNotAllowedException.class)
            .satisfies(e -> assertThat(((OperationNotAllowedException) e).getErrorCode())
                .isEqualTo(BookingError.SLOT_BLOCKED_BY_COACH));

        verify(rescheduleRepo, never()).save(any());
    }

    @Test
    void requestReschedule_slotOutsideAnyBlock_createsRequest() {
        when(bookingService.getBookingOrThrow(BOOKING_ID)).thenReturn(confirmedBooking);
        when(bookingService.isSlotWithinAvailabilityWindow(any(), any(), any(), any())).thenReturn(true);
        when(bookingService.isSlotBlocked(any(), any(), any())).thenReturn(false);
        when(rescheduleRepo.findFirstByBookingIdAndStatusOrderByCreatedAtDesc(BOOKING_ID, "PENDING"))
            .thenReturn(Optional.empty());
        when(coachProfileRepository.findById(COACH_ID)).thenReturn(Optional.of(coach));
        when(rescheduleRepo.save(any())).thenAnswer(i -> i.getArgument(0));

        Instant proposedStart = Instant.now().plus(3, ChronoUnit.DAYS);
        service.requestReschedule(BOOKING_ID, PARENT_ID,
            new CreateRescheduleRequest(proposedStart, proposedStart.plus(1, ChronoUnit.HOURS), null));

        verify(rescheduleRepo).save(any(BookingRescheduleRequest.class));
    }

    @Test
    void requestReschedule_slotWithinAvailabilityWindow_createsRequest() {
        when(bookingService.getBookingOrThrow(BOOKING_ID)).thenReturn(confirmedBooking);
        when(bookingService.isSlotWithinAvailabilityWindow(any(), any(), any(), any())).thenReturn(true);
        when(rescheduleRepo.findFirstByBookingIdAndStatusOrderByCreatedAtDesc(BOOKING_ID, "PENDING"))
            .thenReturn(Optional.empty());
        when(coachProfileRepository.findById(COACH_ID)).thenReturn(Optional.of(coach));
        when(rescheduleRepo.save(any())).thenAnswer(i -> i.getArgument(0));

        Instant proposedStart = Instant.now().plus(3, ChronoUnit.DAYS);
        service.requestReschedule(BOOKING_ID, PARENT_ID,
            new CreateRescheduleRequest(proposedStart, proposedStart.plus(1, ChronoUnit.HOURS), null));

        verify(rescheduleRepo).save(any(BookingRescheduleRequest.class));
    }

    // ---- Deferred-78 AC2: availabilitySignature staleness guard ----

    @Test
    void requestReschedule_nullAvailabilitySignature_succeedsUnchanged() {
        when(bookingService.getBookingOrThrow(BOOKING_ID)).thenReturn(confirmedBooking);
        when(bookingService.isSlotWithinAvailabilityWindow(any(), any(), any(), any())).thenReturn(true);
        when(rescheduleRepo.findFirstByBookingIdAndStatusOrderByCreatedAtDesc(BOOKING_ID, "PENDING"))
            .thenReturn(Optional.empty());
        when(coachProfileRepository.findById(COACH_ID)).thenReturn(Optional.of(coach));
        when(rescheduleRepo.save(any())).thenAnswer(i -> i.getArgument(0));

        Instant proposedStart = Instant.now().plus(3, ChronoUnit.DAYS);
        service.requestReschedule(BOOKING_ID, PARENT_ID,
            new CreateRescheduleRequest(proposedStart, proposedStart.plus(1, ChronoUnit.HOURS), null));

        verify(rescheduleRepo).save(any(BookingRescheduleRequest.class));
    }

    @Test
    void requestReschedule_matchingAvailabilitySignature_succeeds() {
        when(bookingService.getBookingOrThrow(BOOKING_ID)).thenReturn(confirmedBooking);
        when(bookingService.isSlotWithinAvailabilityWindow(any(), any(), any(), any())).thenReturn(true);
        when(rescheduleRepo.findFirstByBookingIdAndStatusOrderByCreatedAtDesc(BOOKING_ID, "PENDING"))
            .thenReturn(Optional.empty());
        when(coachProfileRepository.findById(COACH_ID)).thenReturn(Optional.of(coach));
        when(rescheduleRepo.save(any())).thenAnswer(i -> i.getArgument(0));

        CoachAvailabilityWindow window = new CoachAvailabilityWindow();
        window.setId(UUID.randomUUID());
        window.setCoachId(COACH_ID);
        window.setDayOfWeek((short) 1);
        window.setStartTime(java.time.LocalTime.of(0, 0));
        window.setEndTime(java.time.LocalTime.of(23, 59));
        window.setCanonicalTimezone("UTC");
        when(coachAvailabilityWindowRepository.findByCoachIdOrderByDayOfWeekAscStartTimeAscIdAsc(COACH_ID))
            .thenReturn(List.of(window));

        // originalDuration is confirmedBooking's own length (1 hour, set in setUp()) — the signature
        // must be computed against that, not SessionDurationResolver.
        String currentSignature =
            AvailabilityService.computeAvailabilitySignature(List.of(window), Duration.ofHours(1));

        Instant proposedStart = Instant.now().plus(3, ChronoUnit.DAYS);
        service.requestReschedule(BOOKING_ID, PARENT_ID,
            new CreateRescheduleRequest(proposedStart, proposedStart.plus(1, ChronoUnit.HOURS), currentSignature));

        verify(rescheduleRepo).save(any(BookingRescheduleRequest.class));
    }

    @Test
    void requestReschedule_staleAvailabilitySignature_throwsAvailabilityChanged() {
        when(bookingService.getBookingOrThrow(BOOKING_ID)).thenReturn(confirmedBooking);
        when(coachAvailabilityWindowRepository.findByCoachIdOrderByDayOfWeekAscStartTimeAscIdAsc(COACH_ID))
            .thenReturn(List.of());

        Instant proposedStart = Instant.now().plus(3, ChronoUnit.DAYS);
        assertThatThrownBy(() -> service.requestReschedule(BOOKING_ID, PARENT_ID,
            new CreateRescheduleRequest(proposedStart, proposedStart.plus(1, ChronoUnit.HOURS),
                "a-stale-signature-that-cannot-match")))
            .isInstanceOf(OperationNotAllowedException.class)
            .satisfies(e -> assertThat(((OperationNotAllowedException) e).getErrorCode())
                .isEqualTo(BookingError.AVAILABILITY_CHANGED));

        verify(rescheduleRepo, never()).save(any());
    }

    @Test
    void requestReschedule_pendingAlreadyExists_throws() {
        when(bookingService.getBookingOrThrow(BOOKING_ID)).thenReturn(confirmedBooking);
        when(bookingService.isSlotWithinAvailabilityWindow(any(), any(), any(), any())).thenReturn(true);
        BookingRescheduleRequest existing = new BookingRescheduleRequest();
        when(rescheduleRepo.findFirstByBookingIdAndStatusOrderByCreatedAtDesc(BOOKING_ID, "PENDING"))
            .thenReturn(Optional.of(existing));

        // Both bounds from ONE instant: two Instant.now() calls made the proposal 1 hour plus a few
        // microseconds, which UAT.2 AC3's same-duration check now rejects BEFORE the
        // pending-request check this test is about.
        Instant proposedStart = Instant.now().plus(3, ChronoUnit.DAYS);
        assertThatThrownBy(() -> service.requestReschedule(BOOKING_ID, PARENT_ID,
            new CreateRescheduleRequest(proposedStart, proposedStart.plus(1, ChronoUnit.HOURS), null)))
            .isInstanceOf(OperationNotAllowedException.class)
            .hasMessageContaining("pending reschedule");
    }

    @Test
    void acceptReschedule_coachOwnsBooking_updatesTimesAndStatus() {
        when(bookingService.getBookingOrThrow(BOOKING_ID)).thenReturn(confirmedBooking);
        when(bookingService.isSlotWithinAvailabilityWindow(any(), any(), any(), any())).thenReturn(true);
        when(coachProfileRepository.findByUserId(COACH_USER_ID)).thenReturn(Optional.of(coach));

        Instant proposedStart = Instant.now().plus(5, ChronoUnit.DAYS);
        Instant proposedEnd = proposedStart.plus(1, ChronoUnit.HOURS);
        BookingRescheduleRequest pending = new BookingRescheduleRequest();
        pending.setBookingId(BOOKING_ID);
        pending.setStatus("PENDING");
        pending.setProposedStartTime(proposedStart);
        pending.setProposedEndTime(proposedEnd);
        when(rescheduleRepo.findById(RESCHEDULE_ID)).thenReturn(Optional.of(pending));
        // Deferred-15 AC3: the authoritative read is the locked one.
        when(rescheduleRepo.findByIdForUpdate(RESCHEDULE_ID)).thenReturn(Optional.of(pending));
        when(rescheduleRepo.save(any())).thenAnswer(i -> i.getArgument(0));
        // Deferred-14 AC4: accept now locks the coach and checks the PROPOSED window for overlap.
        when(coachProfileRepository.findByIdForUpdate(coach.getId())).thenReturn(Optional.of(coach));
        when(bookingRepository.findOverlappingBookings(
            coach.getId(), proposedStart, proposedEnd,
            BookingService.ACTIVE_SLOT_STATUSES_EXCLUDING_REQUESTED, BOOKING_ID))
            .thenReturn(List.of());

        service.acceptReschedule(BOOKING_ID, RESCHEDULE_ID, COACH_USER_ID);

        // Deferred-50 AC3: verify the actual proposed times were passed, not (for example) the
        // booking's original requestedStartTime/requestedEndTime — an argument-swap regression.
        verify(bookingService).isSlotWithinAvailabilityWindow(eq(proposedStart), eq(proposedEnd), any(), any());
        assertThat(confirmedBooking.getRequestedStartTime()).isEqualTo(proposedStart);
        assertThat(confirmedBooking.getRequestedEndTime()).isEqualTo(proposedEnd);
        assertThat(pending.getStatus()).isEqualTo("ACCEPTED");

        ArgumentCaptor<RescheduleAcceptedEvent> captor = ArgumentCaptor.forClass(RescheduleAcceptedEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        assertThat(captor.getValue().getNewStartTime()).isEqualTo(proposedStart);
    }

    @Test
    void acceptReschedule_concurrentModification_throwsRetryableException() {
        when(bookingService.getBookingOrThrow(BOOKING_ID)).thenReturn(confirmedBooking);
        when(bookingService.isSlotWithinAvailabilityWindow(any(), any(), any(), any())).thenReturn(true);
        when(coachProfileRepository.findByUserId(COACH_USER_ID)).thenReturn(Optional.of(coach));

        Instant proposedStart = Instant.now().plus(5, ChronoUnit.DAYS);
        Instant proposedEnd = proposedStart.plus(1, ChronoUnit.HOURS);
        BookingRescheduleRequest pending = new BookingRescheduleRequest();
        pending.setBookingId(BOOKING_ID);
        pending.setStatus("PENDING");
        pending.setProposedStartTime(proposedStart);
        pending.setProposedEndTime(proposedEnd);
        when(rescheduleRepo.findById(RESCHEDULE_ID)).thenReturn(Optional.of(pending));
        when(rescheduleRepo.findByIdForUpdate(RESCHEDULE_ID)).thenReturn(Optional.of(pending));
        when(coachProfileRepository.findByIdForUpdate(coach.getId())).thenReturn(Optional.of(coach));
        when(bookingRepository.findOverlappingBookings(
            coach.getId(), proposedStart, proposedEnd,
            BookingService.ACTIVE_SLOT_STATUSES_EXCLUDING_REQUESTED, BOOKING_ID))
            .thenReturn(List.of());
        when(bookingRepository.save(any(Booking.class))).thenThrow(new OptimisticLockingFailureException("test"));

        assertThatThrownBy(() -> service.acceptReschedule(BOOKING_ID, RESCHEDULE_ID, COACH_USER_ID))
            .isInstanceOf(OperationNotAllowedException.class)
            .hasCauseInstanceOf(OptimisticLockingFailureException.class)
            .satisfies(e -> assertThat(((OperationNotAllowedException) e).getErrorCode())
                .isEqualTo(BookingError.CONCURRENT_MODIFICATION));
    }

    @Test
    void acceptReschedule_proposedSlotOverlapsAnotherBooking_throwsSlotUnavailable() {
        when(bookingService.getBookingOrThrow(BOOKING_ID)).thenReturn(confirmedBooking);
        when(bookingService.isSlotWithinAvailabilityWindow(any(), any(), any(), any())).thenReturn(true);
        when(coachProfileRepository.findByUserId(COACH_USER_ID)).thenReturn(Optional.of(coach));

        Instant originalStart = confirmedBooking.getRequestedStartTime();
        Instant proposedStart = Instant.now().plus(5, ChronoUnit.DAYS);
        Instant proposedEnd = proposedStart.plus(1, ChronoUnit.HOURS);
        BookingRescheduleRequest pending = new BookingRescheduleRequest();
        pending.setBookingId(BOOKING_ID);
        pending.setStatus("PENDING");
        pending.setProposedStartTime(proposedStart);
        pending.setProposedEndTime(proposedEnd);
        when(rescheduleRepo.findById(RESCHEDULE_ID)).thenReturn(Optional.of(pending));
        when(rescheduleRepo.findByIdForUpdate(RESCHEDULE_ID)).thenReturn(Optional.of(pending));
        when(coachProfileRepository.findByIdForUpdate(coach.getId())).thenReturn(Optional.of(coach));
        when(bookingRepository.findOverlappingBookings(
            coach.getId(), proposedStart, proposedEnd,
            BookingService.ACTIVE_SLOT_STATUSES_EXCLUDING_REQUESTED, BOOKING_ID))
            .thenReturn(List.of(new Booking()));

        assertThatThrownBy(() -> service.acceptReschedule(BOOKING_ID, RESCHEDULE_ID, COACH_USER_ID))
            .isInstanceOf(OperationNotAllowedException.class)
            .satisfies(e -> assertThat(((OperationNotAllowedException) e).getErrorCode())
                .isEqualTo(BookingError.SLOT_UNAVAILABLE));

        assertThat(confirmedBooking.getRequestedStartTime())
            .as("booking times must be left untouched when the proposed slot is taken")
            .isEqualTo(originalStart);
        assertThat(pending.getStatus()).isEqualTo("PENDING");
        verify(bookingRepository, never()).save(any());
        verify(eventPublisher, never()).publishEvent(any(RescheduleAcceptedEvent.class));
    }

    /**
     * Deferred-49 AC4: the coach narrowed their availability after the parent's proposal but before
     * accepting it — acceptReschedule re-checks availability at the finalization point, not just
     * requestReschedule at proposal time. Mirrors acceptReschedule_suspendedCoach_throwsCoachUnavailable's
     * shape: the new check sits between the suspension check and the overlap check, so overlap is
     * never reached here and must not be stubbed (unreachable stubs fail strict Mockito verification).
     */
    @Test
    void acceptReschedule_slotNoLongerWithinAvailabilityWindow_throwsSlotOutsideAvailability() {
        when(bookingService.getBookingOrThrow(BOOKING_ID)).thenReturn(confirmedBooking);
        when(coachProfileRepository.findByUserId(COACH_USER_ID)).thenReturn(Optional.of(coach));

        Instant originalStart = confirmedBooking.getRequestedStartTime();
        Instant proposedStart = Instant.now().plus(5, ChronoUnit.DAYS);
        Instant proposedEnd = proposedStart.plus(1, ChronoUnit.HOURS);
        BookingRescheduleRequest pending = new BookingRescheduleRequest();
        pending.setBookingId(BOOKING_ID);
        pending.setStatus("PENDING");
        pending.setProposedStartTime(proposedStart);
        pending.setProposedEndTime(proposedEnd);
        when(rescheduleRepo.findById(RESCHEDULE_ID)).thenReturn(Optional.of(pending));
        when(rescheduleRepo.findByIdForUpdate(RESCHEDULE_ID)).thenReturn(Optional.of(pending));
        when(coachProfileRepository.findByIdForUpdate(coach.getId())).thenReturn(Optional.of(coach));
        when(bookingService.isSlotWithinAvailabilityWindow(any(), any(), any(), any())).thenReturn(false);

        assertThatThrownBy(() -> service.acceptReschedule(BOOKING_ID, RESCHEDULE_ID, COACH_USER_ID))
            .isInstanceOf(OperationNotAllowedException.class)
            .satisfies(e -> assertThat(((OperationNotAllowedException) e).getErrorCode())
                .isEqualTo(BookingError.SLOT_OUTSIDE_AVAILABILITY));

        assertThat(confirmedBooking.getRequestedStartTime())
            .as("booking times must be left untouched when availability no longer permits the slot")
            .isEqualTo(originalStart);
        assertThat(pending.getStatus()).isEqualTo("PENDING");
        verify(bookingRepository, never()).findOverlappingBookings(any(), any(), any(), any(), any());
        verify(bookingRepository, never()).save(any());
        verify(eventPublisher, never()).publishEvent(any(RescheduleAcceptedEvent.class));
    }

    /**
     * Deferred-79 AC1: accept-time re-check, a fresh independent lookup — mirrors the availability
     * re-check test above. The block check sits between the window check and the overlap check, so
     * the overlap query is unreachable here and must not be stubbed (unreachable stubs fail strict
     * Mockito verification).
     */
    @Test
    void acceptReschedule_slotOverlapsActiveBlock_throwsSlotBlockedByCoach() {
        when(bookingService.getBookingOrThrow(BOOKING_ID)).thenReturn(confirmedBooking);
        when(coachProfileRepository.findByUserId(COACH_USER_ID)).thenReturn(Optional.of(coach));

        Instant originalStart = confirmedBooking.getRequestedStartTime();
        Instant proposedStart = Instant.now().plus(5, ChronoUnit.DAYS);
        Instant proposedEnd = proposedStart.plus(1, ChronoUnit.HOURS);
        BookingRescheduleRequest pending = new BookingRescheduleRequest();
        pending.setBookingId(BOOKING_ID);
        pending.setStatus("PENDING");
        pending.setProposedStartTime(proposedStart);
        pending.setProposedEndTime(proposedEnd);
        when(rescheduleRepo.findById(RESCHEDULE_ID)).thenReturn(Optional.of(pending));
        when(rescheduleRepo.findByIdForUpdate(RESCHEDULE_ID)).thenReturn(Optional.of(pending));
        when(coachProfileRepository.findByIdForUpdate(coach.getId())).thenReturn(Optional.of(coach));
        when(bookingService.isSlotWithinAvailabilityWindow(any(), any(), any(), any())).thenReturn(true);
        when(bookingService.isSlotBlocked(any(), any(), any())).thenReturn(true);

        assertThatThrownBy(() -> service.acceptReschedule(BOOKING_ID, RESCHEDULE_ID, COACH_USER_ID))
            .isInstanceOf(OperationNotAllowedException.class)
            .satisfies(e -> assertThat(((OperationNotAllowedException) e).getErrorCode())
                .isEqualTo(BookingError.SLOT_BLOCKED_BY_COACH));

        assertThat(confirmedBooking.getRequestedStartTime())
            .as("booking times must be left untouched when the coach has blocked out the slot")
            .isEqualTo(originalStart);
        assertThat(pending.getStatus()).isEqualTo("PENDING");
        verify(bookingRepository, never()).findOverlappingBookings(any(), any(), any(), any(), any());
        verify(bookingRepository, never()).save(any());
        verify(eventPublisher, never()).publishEvent(any(RescheduleAcceptedEvent.class));
    }

    @Test
    void acceptReschedule_slotNotBlocked_succeeds() {
        when(bookingService.getBookingOrThrow(BOOKING_ID)).thenReturn(confirmedBooking);
        when(coachProfileRepository.findByUserId(COACH_USER_ID)).thenReturn(Optional.of(coach));

        Instant proposedStart = Instant.now().plus(5, ChronoUnit.DAYS);
        Instant proposedEnd = proposedStart.plus(1, ChronoUnit.HOURS);
        BookingRescheduleRequest pending = new BookingRescheduleRequest();
        pending.setBookingId(BOOKING_ID);
        pending.setStatus("PENDING");
        pending.setProposedStartTime(proposedStart);
        pending.setProposedEndTime(proposedEnd);
        when(rescheduleRepo.findById(RESCHEDULE_ID)).thenReturn(Optional.of(pending));
        when(rescheduleRepo.findByIdForUpdate(RESCHEDULE_ID)).thenReturn(Optional.of(pending));
        when(coachProfileRepository.findByIdForUpdate(coach.getId())).thenReturn(Optional.of(coach));
        when(bookingService.isSlotWithinAvailabilityWindow(any(), any(), any(), any())).thenReturn(true);
        when(bookingService.isSlotBlocked(any(), any(), any())).thenReturn(false);
        when(bookingRepository.findOverlappingBookings(any(), any(), any(), any(), any())).thenReturn(List.of());
        when(rescheduleRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(bookingRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.acceptReschedule(BOOKING_ID, RESCHEDULE_ID, COACH_USER_ID);

        assertThat(pending.getStatus()).isEqualTo("ACCEPTED");
        verify(bookingRepository).save(any(Booking.class));
        verify(eventPublisher).publishEvent(any(RescheduleAcceptedEvent.class));
    }

    @Test
    void acceptReschedule_wrongCoach_throws() {
        Booking booking = new Booking();
        booking.setCoachId(UUID.randomUUID());
        when(bookingService.getBookingOrThrow(BOOKING_ID)).thenReturn(booking);
        when(coachProfileRepository.findByUserId(COACH_USER_ID)).thenReturn(Optional.of(coach));

        assertThatThrownBy(() -> service.acceptReschedule(BOOKING_ID, RESCHEDULE_ID, COACH_USER_ID))
            .isInstanceOf(OperationNotAllowedException.class);
    }

    @Test
    void acceptReschedule_rescheduleAlreadyDeclined_throws() {
        when(bookingService.getBookingOrThrow(BOOKING_ID)).thenReturn(confirmedBooking);
        when(coachProfileRepository.findByUserId(COACH_USER_ID)).thenReturn(Optional.of(coach));

        BookingRescheduleRequest declined = new BookingRescheduleRequest();
        declined.setBookingId(BOOKING_ID);
        declined.setStatus("DECLINED");
        when(rescheduleRepo.findById(RESCHEDULE_ID)).thenReturn(Optional.of(declined));

        assertThatThrownBy(() -> service.acceptReschedule(BOOKING_ID, RESCHEDULE_ID, COACH_USER_ID))
            .isInstanceOf(OperationNotAllowedException.class);
    }

    @Test
    void declineReschedule_coachOwnsBooking_setsDeclined() {
        Instant originalStart = confirmedBooking.getRequestedStartTime();
        when(bookingService.getBookingOrThrow(BOOKING_ID)).thenReturn(confirmedBooking);
        when(coachProfileRepository.findByUserId(COACH_USER_ID)).thenReturn(Optional.of(coach));

        BookingRescheduleRequest pending = new BookingRescheduleRequest();
        pending.setBookingId(BOOKING_ID);
        pending.setStatus("PENDING");
        // Deferred-15 AC3: decline takes the same reschedule-row lock accept does.
        when(rescheduleRepo.findByIdForUpdate(RESCHEDULE_ID)).thenReturn(Optional.of(pending));
        when(rescheduleRepo.save(any())).thenAnswer(i -> i.getArgument(0));

        service.declineReschedule(BOOKING_ID, RESCHEDULE_ID, COACH_USER_ID);

        assertThat(pending.getStatus()).isEqualTo("DECLINED");
        assertThat(confirmedBooking.getRequestedStartTime()).isEqualTo(originalStart);

        ArgumentCaptor<RescheduleDeclinedEvent> captor = ArgumentCaptor.forClass(RescheduleDeclinedEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        assertThat(captor.getValue().getBookingId()).isEqualTo(BOOKING_ID);
    }

    /**
     * Deferred-15 AC3. The unlocked read at the top of acceptReschedule still says PENDING — that is
     * exactly the stale view a caller holds while parked on the lock. Only the locked re-read sees
     * the decline that committed in the meantime, and it is what must stop the overwrite.
     */
    @Test
    void acceptReschedule_declinedWhileWaitingForTheLock_throwsAndLeavesBookingUntouched() {
        Instant originalStart = confirmedBooking.getRequestedStartTime();
        when(bookingService.getBookingOrThrow(BOOKING_ID)).thenReturn(confirmedBooking);
        when(coachProfileRepository.findByUserId(COACH_USER_ID)).thenReturn(Optional.of(coach));

        BookingRescheduleRequest staleView = new BookingRescheduleRequest();
        staleView.setBookingId(BOOKING_ID);
        staleView.setStatus("PENDING");
        staleView.setProposedStartTime(Instant.now().plus(5, ChronoUnit.DAYS));
        staleView.setProposedEndTime(Instant.now().plus(5, ChronoUnit.DAYS).plus(1, ChronoUnit.HOURS));
        BookingRescheduleRequest lockedView = new BookingRescheduleRequest();
        lockedView.setBookingId(BOOKING_ID);
        lockedView.setStatus("DECLINED");

        when(rescheduleRepo.findById(RESCHEDULE_ID)).thenReturn(Optional.of(staleView));
        when(rescheduleRepo.findByIdForUpdate(RESCHEDULE_ID)).thenReturn(Optional.of(lockedView));

        assertThatThrownBy(() -> service.acceptReschedule(BOOKING_ID, RESCHEDULE_ID, COACH_USER_ID))
            .isInstanceOf(OperationNotAllowedException.class);

        assertThat(confirmedBooking.getRequestedStartTime()).isEqualTo(originalStart);
        assertThat(lockedView.getStatus()).isEqualTo("DECLINED");
        verify(bookingRepository, never()).save(any());
        verify(rescheduleRepo, never()).save(any());
    }

    /** Deferred-15 AC4: a suspended coach cannot accept a reschedule. */
    @Test
    void acceptReschedule_suspendedCoach_throwsCoachUnavailable() {
        when(bookingService.getBookingOrThrow(BOOKING_ID)).thenReturn(confirmedBooking);
        when(coachProfileRepository.findByUserId(COACH_USER_ID)).thenReturn(Optional.of(coach));

        BookingRescheduleRequest pending = new BookingRescheduleRequest();
        pending.setBookingId(BOOKING_ID);
        pending.setStatus("PENDING");
        pending.setProposedStartTime(Instant.now().plus(5, ChronoUnit.DAYS));
        pending.setProposedEndTime(Instant.now().plus(5, ChronoUnit.DAYS).plus(1, ChronoUnit.HOURS));
        when(rescheduleRepo.findById(RESCHEDULE_ID)).thenReturn(Optional.of(pending));
        when(rescheduleRepo.findByIdForUpdate(RESCHEDULE_ID)).thenReturn(Optional.of(pending));

        CoachProfile suspended = new CoachProfile();
        suspended.setId(COACH_ID);
        suspended.setUserId(COACH_USER_ID);
        suspended.setStatus(com.softropic.skillars.platform.marketplace.contract.CoachProfileStatus.SUSPENDED);
        when(coachProfileRepository.findByIdForUpdate(COACH_ID)).thenReturn(Optional.of(suspended));

        assertThatThrownBy(() -> service.acceptReschedule(BOOKING_ID, RESCHEDULE_ID, COACH_USER_ID))
            .isInstanceOf(OperationNotAllowedException.class)
            .satisfies(e -> assertThat(((OperationNotAllowedException) e).getErrorCode())
                .isEqualTo(BookingError.COACH_UNAVAILABLE));

        verify(rescheduleRepo, never()).save(any());
    }

    /**
     * skillars-deferred-69 AC8: call-order assertion pinning the documented lock order (reschedule
     * request row before coach profile row, RescheduleService.java's own comment ahead of the locked
     * re-reads) — not a concurrency/deadlock reproduction, since nothing in this codebase actually
     * contends among the three methods the original ledger item named (confirmed during story
     * research: BookingDuplicationService.duplicateNextWeek and CoachProfileService.saveStep4 each
     * take exactly one lock, the coach-profile row only — there is nothing to deadlock against
     * today). This guards the convention so a future editor adding a second lock elsewhere is caught
     * if they get the order wrong.
     */
    @Test
    void acceptReschedule_lockAcquisitionOrder_rescheduleRequestBeforeCoachProfile() {
        when(bookingService.getBookingOrThrow(BOOKING_ID)).thenReturn(confirmedBooking);
        when(bookingService.isSlotWithinAvailabilityWindow(any(), any(), any(), any())).thenReturn(true);
        when(coachProfileRepository.findByUserId(COACH_USER_ID)).thenReturn(Optional.of(coach));

        Instant proposedStart = Instant.now().plus(5, ChronoUnit.DAYS);
        Instant proposedEnd = proposedStart.plus(1, ChronoUnit.HOURS);
        BookingRescheduleRequest pending = new BookingRescheduleRequest();
        pending.setBookingId(BOOKING_ID);
        pending.setStatus("PENDING");
        pending.setProposedStartTime(proposedStart);
        pending.setProposedEndTime(proposedEnd);
        when(rescheduleRepo.findById(RESCHEDULE_ID)).thenReturn(Optional.of(pending));
        when(rescheduleRepo.findByIdForUpdate(RESCHEDULE_ID)).thenReturn(Optional.of(pending));
        when(rescheduleRepo.save(any())).thenAnswer(i -> i.getArgument(0));
        when(coachProfileRepository.findByIdForUpdate(coach.getId())).thenReturn(Optional.of(coach));
        when(bookingRepository.findOverlappingBookings(
            coach.getId(), proposedStart, proposedEnd,
            BookingService.ACTIVE_SLOT_STATUSES_EXCLUDING_REQUESTED, BOOKING_ID))
            .thenReturn(List.of());

        service.acceptReschedule(BOOKING_ID, RESCHEDULE_ID, COACH_USER_ID);

        InOrder order = inOrder(rescheduleRepo, coachProfileRepository);
        order.verify(rescheduleRepo).findByIdForUpdate(RESCHEDULE_ID);
        order.verify(coachProfileRepository).findByIdForUpdate(coach.getId());
    }

    // ---- skillars-deferred-69 AC5: coach-initiated reschedule ----

    @Test
    void requestRescheduleAsCoach_coachOwnsBooking_createsRequestAndPublishesEvent() {
        when(bookingService.getBookingOrThrow(BOOKING_ID)).thenReturn(confirmedBooking);
        when(coachProfileRepository.findByUserId(COACH_USER_ID)).thenReturn(Optional.of(coach));
        when(bookingService.isSlotWithinAvailabilityWindow(any(), any(), any(), any())).thenReturn(true);
        when(rescheduleRepo.findFirstByBookingIdAndStatusOrderByCreatedAtDesc(BOOKING_ID, "PENDING"))
            .thenReturn(Optional.empty());
        when(rescheduleRepo.save(any())).thenAnswer(i -> i.getArgument(0));

        Instant proposedStart = Instant.now().plus(3, ChronoUnit.DAYS);
        Instant proposedEnd = proposedStart.plus(1, ChronoUnit.HOURS);
        service.requestRescheduleAsCoach(BOOKING_ID, COACH_USER_ID, new CreateRescheduleRequest(proposedStart, proposedEnd, null));

        ArgumentCaptor<BookingRescheduleRequest> savedCaptor = ArgumentCaptor.forClass(BookingRescheduleRequest.class);
        verify(rescheduleRepo).save(savedCaptor.capture());
        assertThat(savedCaptor.getValue().getProposedBy()).isEqualTo("COACH");

        ArgumentCaptor<RescheduleRequestedByCoachEvent> captor = ArgumentCaptor.forClass(RescheduleRequestedByCoachEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        assertThat(captor.getValue().getBookingId()).isEqualTo(BOOKING_ID);
        assertThat(captor.getValue().getProposedStartTime()).isEqualTo(proposedStart);
        assertThat(captor.getValue().getCoachDisplayName()).isEqualTo("Test Coach");
    }

    @Test
    void requestRescheduleAsCoach_wrongCoach_throws() {
        Booking booking = new Booking();
        booking.setCoachId(UUID.randomUUID());
        when(bookingService.getBookingOrThrow(BOOKING_ID)).thenReturn(booking);
        when(coachProfileRepository.findByUserId(COACH_USER_ID)).thenReturn(Optional.of(coach));

        assertThatThrownBy(() -> service.requestRescheduleAsCoach(BOOKING_ID, COACH_USER_ID,
            new CreateRescheduleRequest(Instant.now().plus(1, ChronoUnit.DAYS), Instant.now().plus(1, ChronoUnit.DAYS).plus(1, ChronoUnit.HOURS), null)))
            .isInstanceOf(OperationNotAllowedException.class);

        verify(rescheduleRepo, never()).save(any());
    }

    /** A coach cannot accept their own coach-initiated proposal through the coach-only accept endpoint. */
    @Test
    void acceptReschedule_coachProposedOwnProposal_throwsCannotRespondToOwnProposal() {
        when(bookingService.getBookingOrThrow(BOOKING_ID)).thenReturn(confirmedBooking);
        when(coachProfileRepository.findByUserId(COACH_USER_ID)).thenReturn(Optional.of(coach));

        BookingRescheduleRequest pending = new BookingRescheduleRequest();
        pending.setBookingId(BOOKING_ID);
        pending.setStatus("PENDING");
        pending.setProposedBy("COACH");
        when(rescheduleRepo.findById(RESCHEDULE_ID)).thenReturn(Optional.of(pending));

        assertThatThrownBy(() -> service.acceptReschedule(BOOKING_ID, RESCHEDULE_ID, COACH_USER_ID))
            .isInstanceOf(OperationNotAllowedException.class)
            .satisfies(e -> assertThat(((OperationNotAllowedException) e).getErrorCode())
                .isEqualTo(BookingError.CANNOT_RESPOND_TO_OWN_PROPOSAL));

        verify(rescheduleRepo, never()).save(any());
    }

    /** A coach cannot decline their own coach-initiated proposal through the coach-only decline endpoint. */
    @Test
    void declineReschedule_coachProposedOwnProposal_throwsCannotRespondToOwnProposal() {
        when(bookingService.getBookingOrThrow(BOOKING_ID)).thenReturn(confirmedBooking);
        when(coachProfileRepository.findByUserId(COACH_USER_ID)).thenReturn(Optional.of(coach));

        BookingRescheduleRequest pending = new BookingRescheduleRequest();
        pending.setBookingId(BOOKING_ID);
        pending.setStatus("PENDING");
        pending.setProposedBy("COACH");
        when(rescheduleRepo.findByIdForUpdate(RESCHEDULE_ID)).thenReturn(Optional.of(pending));

        assertThatThrownBy(() -> service.declineReschedule(BOOKING_ID, RESCHEDULE_ID, COACH_USER_ID))
            .isInstanceOf(OperationNotAllowedException.class)
            .satisfies(e -> assertThat(((OperationNotAllowedException) e).getErrorCode())
                .isEqualTo(BookingError.CANNOT_RESPOND_TO_OWN_PROPOSAL));

        assertThat(pending.getStatus()).isEqualTo("PENDING");
        verify(rescheduleRepo, never()).save(any());
    }

    @Test
    void acceptRescheduleAsParent_parentOwnsBooking_updatesTimesAndStatus() {
        when(bookingService.getBookingOrThrow(BOOKING_ID)).thenReturn(confirmedBooking);
        when(coachProfileRepository.findById(COACH_ID)).thenReturn(Optional.of(coach));
        when(bookingService.isSlotWithinAvailabilityWindow(any(), any(), any(), any())).thenReturn(true);

        Instant proposedStart = Instant.now().plus(5, ChronoUnit.DAYS);
        Instant proposedEnd = proposedStart.plus(1, ChronoUnit.HOURS);
        BookingRescheduleRequest pending = new BookingRescheduleRequest();
        pending.setBookingId(BOOKING_ID);
        pending.setStatus("PENDING");
        pending.setProposedBy("COACH");
        pending.setProposedStartTime(proposedStart);
        pending.setProposedEndTime(proposedEnd);
        when(rescheduleRepo.findById(RESCHEDULE_ID)).thenReturn(Optional.of(pending));
        when(rescheduleRepo.findByIdForUpdate(RESCHEDULE_ID)).thenReturn(Optional.of(pending));
        when(rescheduleRepo.save(any())).thenAnswer(i -> i.getArgument(0));
        when(coachProfileRepository.findByIdForUpdate(coach.getId())).thenReturn(Optional.of(coach));
        when(bookingRepository.findOverlappingBookings(
            coach.getId(), proposedStart, proposedEnd,
            BookingService.ACTIVE_SLOT_STATUSES_EXCLUDING_REQUESTED, BOOKING_ID))
            .thenReturn(List.of());

        service.acceptRescheduleAsParent(BOOKING_ID, RESCHEDULE_ID, PARENT_ID);

        assertThat(confirmedBooking.getRequestedStartTime()).isEqualTo(proposedStart);
        assertThat(confirmedBooking.getRequestedEndTime()).isEqualTo(proposedEnd);
        assertThat(pending.getStatus()).isEqualTo("ACCEPTED");

        ArgumentCaptor<RescheduleAcceptedEvent> captor = ArgumentCaptor.forClass(RescheduleAcceptedEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        assertThat(captor.getValue().getNewStartTime()).isEqualTo(proposedStart);
    }

    @Test
    void acceptRescheduleAsParent_wrongParent_throws() {
        when(bookingService.getBookingOrThrow(BOOKING_ID)).thenReturn(confirmedBooking);

        assertThatThrownBy(() -> service.acceptRescheduleAsParent(BOOKING_ID, RESCHEDULE_ID, 999L))
            .isInstanceOf(OperationNotAllowedException.class);

        verify(rescheduleRepo, never()).save(any());
    }

    /** A parent cannot accept their own parent-initiated proposal through the new parent-accept endpoint. */
    @Test
    void acceptRescheduleAsParent_parentProposedOwnProposal_throwsCannotRespondToOwnProposal() {
        when(bookingService.getBookingOrThrow(BOOKING_ID)).thenReturn(confirmedBooking);
        when(coachProfileRepository.findById(COACH_ID)).thenReturn(Optional.of(coach));

        BookingRescheduleRequest pending = new BookingRescheduleRequest();
        pending.setBookingId(BOOKING_ID);
        pending.setStatus("PENDING");
        pending.setProposedBy("PARENT");
        when(rescheduleRepo.findById(RESCHEDULE_ID)).thenReturn(Optional.of(pending));

        assertThatThrownBy(() -> service.acceptRescheduleAsParent(BOOKING_ID, RESCHEDULE_ID, PARENT_ID))
            .isInstanceOf(OperationNotAllowedException.class)
            .satisfies(e -> assertThat(((OperationNotAllowedException) e).getErrorCode())
                .isEqualTo(BookingError.CANNOT_RESPOND_TO_OWN_PROPOSAL));

        verify(rescheduleRepo, never()).save(any());
    }

    @Test
    void declineRescheduleAsParent_parentOwnsBooking_setsDeclined() {
        Instant originalStart = confirmedBooking.getRequestedStartTime();
        when(bookingService.getBookingOrThrow(BOOKING_ID)).thenReturn(confirmedBooking);
        when(coachProfileRepository.findById(COACH_ID)).thenReturn(Optional.of(coach));

        BookingRescheduleRequest pending = new BookingRescheduleRequest();
        pending.setBookingId(BOOKING_ID);
        pending.setStatus("PENDING");
        pending.setProposedBy("COACH");
        when(rescheduleRepo.findByIdForUpdate(RESCHEDULE_ID)).thenReturn(Optional.of(pending));
        when(rescheduleRepo.save(any())).thenAnswer(i -> i.getArgument(0));

        service.declineRescheduleAsParent(BOOKING_ID, RESCHEDULE_ID, PARENT_ID);

        assertThat(pending.getStatus()).isEqualTo("DECLINED");
        assertThat(confirmedBooking.getRequestedStartTime()).isEqualTo(originalStart);

        ArgumentCaptor<RescheduleDeclinedByParentEvent> captor = ArgumentCaptor.forClass(RescheduleDeclinedByParentEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        assertThat(captor.getValue().getBookingId()).isEqualTo(BOOKING_ID);
    }

    /** A parent cannot decline their own parent-initiated proposal through the new parent-decline endpoint. */
    @Test
    void declineRescheduleAsParent_parentProposedOwnProposal_throwsCannotRespondToOwnProposal() {
        when(bookingService.getBookingOrThrow(BOOKING_ID)).thenReturn(confirmedBooking);
        when(coachProfileRepository.findById(COACH_ID)).thenReturn(Optional.of(coach));

        BookingRescheduleRequest pending = new BookingRescheduleRequest();
        pending.setBookingId(BOOKING_ID);
        pending.setStatus("PENDING");
        pending.setProposedBy("PARENT");
        when(rescheduleRepo.findByIdForUpdate(RESCHEDULE_ID)).thenReturn(Optional.of(pending));

        assertThatThrownBy(() -> service.declineRescheduleAsParent(BOOKING_ID, RESCHEDULE_ID, PARENT_ID))
            .isInstanceOf(OperationNotAllowedException.class)
            .satisfies(e -> assertThat(((OperationNotAllowedException) e).getErrorCode())
                .isEqualTo(BookingError.CANNOT_RESPOND_TO_OWN_PROPOSAL));

        assertThat(pending.getStatus()).isEqualTo("PENDING");
        verify(rescheduleRepo, never()).save(any());
    }
}
