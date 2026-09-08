package com.softropic.skillars.platform.payment.service;

import com.softropic.skillars.platform.booking.contract.BookingCancelledByAdminEvent;
import com.softropic.skillars.platform.booking.contract.BookingCancelledByCoachEvent;
import com.softropic.skillars.platform.booking.contract.BookingCancelledByParentEvent;
import com.softropic.skillars.platform.booking.contract.CoachNoShowEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * skillars-deferred-101 AC4: dedicated BEFORE_COMMIT listener for BOOKING_REFUND enqueue atomicity.
 *
 * <p>This listener runs in the publishing (business) transaction, so refund enqueue happens
 * atomically with the booking CANCELLED write. Runs before commit, so a failed enqueue rolls back
 * the entire transaction, surfacing the error to the caller rather than silently losing money.
 * This complements the separate AFTER_COMMIT listeners in {@link CancellationRefundService},
 * which handle pack-restore, cancellation history, and strike issuance (which must survive
 * refund failures — see AC4).
 *
 * <p>Replicate the exact enqueue conditions from the six sites in CancellationRefundService.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RefundEnqueueListener {

    private final RefundOutboxSupport refundOutboxSupport;

    @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT)
    public void onBookingCancelledByParent(BookingCancelledByParentEvent event) {
        // skillars-10-2 D1, skillars-deferred-101 AC4: enqueue in the same tx as CANCELLED write
        if (event.getSessionPackPurchaseId() == null && event.isRefundEligible()) {
            refundOutboxSupport.enqueueBookingRefund(
                event.getParentId(), event.getSessionPrice(), event.getBookingId(),
                "Parent cancellation >24h — full refund");
        }
    }

    @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT)
    public void onBookingCancelledByCoach(BookingCancelledByCoachEvent event) {
        // Replicate the two enqueue sites from CancellationRefundService.onBookingCancelledByCoach
        if (event.getSessionPackPurchaseId() != null && event.isPackExpiredAtCancellation()) {
            refundOutboxSupport.enqueueBookingRefund(
                event.getParentId(), event.getSessionPrice(), event.getBookingId(),
                "Coach cancellation — expired pack refund");
        } else if (event.getSessionPackPurchaseId() == null) {
            refundOutboxSupport.enqueueBookingRefund(
                event.getParentId(), event.getSessionPrice(), event.getBookingId(),
                "Coach cancellation — full refund");
        }
    }

    @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT)
    public void onCoachNoShow(CoachNoShowEvent event) {
        // Replicate the two enqueue sites from CancellationRefundService.onCoachNoShow
        if (event.getSessionPackPurchaseId() != null && event.isPackExpiredAtCancellation()) {
            refundOutboxSupport.enqueueBookingRefund(
                event.getParentId(), event.getSessionPrice(), event.getBookingId(),
                "Coach no-show — expired pack refund");
        } else if (event.getSessionPackPurchaseId() == null) {
            refundOutboxSupport.enqueueBookingRefund(
                event.getParentId(), event.getSessionPrice(), event.getBookingId(),
                "Coach no-show — full refund");
        }
    }

    @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT)
    public void onBookingCancelledByAdmin(BookingCancelledByAdminEvent event) {
        // Replicate the enqueue site from CancellationRefundService.onBookingCancelledByAdmin
        if (event.getSessionPackPurchaseId() == null) {
            refundOutboxSupport.enqueueBookingRefund(
                event.getParentId(), event.getSessionPrice(), event.getBookingId(),
                "Admin coach suspension — full refund");
        }
    }
}
