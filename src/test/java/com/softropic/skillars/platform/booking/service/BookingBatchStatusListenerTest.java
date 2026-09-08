package com.softropic.skillars.platform.booking.service;

import com.softropic.skillars.platform.booking.contract.BookingStatusChangedEvent;
import com.softropic.skillars.platform.booking.repo.Booking;
import com.softropic.skillars.platform.booking.repo.BookingRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.PessimisticLockingFailureException;

import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class BookingBatchStatusListenerTest {

    @Mock
    private BookingRepository bookingRepository;

    @Mock
    private BookingBatchService bookingBatchService;

    @InjectMocks
    private BookingBatchStatusListener listener;

    @Test
    void onBookingStatusChanged_nullBookingId_returnsWithoutCallingRepository() {
        BookingStatusChangedEvent event = new BookingStatusChangedEvent(this, null, "CONFIRMED");

        listener.onBookingStatusChanged(event);

        verify(bookingRepository, never()).findById(any());
    }

    @Test
    void onBookingStatusChanged_updateBatchStatusThrows_catchesAndContinues() {
        UUID bookingId = UUID.randomUUID();
        BookingStatusChangedEvent event = new BookingStatusChangedEvent(this, bookingId, "CONFIRMED");

        Booking booking = new Booking();
        booking.setBatchId(UUID.randomUUID());

        when(bookingRepository.findById(bookingId)).thenReturn(Optional.of(booking));
        doThrow(new PessimisticLockingFailureException("Lock failed"))
            .when(bookingBatchService).updateBatchStatusFromBooking(booking.getBatchId());

        listener.onBookingStatusChanged(event);

        verify(bookingRepository).findById(bookingId);
        verify(bookingBatchService).updateBatchStatusFromBooking(booking.getBatchId());
    }
}
