package com.softropic.skillars.platform.booking.service;

import com.softropic.skillars.platform.booking.contract.DuplicateBookingProposedEvent;
import com.softropic.skillars.platform.booking.repo.Booking;
import com.softropic.skillars.platform.booking.repo.BookingRepository;
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
class BookingDuplicationServiceTest {

    @Mock private BookingService bookingService;
    @Mock private BookingRepository bookingRepository;
    @Mock private CoachProfileRepository coachProfileRepository;
    @Mock private UserRepository userRepository;
    @Mock private SessionPackService sessionPackService;
    @Mock private ApplicationEventPublisher eventPublisher;

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
            userRepository, sessionPackService, eventPublisher
        );

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
    void duplicateNextWeek_completedBooking_createsNewRequestedBookingAdvancedBy7Days() {
        when(bookingService.getBookingOrThrow(ORIGINAL_BOOKING_ID)).thenReturn(completedBooking);
        when(coachProfileRepository.findByUserId(COACH_USER_ID)).thenReturn(Optional.of(coach));
        when(sessionPackService.hasCredits(PLAYER_ID, COACH_ID)).thenReturn(true);

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

        Instant expectedStart = originalStart.plus(7, ChronoUnit.DAYS);
        assertThat(saved.getRequestedStartTime()).isEqualTo(expectedStart);
        assertThat(saved.getCoachId()).isEqualTo(COACH_ID);
        assertThat(saved.getPlayerId()).isEqualTo(PLAYER_ID);
        assertThat(saved.getParentId()).isEqualTo(PARENT_ID);

        ArgumentCaptor<DuplicateBookingProposedEvent> eventCaptor =
            ArgumentCaptor.forClass(DuplicateBookingProposedEvent.class);
        verify(eventPublisher).publishEvent(eventCaptor.capture());
        assertThat(eventCaptor.getValue().getParentEmail()).isEqualTo("parent@test.com");
        assertThat(eventCaptor.getValue().getCoachDisplayName()).isEqualTo("Test Coach");
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

    @Test
    void duplicateNextWeek_notCompletedStatus_throws() {
        completedBooking.setStatus("CONFIRMED");
        when(bookingService.getBookingOrThrow(ORIGINAL_BOOKING_ID)).thenReturn(completedBooking);
        when(coachProfileRepository.findByUserId(COACH_USER_ID)).thenReturn(Optional.of(coach));

        assertThatThrownBy(() -> service.duplicateNextWeek(ORIGINAL_BOOKING_ID, COACH_USER_ID))
            .isInstanceOf(OperationNotAllowedException.class)
            .hasMessageContaining("COMPLETED");
    }

    @Test
    void duplicateNextWeek_noCreditsAvailable_throws() {
        // Start time 6 days ago → +7 days is in the future (1 day from now)
        Instant originalStart = Instant.now().minus(6, ChronoUnit.DAYS);
        completedBooking.setRequestedStartTime(originalStart);
        completedBooking.setRequestedEndTime(originalStart.plus(1, ChronoUnit.HOURS));

        when(bookingService.getBookingOrThrow(ORIGINAL_BOOKING_ID)).thenReturn(completedBooking);
        when(coachProfileRepository.findByUserId(COACH_USER_ID)).thenReturn(Optional.of(coach));
        when(sessionPackService.hasCredits(PLAYER_ID, COACH_ID)).thenReturn(false);

        assertThatThrownBy(() -> service.duplicateNextWeek(ORIGINAL_BOOKING_ID, COACH_USER_ID))
            .isInstanceOf(OperationNotAllowedException.class)
            .hasMessageContaining("credits");
    }

    @Test
    void duplicateNextWeek_proposedTimePast_throws() {
        // Original booking 14 days ago → +7 days = 7 days ago (still past)
        Instant originalStart = Instant.now().minus(14, ChronoUnit.DAYS);
        completedBooking.setRequestedStartTime(originalStart);
        completedBooking.setRequestedEndTime(originalStart.plus(1, ChronoUnit.HOURS));

        when(bookingService.getBookingOrThrow(ORIGINAL_BOOKING_ID)).thenReturn(completedBooking);
        when(coachProfileRepository.findByUserId(COACH_USER_ID)).thenReturn(Optional.of(coach));

        assertThatThrownBy(() -> service.duplicateNextWeek(ORIGINAL_BOOKING_ID, COACH_USER_ID))
            .isInstanceOf(OperationNotAllowedException.class)
            .hasMessageContaining("past");
    }
}
