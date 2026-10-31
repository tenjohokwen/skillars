package com.softropic.skillars.platform.booking.service;

import com.softropic.skillars.infrastructure.util.TestClockProvider;
import com.softropic.skillars.platform.booking.contract.ActorRole;
import com.softropic.skillars.platform.booking.contract.BookingEvent;
import com.softropic.skillars.platform.booking.contract.BookingStateTransitionException;
import com.softropic.skillars.platform.booking.contract.BookingStatus;
import com.softropic.skillars.platform.booking.contract.AdminBookingCancellationOutcome;
import com.softropic.skillars.platform.booking.contract.BookingCancelledByAdminEvent;
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
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
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
    @Mock private BookingCompletionService bookingCompletionService;
    @Mock private ConfigService configService;

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
        service = new UnresolvedSessionSweeperService(bookingRepository, bookingService, bookingCompletionService,
            configService, txTemplate);
        lenient().when(configService.getBoundedLong(ConfigBounds.BOOKING_UNRESOLVED_SESSION_GRACE_MINUTES))
            .thenReturn(GRACE_MINUTES);
        lenient().when(bookingRepository.findLiveSessionIdsEndedBefore(any(), any())).thenReturn(List.of());
        lenient().when(configService.getBoundedLong(ConfigBounds.BOOKING_COACH_NO_SHOW_REPORT_WINDOW_HOURS)).thenReturn(5L);
        lenient().when(configService.getBoundedLong(ConfigBounds.BOOKING_UNRESOLVED_SESSION_AUTO_REFUND_HOURS)).thenReturn(48L);
        lenient().when(bookingRepository.findNeverStartedIdsStartedBefore(any(), any())).thenReturn(List.of());
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
    void sweep_queriesLiveSessionsAtEndPlusGrace_andUnstartedOnesAtStartPlusAutoRefundHours_inBoundedPages() {
        service.sweep();

        ArgumentCaptor<Pageable> live = ArgumentCaptor.forClass(Pageable.class);
        ArgumentCaptor<Pageable> unstarted = ArgumentCaptor.forClass(Pageable.class);
        verify(bookingRepository).findLiveSessionIdsEndedBefore(eq(NOW.minus(GRACE_MINUTES, ChronoUnit.MINUTES)), live.capture());
        verify(bookingRepository).findNeverStartedIdsStartedBefore(eq(NOW.minus(48, ChronoUnit.HOURS)), unstarted.capture());
        assertThat(live.getValue().getPageSize()).isEqualTo(UnresolvedSessionSweeperService.SWEEP_BATCH_SIZE);
        assertThat(unstarted.getValue().getPageSize()).isEqualTo(UnresolvedSessionSweeperService.SWEEP_BATCH_SIZE);
    }

    /** The family's claim window always closes first, whatever the config says. */
    @Test
    void sweep_autoRefundIsNeverEarlierThanTheReportWindowPlusOneHour() {
        when(configService.getBoundedLong(ConfigBounds.BOOKING_UNRESOLVED_SESSION_AUTO_REFUND_HOURS)).thenReturn(3L);
        when(configService.getBoundedLong(ConfigBounds.BOOKING_COACH_NO_SHOW_REPORT_WINDOW_HOURS)).thenReturn(5L);

        service.sweep();

        verify(bookingRepository).findNeverStartedIdsStartedBefore(eq(NOW.minus(6, ChronoUnit.HOURS)), any());
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
        // fix/audit3-completion-dispute-integrity: the family is asked to confirm or dispute it at once.
        verify(bookingCompletionService).requestFamilyConfirmation(b);
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
    void sweep_neverStartedSession_isRefundedInFullAsTheSystem_withTheNeverStartedReason() {
        UUID id = UUID.randomUUID();
        when(bookingRepository.findNeverStartedIdsStartedBefore(any(), any())).thenReturn(List.of(id));
        when(bookingService.cancelBookingAsAdmin(id, UnresolvedSessionSweeperService.SYSTEM_ACTOR_ID,
            BookingCancelledByAdminEvent.REASON_NEVER_STARTED)).thenReturn(AdminBookingCancellationOutcome.CANCELLED);

        service.sweep();

        verify(bookingService).cancelBookingAsAdmin(id, 0L, "NEVER_STARTED");
        verify(bookingService, never()).transition(any(), any(), any());
    }

    @Test
    void sweep_neverStartedSessionThatMovedOnUnderTheLock_isLeftAloneAndTheSweepGoesOn() {
        UUID moved = UUID.randomUUID();
        UUID next = UUID.randomUUID();
        when(bookingRepository.findNeverStartedIdsStartedBefore(any(), any())).thenReturn(List.of(moved, next));
        when(bookingService.cancelBookingAsAdmin(eq(moved), anyLong(), anyString()))
            .thenReturn(AdminBookingCancellationOutcome.NOT_CANCELLABLE);
        when(bookingService.cancelBookingAsAdmin(eq(next), anyLong(), anyString()))
            .thenThrow(new org.springframework.dao.OptimisticLockingFailureException("raced"));

        assertThatCode(() -> service.sweep()).doesNotThrowAnyException();

        verify(bookingService).cancelBookingAsAdmin(eq(moved), anyLong(), anyString());
        verify(bookingService).cancelBookingAsAdmin(eq(next), anyLong(), anyString());
    }

    @Test
    void sweep_carriesASchedulerLock() throws NoSuchMethodException {
        assertThat(UnresolvedSessionSweeperService.class.getMethod("sweep").getAnnotation(SchedulerLock.class))
            .isNotNull();
    }
}
