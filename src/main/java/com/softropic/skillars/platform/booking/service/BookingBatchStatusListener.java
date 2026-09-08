package com.softropic.skillars.platform.booking.service;

import com.softropic.skillars.platform.booking.contract.BookingStatusChangedEvent;
import com.softropic.skillars.platform.booking.repo.BookingRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Service
@RequiredArgsConstructor
@Slf4j
public class BookingBatchStatusListener {

    private final BookingRepository bookingRepository;
    private final BookingBatchService bookingBatchService;

    /**
     * skillars-deferred-100 AC4: {@code REQUIRES_NEW} gives this AFTER_COMMIT listener its own
     * short transaction so {@code bookingRepository.findById} no longer runs with no transaction at
     * all (the enclosing one has already committed). This mirrors
     * {@link BookingBatchService#updateBatchStatusFromBooking}, which is itself
     * {@code REQUIRES_NEW}; the batch-row locking there is correct and untouched. The
     * {@code batchId != null} guard below stays — a non-batched booking must be a no-op, and
     * {@code updateBatchStatusFromBooking(null)} must never be called.
     *
     * skillars-deferred-101 AC1: added null-check on event.bookingId() for defence-in-depth, and
     * failure isolation so exceptions during batch-status refresh do not poison the committed
     * transaction's commit() call. Swallowed exceptions self-heal on the next status change.
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public void onBookingStatusChanged(BookingStatusChangedEvent event) {
        if (event.bookingId() == null) {
            log.warn("BookingStatusChangedEvent with null bookingId — ignoring");
            return;
        }
        try {
            bookingRepository.findById(event.bookingId()).ifPresent(booking -> {
                if (booking.getBatchId() != null) {
                    bookingBatchService.updateBatchStatusFromBooking(booking.getBatchId());
                }
            });
        } catch (RuntimeException e) {
            log.error("Batch-status refresh failed for bookingId={} — batch status may be stale until the next status change on this batch", event.bookingId(), e);
        }
    }
}
