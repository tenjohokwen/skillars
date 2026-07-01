package com.softropic.skillars.platform.booking.service;

import com.softropic.skillars.platform.booking.contract.BookingExpiredEvent;
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
class BookingExpirySchedulerTest {

    private static final Long PARENT_ID = 2002L;

    @Mock BookingRepository bookingRepository;
    @Mock BookingService bookingService;
    @Mock ApplicationEventPublisher eventPublisher;
    @Mock ConfigService configService;
    @Mock CoachProfileRepository coachProfileRepository;
    @Mock UserRepository userRepository;

    private BookingExpiryScheduler scheduler;

    @BeforeEach
    void setUp() {
        scheduler = new BookingExpiryScheduler(bookingRepository, bookingService, eventPublisher,
                configService, coachProfileRepository, userRepository);
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
}
