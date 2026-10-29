package com.softropic.skillars.platform.booking.service;

import com.softropic.skillars.infrastructure.util.TestClockProvider;
import com.softropic.skillars.platform.booking.contract.ActorRole;
import com.softropic.skillars.platform.booking.contract.BookingEvent;
import com.softropic.skillars.platform.booking.contract.BookingStateTransitionException;
import com.softropic.skillars.platform.booking.contract.BookingStatus;
import com.softropic.skillars.platform.booking.contract.SessionNeverStartedEvent;
import com.softropic.skillars.platform.booking.contract.TransitionContext;
import com.softropic.skillars.platform.booking.repo.Booking;
import com.softropic.skillars.platform.booking.repo.BookingRepository;
import com.softropic.skillars.platform.config.service.ConfigBounds;
import com.softropic.skillars.platform.config.service.ConfigService;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Pageable;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** fix/booking-completion-integrity: UnresolvedSessionSweeperService. */
@ExtendWith(MockitoExtension.class)
class UnresolvedSessionSweeperServiceTest {

    private static final Instant NOW = Instant.parse("2027-03-01T12:00:00Z");
    private static final long GRACE_MINUTES = 120L;

    @Mock private BookingRepository bookingRepository;
    @Mock private BookingService bookingService;
    @Mock private ConfigService configService;
    @Mock private ApplicationEventPublisher eventPublisher;

    private UnresolvedSessionSweeperService service;

    @BeforeEach
    void setUp() {
        TestClockProvider.setClock(Clock.fixed(NOW, ZoneOffset.UTC));
        TransactionTemplate txTemplate = new TransactionTemplate() {
            @Override
            public <T> T execute(org.springframework.transaction.support.TransactionCallback<T> action) {
                return action.doInTransaction(null);
            }
        };
        service = new UnresolvedSessionSweeperService(bookingRepository, bookingService, configService, txTemplate,
            eventPublisher);
        lenient().when(configService.getBoundedLong(ConfigBounds.BOOKING_UNRESOLVED_SESSION_GRACE_MINUTES))
            .thenReturn(GRACE_MINUTES);
        lenient().when(bookingRepository.findLiveSessionIdsEndedBefore(any(), any())).thenReturn(List.of());
        lenient().when(bookingRepository.findUnalertedNeverStartedIdsEndedBefore(any(), any())).thenReturn(List.of());
    }

    @AfterEach
    void tearDown() {
        TestClockProvider.unsetClock();
    }

    private static Booking booking(UUID id, String status) {
        Booking b = new Booking();
        b.setId(id);
        b.setStatus(status);
        b.setCoachId(UUID.randomUUID());
        b.setParentId(42L);
        b.setRequestedEndTime(NOW.minus(3, ChronoUnit.HOURS));
        return b;
    }

    @Test
    void sweep_queriesBothSetsAtEndPlusGrace_inBoundedPages() {
        service.sweep();

        Instant cutoff = NOW.minus(GRACE_MINUTES, ChronoUnit.MINUTES);
        ArgumentCaptor<Pageable> live = ArgumentCaptor.forClass(Pageable.class);
        ArgumentCaptor<Pageable> unstarted = ArgumentCaptor.forClass(Pageable.class);
        verify(bookingRepository).findLiveSessionIdsEndedBefore(eq(cutoff), live.capture());
        verify(bookingRepository).findUnalertedNeverStartedIdsEndedBefore(eq(cutoff), unstarted.capture());
        assertThat(live.getValue().getPageSize()).isEqualTo(UnresolvedSessionSweeperService.SWEEP_BATCH_SIZE);
        assertThat(unstarted.getValue().getPageSize()).isEqualTo(UnresolvedSessionSweeperService.SWEEP_BATCH_SIZE);
    }

    @Test
    void sweep_liveSessionPastItsEnd_movesToPendingConfirmationAsSystem_andStampsAutoEnded() {
        UUID id = UUID.randomUUID();
        Booking b = booking(id, BookingStatus.IN_PROGRESS.name());
        when(bookingRepository.findLiveSessionIdsEndedBefore(any(), any())).thenReturn(List.of(id));
        when(bookingService.getBookingOrThrow(id)).thenReturn(b);

        service.sweep();

        ArgumentCaptor<TransitionContext> ctx = ArgumentCaptor.forClass(TransitionContext.class);
        verify(bookingService).transition(eq(id), eq(BookingEvent.COMPLETE_PENDING), ctx.capture());
        assertThat(ctx.getValue().actorRole()).isEqualTo(ActorRole.SYSTEM);
        assertThat(b.getAutoEndedAt()).isEqualTo(NOW);
    }

    @Test
    void sweep_liveSessionEndedByTheCoachMeanwhile_isSkippedAndTheSweepGoesOn() {
        UUID raced = UUID.randomUUID();
        UUID next = UUID.randomUUID();
        Booking nextBooking = booking(next, BookingStatus.PAUSED.name());
        when(bookingRepository.findLiveSessionIdsEndedBefore(any(), any())).thenReturn(List.of(raced, next));
        doThrow(new BookingStateTransitionException(BookingStatus.COMPLETED_PENDING_CONFIRMATION, BookingEvent.COMPLETE_PENDING))
            .when(bookingService).transition(eq(raced), eq(BookingEvent.COMPLETE_PENDING), any());
        when(bookingService.getBookingOrThrow(next)).thenReturn(nextBooking);

        assertThatCode(() -> service.sweep()).doesNotThrowAnyException();

        verify(bookingService).transition(eq(next), eq(BookingEvent.COMPLETE_PENDING), any());
        assertThat(nextBooking.getAutoEndedAt()).isEqualTo(NOW);
    }

    @Test
    void sweep_neverStartedSessionPastItsEnd_isStampedAndAlertedOnce_neverTransitioned() {
        UUID id = UUID.randomUUID();
        Booking b = booking(id, BookingStatus.UPCOMING.name());
        when(bookingRepository.findUnalertedNeverStartedIdsEndedBefore(any(), any())).thenReturn(List.of(id));
        when(bookingService.lockBookingForUpdate(id)).thenReturn(b);

        service.sweep();

        assertThat(b.getUnresolvedAlertedAt()).isEqualTo(NOW);
        verify(bookingRepository).save(b);
        ArgumentCaptor<SessionNeverStartedEvent> event = ArgumentCaptor.forClass(SessionNeverStartedEvent.class);
        verify(eventPublisher).publishEvent(event.capture());
        assertThat(event.getValue().getBookingId()).isEqualTo(id);
        assertThat(event.getValue().getParentId()).isEqualTo(42L);
        verify(bookingService, never()).transition(any(), any(), any());
    }

    @Test
    void sweep_neverStartedSessionThatMovedOnUnderTheLock_isNotAlerted() {
        UUID id = UUID.randomUUID();
        Booking b = booking(id, BookingStatus.NO_SHOW_COACH.name());
        when(bookingRepository.findUnalertedNeverStartedIdsEndedBefore(any(), any())).thenReturn(List.of(id));
        when(bookingService.lockBookingForUpdate(id)).thenReturn(b);

        service.sweep();

        assertThat(b.getUnresolvedAlertedAt()).isNull();
        verify(bookingRepository, never()).save(any());
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    void sweep_carriesASchedulerLock() throws NoSuchMethodException {
        assertThat(UnresolvedSessionSweeperService.class.getMethod("sweep").getAnnotation(SchedulerLock.class))
            .isNotNull();
    }
}
