package com.softropic.skillars.platform.booking.service;

import com.softropic.skillars.platform.booking.contract.BookingEvent;
import com.softropic.skillars.platform.booking.contract.BookingExpiredEvent;
import com.softropic.skillars.platform.booking.contract.BookingStateTransitionException;
import com.softropic.skillars.platform.booking.contract.BookingStatus;
import com.softropic.skillars.platform.booking.repo.Booking;
import com.softropic.skillars.platform.booking.repo.BookingRepository;
import com.softropic.skillars.platform.config.service.ConfigService;
import com.softropic.skillars.platform.marketplace.repo.CoachProfileRepository;
import com.softropic.skillars.platform.security.repo.User;
import com.softropic.skillars.platform.security.repo.UserRepository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BookingExpirySchedulerTest {

    private static final Long PARENT_ID = 2002L;

    @Mock BookingRepository bookingRepository;
    @Mock BookingService bookingService;
    @Mock ApplicationEventPublisher eventPublisher;
    @Mock ConfigService configService;
    @Mock CoachProfileRepository coachProfileRepository;
    @Mock UserRepository userRepository;
    // skillars-deferred-118 AC1: the scheduler no longer carries a method-level @Transactional;
    // each booking is processed in its own TransactionTemplate scope. A real template over a stub
    // manager keeps that structure visible here rather than mocking it away (mirrors
    // BookingReminderSchedulerTest's established pattern for this exact scheduler shape).
    @Mock PlatformTransactionManager transactionManager;

    private BookingExpiryScheduler scheduler;

    @BeforeEach
    void setUp() {
        lenient().when(transactionManager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        scheduler = new BookingExpiryScheduler(bookingRepository, bookingService, eventPublisher,
                configService, coachProfileRepository, userRepository,
                new TransactionTemplate(transactionManager));
        lenient().when(configService.getBoundedLong(eq("booking.request_expiry_hours"), anyLong(), anyLong(), anyLong())).thenReturn(48L);
    }

    private Booking buildBooking() {
        Booking booking = new Booking();
        booking.setId(UUID.randomUUID());
        booking.setParentId(PARENT_ID);
        booking.setCoachId(UUID.randomUUID());
        booking.setRequestedStartTime(Instant.now());
        booking.setCanonicalTimezone("UTC");
        booking.setCreatedAt(Instant.now());
        return booking;
    }

    @Test
    void resolveEmail_unknownParent_publishesBlankEmailInsteadOfThrowing() {
        Booking booking = buildBooking();
        when(bookingRepository.findRequestedBookingsOlderThan(any())).thenReturn(List.of(booking));
        when(coachProfileRepository.findById(any())).thenReturn(Optional.empty());
        when(userRepository.findById(PARENT_ID)).thenReturn(Optional.empty());

        scheduler.expireStaleRequests();

        ArgumentCaptor<BookingExpiredEvent> captor = ArgumentCaptor.forClass(BookingExpiredEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        assertThat(captor.getValue().getParentEmail()).isBlank();
        assertThat(captor.getValue().getBookingId()).isEqualTo(booking.getId());
    }

    @Test
    void resolveEmail_knownParent_publishesResolvedEmail() {
        Booking booking = buildBooking();
        User parent = new User();
        parent.setEmail("parent@example.com");
        when(bookingRepository.findRequestedBookingsOlderThan(any())).thenReturn(List.of(booking));
        when(coachProfileRepository.findById(any())).thenReturn(Optional.empty());
        when(userRepository.findById(PARENT_ID)).thenReturn(Optional.of(parent));

        scheduler.expireStaleRequests();

        ArgumentCaptor<BookingExpiredEvent> captor = ArgumentCaptor.forClass(BookingExpiredEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        assertThat(captor.getValue().getParentEmail()).isEqualTo("parent@example.com");
    }

    /**
     * skillars-deferred-118 AC1 — the actual regression this story exists to prevent. Before the
     * fix, both bookings shared one physical transaction: the second booking's
     * {@code BookingStateTransitionException} (simulating a coach-accept/parent-cancel race) marked
     * that shared transaction rollback-only, and the swallowing per-iteration {@code catch} could not
     * stop the whole batch — including the first booking's already-successful expiry — from being
     * discarded at commit via an uncaught {@code UnexpectedRollbackException}. With per-booking
     * {@code TransactionTemplate} scopes, the first booking's transition + event publish must commit
     * independently of the second booking's failure, and the exception must never propagate out of
     * {@code expireStaleRequests()}.
     */
    @Test
    void expireStaleRequests_secondBookingRacesAndThrows_firstBookingsExpiryStillCommits() {
        Booking first = buildBooking();
        Booking second = buildBooking();
        when(bookingRepository.findRequestedBookingsOlderThan(any())).thenReturn(List.of(first, second));
        when(coachProfileRepository.findById(any())).thenReturn(Optional.empty());
        when(userRepository.findById(PARENT_ID)).thenReturn(Optional.empty());
        // First booking transitions normally. Second booking raced by a concurrent coach-accept/
        // parent-cancel between the batch select and its own turn in the loop: its current status is
        // no longer REQUESTED. Both calls stubbed explicitly — Mockito's strict-stubs mode flags an
        // unstubbed invocation as a "PotentialStubbingProblem" once any doThrow() exists for the same
        // method with different argument matchers.
        doNothing().when(bookingService).transition(eq(first.getId()), eq(BookingEvent.DECLINE), any());
        doThrow(new BookingStateTransitionException(BookingStatus.ACCEPTED, BookingEvent.DECLINE))
            .when(bookingService).transition(eq(second.getId()), eq(BookingEvent.DECLINE), any());

        assertThatCode(() -> scheduler.expireStaleRequests()).doesNotThrowAnyException();

        verify(bookingService).transition(eq(first.getId()), eq(BookingEvent.DECLINE), any());
        verify(bookingService).transition(eq(second.getId()), eq(BookingEvent.DECLINE), any());
        ArgumentCaptor<BookingExpiredEvent> captor = ArgumentCaptor.forClass(BookingExpiredEvent.class);
        verify(eventPublisher, times(1)).publishEvent(captor.capture());
        assertThat(captor.getValue().getBookingId()).isEqualTo(first.getId());
    }
}
