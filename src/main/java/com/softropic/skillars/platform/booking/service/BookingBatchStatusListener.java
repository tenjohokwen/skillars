package com.softropic.skillars.platform.booking.service;

import com.softropic.skillars.platform.booking.contract.BookingStatusChangedEvent;
import com.softropic.skillars.platform.booking.repo.BookingRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Service
@RequiredArgsConstructor
@Slf4j
public class BookingBatchStatusListener {

    private final BookingRepository bookingRepository;
    private final BookingBatchService bookingBatchService;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onBookingStatusChanged(BookingStatusChangedEvent event) {
        bookingRepository.findById(event.bookingId()).ifPresent(booking -> {
            if (booking.getBatchId() != null) {
                bookingBatchService.updateBatchStatusFromBooking(booking.getBatchId());
            }
        });
    }
}
