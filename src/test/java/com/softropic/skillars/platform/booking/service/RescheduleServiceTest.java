package com.softropic.skillars.platform.booking.service;

import com.softropic.skillars.infrastructure.exception.ResourceNotFoundException;
import com.softropic.skillars.platform.booking.contract.BookingError;
import com.softropic.skillars.platform.booking.contract.CreateRescheduleRequest;
import com.softropic.skillars.platform.booking.contract.RescheduleAcceptedEvent;
import com.softropic.skillars.platform.booking.contract.RescheduleDeclinedEvent;
import com.softropic.skillars.platform.booking.contract.RescheduleRequestedEvent;
import com.softropic.skillars.platform.booking.repo.Booking;
import com.softropic.skillars.platform.booking.repo.BookingRepository;
import com.softropic.skillars.platform.booking.repo.BookingRescheduleRequest;
import com.softropic.skillars.platform.booking.repo.BookingRescheduleRequestRepository;
import com.softropic.skillars.platform.marketplace.repo.CoachProfile;
import com.softropic.skillars.platform.marketplace.repo.CoachProfileRepository;
import com.softropic.skillars.platform.security.contract.exception.OperationNotAllowedException;
import com.softropic.skillars.platform.security.repo.User;
import com.softropic.skillars.platform.security.repo.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

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
            eventPublisher, entityManager
        );

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
    }

    @Test
    void requestReschedule_parentOwnsBooking_confirmedStatus_createsRequest() {
        when(bookingService.getBookingOrThrow(BOOKING_ID)).thenReturn(confirmedBooking);
        when(rescheduleRepo.findFirstByBookingIdAndStatusOrderByCreatedAtDesc(BOOKING_ID, "PENDING"))
            .thenReturn(Optional.empty());
        when(coachProfileRepository.findById(COACH_ID)).thenReturn(Optional.of(coach));
        when(rescheduleRepo.save(any())).thenAnswer(i -> i.getArgument(0));

        Instant proposedStart = Instant.now().plus(3, ChronoUnit.DAYS);
        Instant proposedEnd = proposedStart.plus(1, ChronoUnit.HOURS);
        service.requestReschedule(BOOKING_ID, PARENT_ID, new CreateRescheduleRequest(proposedStart, proposedEnd));

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
        when(rescheduleRepo.findFirstByBookingIdAndStatusOrderByCreatedAtDesc(BOOKING_ID, "PENDING"))
            .thenReturn(Optional.empty());
        when(coachProfileRepository.findById(COACH_ID)).thenReturn(Optional.of(coach));
        when(rescheduleRepo.save(any())).thenAnswer(i -> i.getArgument(0));

        Instant proposedStart = Instant.now().plus(4, ChronoUnit.DAYS);
        service.requestReschedule(BOOKING_ID, PARENT_ID,
            new CreateRescheduleRequest(proposedStart, proposedStart.plus(3, ChronoUnit.HOURS)));

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
            new CreateRescheduleRequest(proposedStart, proposedStart.plus(8, ChronoUnit.HOURS))))
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
            new CreateRescheduleRequest(proposedStart, proposedStart.plus(30, ChronoUnit.MINUTES))))
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
            )))
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
            )))
            .isInstanceOf(OperationNotAllowedException.class);
    }

    @Test
    void requestReschedule_pastProposedTime_throws() {
        when(bookingService.getBookingOrThrow(BOOKING_ID)).thenReturn(confirmedBooking);

        assertThatThrownBy(() -> service.requestReschedule(BOOKING_ID, PARENT_ID,
            new CreateRescheduleRequest(
                Instant.now().minus(1, ChronoUnit.HOURS),
                Instant.now().plus(1, ChronoUnit.HOURS)
            )))
            .isInstanceOf(OperationNotAllowedException.class);
    }

    @Test
    void requestReschedule_pendingAlreadyExists_throws() {
        when(bookingService.getBookingOrThrow(BOOKING_ID)).thenReturn(confirmedBooking);
        BookingRescheduleRequest existing = new BookingRescheduleRequest();
        when(rescheduleRepo.findFirstByBookingIdAndStatusOrderByCreatedAtDesc(BOOKING_ID, "PENDING"))
            .thenReturn(Optional.of(existing));

        // Both bounds from ONE instant: two Instant.now() calls made the proposal 1 hour plus a few
        // microseconds, which UAT.2 AC3's same-duration check now rejects BEFORE the
        // pending-request check this test is about.
        Instant proposedStart = Instant.now().plus(3, ChronoUnit.DAYS);
        assertThatThrownBy(() -> service.requestReschedule(BOOKING_ID, PARENT_ID,
            new CreateRescheduleRequest(proposedStart, proposedStart.plus(1, ChronoUnit.HOURS))))
            .isInstanceOf(OperationNotAllowedException.class)
            .hasMessageContaining("pending reschedule");
    }

    @Test
    void acceptReschedule_coachOwnsBooking_updatesTimesAndStatus() {
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

        assertThat(confirmedBooking.getRequestedStartTime()).isEqualTo(proposedStart);
        assertThat(confirmedBooking.getRequestedEndTime()).isEqualTo(proposedEnd);
        assertThat(pending.getStatus()).isEqualTo("ACCEPTED");

        ArgumentCaptor<RescheduleAcceptedEvent> captor = ArgumentCaptor.forClass(RescheduleAcceptedEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        assertThat(captor.getValue().getNewStartTime()).isEqualTo(proposedStart);
    }

    @Test
    void acceptReschedule_proposedSlotOverlapsAnotherBooking_throwsSlotUnavailable() {
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
}
