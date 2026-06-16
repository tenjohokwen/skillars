package com.softropic.skillars.platform.booking.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.softropic.skillars.infrastructure.exception.ResourceNotFoundException;
import com.softropic.skillars.platform.booking.contract.BookingCompletedEvent;
import com.softropic.skillars.platform.booking.contract.BookingEvent;
import com.softropic.skillars.platform.booking.contract.BookingStateTransitionException;
import com.softropic.skillars.platform.booking.contract.BookingStatus;
import com.softropic.skillars.platform.booking.contract.QuickCompleteConfirmationRequiredEvent;
import com.softropic.skillars.platform.booking.contract.TransitionContext;
import com.softropic.skillars.platform.booking.contract.WrapUpRequest;
import com.softropic.skillars.platform.booking.repo.Booking;
import com.softropic.skillars.platform.booking.repo.SessionCompletionData;
import com.softropic.skillars.platform.booking.repo.SessionCompletionDataRepository;
import com.softropic.skillars.platform.marketplace.repo.CoachProfile;
import com.softropic.skillars.platform.marketplace.repo.CoachProfileRepository;
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
import org.springframework.context.ApplicationEventPublisher;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.mockito.Mockito.lenient;

@ExtendWith(MockitoExtension.class)
class BookingCompletionServiceTest {

    @Mock private BookingService bookingService;
    @Mock private SessionPackService sessionPackService;
    @Mock private SessionCompletionDataRepository completionDataRepository;
    @Mock private CoachProfileRepository coachProfileRepository;
    @Mock private UserRepository userRepository;
    @Mock private PlayerProfileRepository playerProfileRepository;
    @Mock private ApplicationEventPublisher eventPublisher;

    private BookingCompletionService service;

    private static final UUID BOOKING_ID   = UUID.randomUUID();
    private static final UUID COACH_ID     = UUID.randomUUID();
    private static final Long COACH_USER_ID = 100L;
    private static final Long PLAYER_ID    = 200L;
    private static final Long PARENT_ID    = 300L;

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
        booking.setStatus(BookingStatus.COMPLETED_PENDING_CONFIRMATION.name());

        lenient().when(coachProfileRepository.findByUserId(COACH_USER_ID)).thenReturn(Optional.of(coach));
        lenient().when(bookingService.getBookingOrThrow(BOOKING_ID)).thenReturn(booking);
    }

    @Test
    void submitWrapUp_liveMode_completesBookingAndDeductsCredit() {
        when(completionDataRepository.save(any())).thenAnswer(i -> i.getArgument(0));
        WrapUpRequest req = new WrapUpRequest(true, 4, 3, 5, null, List.of(), "LIVE");

        service.submitWrapUp(BOOKING_ID, COACH_USER_ID, req);

        verify(bookingService).transition(eq(BOOKING_ID), eq(BookingEvent.QUICK_COMPLETE), any(TransitionContext.class));
        verify(sessionPackService).deductCredit(PLAYER_ID, COACH_ID);

        ArgumentCaptor<BookingCompletedEvent> captor = ArgumentCaptor.forClass(BookingCompletedEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        BookingCompletedEvent event = captor.getValue();
        assertThat(event.getBookingId()).isEqualTo(BOOKING_ID);
        assertThat(event.isPlayerAttended()).isTrue();
    }

    @Test
    void submitWrapUp_quickMode_doesNotTransitionStatusAndPublishesConfirmationEvent() {
        when(completionDataRepository.save(any())).thenAnswer(i -> i.getArgument(0));
        WrapUpRequest req = new WrapUpRequest(true, 3, 4, 4, "Good session", List.of(), "QUICK");

        User parent = new User();
        parent.setEmail("parent@test.com");
        PlayerProfile player = new PlayerProfile();
        player.setName("Test Player");
        when(userRepository.findById(PARENT_ID)).thenReturn(Optional.of(parent));
        when(playerProfileRepository.findById(PLAYER_ID)).thenReturn(Optional.of(player));

        service.submitWrapUp(BOOKING_ID, COACH_USER_ID, req);

        verify(bookingService, never()).transition(any(), eq(BookingEvent.QUICK_COMPLETE), any());
        verify(sessionPackService, never()).deductCredit(any(), any());

        ArgumentCaptor<QuickCompleteConfirmationRequiredEvent> captor =
            ArgumentCaptor.forClass(QuickCompleteConfirmationRequiredEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        QuickCompleteConfirmationRequiredEvent event = captor.getValue();
        assertThat(event.getBookingId()).isEqualTo(BOOKING_ID);
        assertThat(event.getParentEmail()).isEqualTo("parent@test.com");
        assertThat(event.getPlayerName()).isEqualTo("Test Player");
    }

    @Test
    void confirmCompletion_validParent_firesCompleteTransitionAndDeductsCredit() {
        booking.setStatus(BookingStatus.COMPLETED_PENDING_CONFIRMATION.name());

        SessionCompletionData scd = new SessionCompletionData();
        scd.setPlayerAttended(true);
        when(completionDataRepository.findByBookingId(BOOKING_ID)).thenReturn(Optional.of(scd));

        service.confirmCompletion(BOOKING_ID, PARENT_ID);

        verify(bookingService).transition(eq(BOOKING_ID), eq(BookingEvent.COMPLETE), any(TransitionContext.class));
        verify(sessionPackService).deductCredit(PLAYER_ID, COACH_ID);

        ArgumentCaptor<BookingCompletedEvent> captor = ArgumentCaptor.forClass(BookingCompletedEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        assertThat(captor.getValue().getBookingId()).isEqualTo(BOOKING_ID);
    }

    @Test
    void startSession_bookingNotUpcoming_throwsException() {
        booking.setStatus(BookingStatus.IN_PROGRESS.name());

        assertThatThrownBy(() -> service.startSession(BOOKING_ID, COACH_USER_ID))
            .isInstanceOf(OperationNotAllowedException.class);

        verify(bookingService, never()).transition(any(), eq(BookingEvent.START), any());
    }

    @Test
    void startSession_coachDoesNotOwnBooking_throwsException() {
        booking.setCoachId(UUID.randomUUID()); // different coach
        booking.setStatus(BookingStatus.UPCOMING.name());

        assertThatThrownBy(() -> service.startSession(BOOKING_ID, COACH_USER_ID))
            .isInstanceOf(OperationNotAllowedException.class);
    }

    @Test
    void initiateQuickComplete_upcomingBooking_firesCompletePendingTransition() {
        booking.setStatus(BookingStatus.UPCOMING.name());

        service.initiateQuickComplete(BOOKING_ID, COACH_USER_ID);

        verify(bookingService).transition(eq(BOOKING_ID), eq(BookingEvent.COMPLETE_PENDING), any(TransitionContext.class));
    }
}
