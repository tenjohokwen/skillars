package com.softropic.skillars.platform.booking.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.softropic.skillars.platform.booking.contract.ActorRole;
import com.softropic.skillars.platform.booking.contract.BookingEvent;
import com.softropic.skillars.platform.booking.contract.BookingStatus;
import com.softropic.skillars.platform.booking.contract.TransitionContext;
import com.softropic.skillars.platform.booking.repo.Booking;
import com.softropic.skillars.platform.booking.repo.SessionCompletionDataRepository;
import com.softropic.skillars.platform.marketplace.repo.CoachProfile;
import com.softropic.skillars.platform.marketplace.repo.CoachProfileRepository;
import com.softropic.skillars.platform.security.contract.exception.OperationNotAllowedException;
import com.softropic.skillars.platform.security.repo.PlayerProfileRepository;
import com.softropic.skillars.platform.security.repo.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.OptimisticLockingFailureException;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class SessionPauseResumeServiceTest {

    @Mock private BookingService bookingService;
    @Mock private SessionPackService sessionPackService;
    @Mock private SessionCompletionDataRepository completionDataRepository;
    @Mock private CoachProfileRepository coachProfileRepository;
    @Mock private UserRepository userRepository;
    @Mock private PlayerProfileRepository playerProfileRepository;
    @Mock private ApplicationEventPublisher eventPublisher;

    private BookingCompletionService service;

    private static final UUID BOOKING_ID    = UUID.randomUUID();
    private static final UUID COACH_ID      = UUID.randomUUID();
    private static final Long COACH_USER_ID = 100L;
    private static final Long PLAYER_ID     = 200L;
    private static final Long PARENT_ID     = 300L;

    private CoachProfile coach;
    private Booking booking;

    @BeforeEach
    void setUp() {
        service = new BookingCompletionService(
            bookingService, sessionPackService, completionDataRepository,
            coachProfileRepository, userRepository, playerProfileRepository,
            eventPublisher, new ObjectMapper()
        );

        coach = new CoachProfile();
        coach.setId(COACH_ID);
        coach.setUserId(COACH_USER_ID);
        coach.setDisplayName("Test Coach");

        booking = new Booking();
        booking.setCoachId(COACH_ID);
        booking.setPlayerId(PLAYER_ID);
        booking.setParentId(PARENT_ID);
        booking.setRequestedStartTime(Instant.now());
        booking.setCanonicalTimezone("Europe/Berlin");
        booking.setStatus(BookingStatus.IN_PROGRESS.name());

        lenient().when(coachProfileRepository.findByUserId(COACH_USER_ID)).thenReturn(Optional.of(coach));
        lenient().when(bookingService.getBookingOrThrow(BOOKING_ID)).thenReturn(booking);
    }

    @Test
    void pauseSession_inProgressBooking_firesPauseTransitionAsCoach() {
        service.pauseSession(BOOKING_ID, COACH_USER_ID);

        ArgumentCaptor<TransitionContext> ctxCaptor = ArgumentCaptor.forClass(TransitionContext.class);
        verify(bookingService).transition(eq(BOOKING_ID), eq(BookingEvent.PAUSE), ctxCaptor.capture());
        assertThat(ctxCaptor.getValue().actorRole()).isEqualTo(ActorRole.COACH);
        assertThat(ctxCaptor.getValue().actorUserId()).isEqualTo(COACH_USER_ID);
    }

    @Test
    void resumeSession_pausedBooking_firesResumeTransitionAsCoach() {
        booking.setStatus(BookingStatus.PAUSED.name());

        service.resumeSession(BOOKING_ID, COACH_USER_ID);

        ArgumentCaptor<TransitionContext> ctxCaptor = ArgumentCaptor.forClass(TransitionContext.class);
        verify(bookingService).transition(eq(BOOKING_ID), eq(BookingEvent.RESUME), ctxCaptor.capture());
        assertThat(ctxCaptor.getValue().actorRole()).isEqualTo(ActorRole.COACH);
    }

    @Test
    void pauseAndResumeMultipleCycles_eachFiresCorrectTransition() {
        service.pauseSession(BOOKING_ID, COACH_USER_ID);

        booking.setStatus(BookingStatus.PAUSED.name());
        service.resumeSession(BOOKING_ID, COACH_USER_ID);

        booking.setStatus(BookingStatus.IN_PROGRESS.name());
        service.pauseSession(BOOKING_ID, COACH_USER_ID);

        booking.setStatus(BookingStatus.PAUSED.name());
        service.resumeSession(BOOKING_ID, COACH_USER_ID);

        verify(bookingService, times(2)).transition(eq(BOOKING_ID), eq(BookingEvent.PAUSE), any());
        verify(bookingService, times(2)).transition(eq(BOOKING_ID), eq(BookingEvent.RESUME), any());
    }

    @Test
    void endSession_fromPausedStatus_firesCompletePendingTransition() {
        booking.setStatus(BookingStatus.PAUSED.name());

        service.endSession(BOOKING_ID, COACH_USER_ID);

        verify(bookingService).transition(eq(BOOKING_ID), eq(BookingEvent.COMPLETE_PENDING), any(TransitionContext.class));
    }

    @Test
    void pauseSession_bookingNotInProgress_throwsOperationNotAllowedException() {
        booking.setStatus(BookingStatus.PAUSED.name());

        assertThatThrownBy(() -> service.pauseSession(BOOKING_ID, COACH_USER_ID))
            .isInstanceOf(OperationNotAllowedException.class);
    }

    @Test
    void resumeSession_bookingNotPaused_throwsOperationNotAllowedException() {
        booking.setStatus(BookingStatus.IN_PROGRESS.name());

        assertThatThrownBy(() -> service.resumeSession(BOOKING_ID, COACH_USER_ID))
            .isInstanceOf(OperationNotAllowedException.class);
    }

    @Test
    void pauseSession_wrongCoach_throwsOperationNotAllowedException() {
        booking.setCoachId(UUID.randomUUID()); // different coach

        assertThatThrownBy(() -> service.pauseSession(BOOKING_ID, COACH_USER_ID))
            .isInstanceOf(OperationNotAllowedException.class);
    }

    @Test
    void pauseSession_optimisticLockFailure_throwsOperationNotAllowedException() {
        doThrow(new OptimisticLockingFailureException("concurrent update"))
            .when(bookingService).transition(eq(BOOKING_ID), eq(BookingEvent.PAUSE), any());

        assertThatThrownBy(() -> service.pauseSession(BOOKING_ID, COACH_USER_ID))
            .isInstanceOf(OperationNotAllowedException.class)
            .hasMessageContaining("concurrently");
    }
}
