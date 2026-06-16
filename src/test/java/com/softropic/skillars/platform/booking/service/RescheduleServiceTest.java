package com.softropic.skillars.platform.booking.service;

import com.softropic.skillars.infrastructure.exception.ResourceNotFoundException;
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

import java.time.Instant;
import java.time.temporal.ChronoUnit;
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
            bookingService, bookingRepository, rescheduleRepo, coachProfileRepository, userRepository, eventPublisher
        );

        confirmedBooking = new Booking();
        confirmedBooking.setParentId(PARENT_ID);
        confirmedBooking.setCoachId(COACH_ID);
        confirmedBooking.setStatus("CONFIRMED");
        confirmedBooking.setRequestedStartTime(Instant.now().plus(2, ChronoUnit.DAYS));
        confirmedBooking.setRequestedEndTime(Instant.now().plus(2, ChronoUnit.DAYS).plus(1, ChronoUnit.HOURS));
        confirmedBooking.setCanonicalTimezone("Europe/Berlin");

        coach = new CoachProfile();
        coach.setId(COACH_ID);
        coach.setUserId(COACH_USER_ID);
        coach.setDisplayName("Test Coach");
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

        assertThatThrownBy(() -> service.requestReschedule(BOOKING_ID, PARENT_ID,
            new CreateRescheduleRequest(
                Instant.now().plus(3, ChronoUnit.DAYS),
                Instant.now().plus(3, ChronoUnit.DAYS).plus(1, ChronoUnit.HOURS)
            )))
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
        when(rescheduleRepo.save(any())).thenAnswer(i -> i.getArgument(0));

        service.acceptReschedule(BOOKING_ID, RESCHEDULE_ID, COACH_USER_ID);

        assertThat(confirmedBooking.getRequestedStartTime()).isEqualTo(proposedStart);
        assertThat(confirmedBooking.getRequestedEndTime()).isEqualTo(proposedEnd);
        assertThat(pending.getStatus()).isEqualTo("ACCEPTED");

        ArgumentCaptor<RescheduleAcceptedEvent> captor = ArgumentCaptor.forClass(RescheduleAcceptedEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        assertThat(captor.getValue().getNewStartTime()).isEqualTo(proposedStart);
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
        when(rescheduleRepo.findById(RESCHEDULE_ID)).thenReturn(Optional.of(pending));
        when(rescheduleRepo.save(any())).thenAnswer(i -> i.getArgument(0));

        service.declineReschedule(BOOKING_ID, RESCHEDULE_ID, COACH_USER_ID);

        assertThat(pending.getStatus()).isEqualTo("DECLINED");
        assertThat(confirmedBooking.getRequestedStartTime()).isEqualTo(originalStart);

        ArgumentCaptor<RescheduleDeclinedEvent> captor = ArgumentCaptor.forClass(RescheduleDeclinedEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        assertThat(captor.getValue().getBookingId()).isEqualTo(BOOKING_ID);
    }
}
