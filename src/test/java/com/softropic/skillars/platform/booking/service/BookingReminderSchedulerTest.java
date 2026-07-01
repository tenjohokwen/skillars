package com.softropic.skillars.platform.booking.service;

import com.softropic.skillars.platform.booking.contract.BookingReminderEvent;
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

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BookingReminderSchedulerTest {

    private static final Long PARENT_ID = 1001L;
    private static final UUID COACH_ID = UUID.randomUUID();

    @Mock BookingRepository bookingRepository;
    @Mock BookingService bookingService;
    @Mock ApplicationEventPublisher eventPublisher;
    @Mock ConfigService configService;
    @Mock CoachProfileRepository coachProfileRepository;
    @Mock UserRepository userRepository;

    private BookingReminderScheduler scheduler;

    @BeforeEach
    void setUp() {
        scheduler = new BookingReminderScheduler(bookingRepository, bookingService, eventPublisher,
                configService, coachProfileRepository, userRepository);
        lenient().when(configService.getBoundedLong(eq("platform.reminder_interval_primary_hours"), anyLong(), anyLong(), anyLong())).thenReturn(24L);
        lenient().when(configService.getBoundedLong(eq("platform.reminder_interval_secondary_hours"), anyLong(), anyLong(), anyLong())).thenReturn(2L);
    }

    private Booking buildBooking() {
        Booking booking = new Booking();
        booking.setId(UUID.randomUUID());
        booking.setParentId(PARENT_ID);
        booking.setCoachId(COACH_ID);
        booking.setRequestedStartTime(Instant.now());
        booking.setCanonicalTimezone("UTC");
        return booking;
    }

    @Test
    void resolveEmail_unknownParent_publishesBlankEmailInsteadOfThrowing() {
        Booking booking = buildBooking();
        when(bookingRepository.findConfirmedForUpcomingTransition(any())).thenReturn(List.of(booking));
        when(bookingRepository.findUpcomingWithin2hWindow(any(), any())).thenReturn(List.of());
        when(coachProfileRepository.findById(COACH_ID)).thenReturn(Optional.empty());
        when(userRepository.findById(PARENT_ID)).thenReturn(Optional.empty());

        scheduler.processReminderWindows();

        ArgumentCaptor<BookingReminderEvent> captor = ArgumentCaptor.forClass(BookingReminderEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        BookingReminderEvent event = captor.getValue();
        assertThat(event.getParentEmail()).isBlank();
        assertThat(event.getBookingId()).isEqualTo(booking.getId());
    }

    @Test
    void resolveEmail_knownParent_publishesResolvedEmail() {
        Booking booking = buildBooking();
        User parent = new User();
        parent.setEmail("parent@example.com");
        when(bookingRepository.findConfirmedForUpcomingTransition(any())).thenReturn(List.of(booking));
        when(bookingRepository.findUpcomingWithin2hWindow(any(), any())).thenReturn(List.of());
        when(coachProfileRepository.findById(COACH_ID)).thenReturn(Optional.empty());
        when(userRepository.findById(PARENT_ID)).thenReturn(Optional.of(parent));

        scheduler.processReminderWindows();

        ArgumentCaptor<BookingReminderEvent> captor = ArgumentCaptor.forClass(BookingReminderEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        assertThat(captor.getValue().getParentEmail()).isEqualTo("parent@example.com");
    }
}
