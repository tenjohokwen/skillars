package com.softropic.skillars.platform.booking.service;

import com.softropic.skillars.infrastructure.util.TestClockProvider;
import com.softropic.skillars.platform.booking.contract.ActorRole;
import com.softropic.skillars.platform.booking.contract.ForceCompleteOutcome;
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

import java.lang.reflect.Method;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class StuckCompletionSweeperServiceTest {

    @Mock private BookingRepository bookingRepository;
    @Mock private BookingCompletionService bookingCompletionService;
    @Mock private ConfigService configService;

    private StuckCompletionSweeperService service;

    private static final UUID BOOKING_ID = UUID.randomUUID();

    // skillars-deferred-153 code review (2026-10-17): the cutoff is the only business logic this
    // class computes and it was previously unassertable (every stub matched it with
    // any(Instant.class), so flipping minus to plus would have kept this suite green). Pinning the
    // clock the same way CoachRosterServiceTest/SluDashboardServiceTest do makes it exact.
    private static final Instant NOW = Instant.parse("2027-03-01T12:00:00Z");
    private static final long TIMEOUT_HOURS = 24L;

    @BeforeEach
    void setUp() {
        TestClockProvider.setClock(Clock.fixed(NOW, ZoneOffset.UTC));
        TransactionTemplate txTemplate = new TransactionTemplate() {
            @Override
            public <T> T execute(org.springframework.transaction.support.TransactionCallback<T> action) {
                return action.doInTransaction(null);
            }
        };
        service = new StuckCompletionSweeperService(bookingRepository, bookingCompletionService, configService, txTemplate);
        lenient().when(configService.getBoundedLong(
                eq(ConfigBounds.BOOKING_STUCK_COMPLETION_TIMEOUT_HOURS.key()),
                eq(ConfigBounds.BOOKING_STUCK_COMPLETION_TIMEOUT_HOURS.defaultValue()),
                eq(ConfigBounds.BOOKING_STUCK_COMPLETION_TIMEOUT_HOURS.min()),
                eq(ConfigBounds.BOOKING_STUCK_COMPLETION_TIMEOUT_HOURS.max())))
            .thenReturn(TIMEOUT_HOURS);
    }

    @AfterEach
    void tearDown() {
        TestClockProvider.unsetClock();
    }

    @Test
    void processStuckCompletions_stuckBooking_forceCompletesWithSystemActor() {
        Booking booking = new Booking();
        booking.setId(BOOKING_ID);
        when(bookingRepository.findStuckCompletionsWithNoSessionData(any(Instant.class), any(Pageable.class)))
            .thenReturn(List.of(booking));
        when(bookingCompletionService.forceCompleteWithPlaceholderData(BOOKING_ID, ActorRole.SYSTEM, null))
            .thenReturn(ForceCompleteOutcome.FORCE_COMPLETED);

        service.processStuckCompletions();

        verify(bookingCompletionService)
            .forceCompleteWithPlaceholderData(BOOKING_ID, ActorRole.SYSTEM, null);
    }

    @Test
    void processStuckCompletions_noneStuck_doesNothing() {
        when(bookingRepository.findStuckCompletionsWithNoSessionData(any(Instant.class), any(Pageable.class)))
            .thenReturn(List.of());

        service.processStuckCompletions();

        verify(bookingCompletionService, never())
            .forceCompleteWithPlaceholderData(any(), any(), any());
    }

    @Test
    void processStuckCompletions_oneBookingFails_continuesToNextBooking() {
        Booking booking1 = new Booking();
        booking1.setId(UUID.randomUUID());
        Booking booking2 = new Booking();
        booking2.setId(BOOKING_ID);
        when(bookingRepository.findStuckCompletionsWithNoSessionData(any(Instant.class), any(Pageable.class)))
            .thenReturn(List.of(booking1, booking2));
        org.mockito.Mockito.doThrow(new RuntimeException("boom"))
            .when(bookingCompletionService)
            .forceCompleteWithPlaceholderData(booking1.getId(), ActorRole.SYSTEM, null);

        service.processStuckCompletions();

        verify(bookingCompletionService).forceCompleteWithPlaceholderData(booking2.getId(), ActorRole.SYSTEM, null);
    }

    // skillars-deferred-153 code review (2026-10-17): a benign no-op outcome must not abort the
    // batch — the batch read is unlocked, so a real wrap-up (or another instance) can resolve a
    // booking between the query and this iteration.
    @Test
    void processStuckCompletions_benignNoOpOutcome_continuesToNextBooking() {
        Booking booking1 = new Booking();
        booking1.setId(UUID.randomUUID());
        Booking booking2 = new Booking();
        booking2.setId(BOOKING_ID);
        when(bookingRepository.findStuckCompletionsWithNoSessionData(any(Instant.class), any(Pageable.class)))
            .thenReturn(List.of(booking1, booking2));
        when(bookingCompletionService.forceCompleteWithPlaceholderData(booking1.getId(), ActorRole.SYSTEM, null))
            .thenReturn(ForceCompleteOutcome.ALREADY_RESOLVED);
        when(bookingCompletionService.forceCompleteWithPlaceholderData(booking2.getId(), ActorRole.SYSTEM, null))
            .thenReturn(ForceCompleteOutcome.FORCE_COMPLETED);

        service.processStuckCompletions();

        verify(bookingCompletionService).forceCompleteWithPlaceholderData(booking2.getId(), ActorRole.SYSTEM, null);
    }

    // skillars-deferred-153 code review (2026-10-17): the cutoff sign and the batch bound, both
    // previously unassertable. Captures the real arguments rather than matching them with any().
    @Test
    void processStuckCompletions_queriesWithNowMinusTimeoutAndABoundedFirstPage() {
        when(bookingRepository.findStuckCompletionsWithNoSessionData(any(Instant.class), any(Pageable.class)))
            .thenReturn(List.of());

        service.processStuckCompletions();

        ArgumentCaptor<Instant> cutoffCaptor = ArgumentCaptor.forClass(Instant.class);
        ArgumentCaptor<Pageable> pageableCaptor = ArgumentCaptor.forClass(Pageable.class);
        verify(bookingRepository).findStuckCompletionsWithNoSessionData(
            cutoffCaptor.capture(), pageableCaptor.capture());

        // minus, not plus: a sign flip would make the sweeper force-complete bookings that were
        // Quick-Completed in the FUTURE relative to the cutoff — i.e. effectively all of them.
        assertThat(cutoffCaptor.getValue()).isEqualTo(NOW.minus(TIMEOUT_HOURS, ChronoUnit.HOURS));
        assertThat(cutoffCaptor.getValue()).isBefore(NOW);

        Pageable captured = pageableCaptor.getValue();
        assertThat(captured.getPageNumber()).isZero();
        assertThat(captured.getPageSize()).isEqualTo(200);   // SWEEP_BATCH_SIZE
    }

    @Test
    void processStuckCompletions_carriesSchedulerLock() throws NoSuchMethodException {
        Method method = StuckCompletionSweeperService.class.getMethod("processStuckCompletions");
        SchedulerLock lock = method.getAnnotation(SchedulerLock.class);

        assertThat(lock).as("processStuckCompletions() must carry @SchedulerLock").isNotNull();
        assertThat(lock.name()).isNotBlank();
        assertThat(Duration.parse(lock.lockAtMostFor())).isPositive();
        assertThat(Duration.parse(lock.lockAtLeastFor())).isPositive();
    }
}
