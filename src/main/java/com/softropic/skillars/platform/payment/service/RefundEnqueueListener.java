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
        // C6: only money actually taken is refunded. isWalletRefund() is false for an ACCEPTED booking
        // (nothing charged yet) and for an unexpired pack booking (its session is restored by
        // CancellationRefundService instead) — the same rule as onBookingCancelledByAdmin.
        if (event.isWalletRefund()) {
            refundOutboxSupport.enqueueBookingRefund(
                event.getParentId(), event.getSessionPrice(), event.getBookingId(),
                event.getSessionPackPurchaseId() != null
                    ? "Coach cancellation — expired pack refund"
                    : "Coach cancellation — full refund");
        }
    }

    @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT)
    public void onCoachNoShow(CoachNoShowEvent event) {
        if (event.isWalletRefund()) {
            refundOutboxSupport.enqueueBookingRefund(
                event.getParentId(), event.getSessionPrice(), event.getBookingId(),
                event.getSessionPackPurchaseId() != null
                    ? "Coach no-show — expired pack refund"
                    : "Coach no-show — full refund");
        }
    }

    @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT)
    public void onBookingCancelledByAdmin(BookingCancelledByAdminEvent event) {
        // C2 (DECISIONS.md item 2): a coach suspension / child-safety hold makes the payer whole —
        // but only for money actually taken. isWalletRefund() is false for a pre-capture booking
        // (REQUESTED / ACCEPTED / PAYMENT_PENDING: nothing was charged) and for an unexpired pack
        // booking (its session is restored by CancellationRefundService instead).
        if (event.isWalletRefund()) {
            refundOutboxSupport.enqueueBookingRefund(
                event.getParentId(), event.getRefundAmount(), event.getBookingId(),
                refundDescription(event));
        }
    }

    /**
     * The parent sees this text on their credit statement, so it matches the notice they were sent: a
     * single admin cancellation (C6, ADMIN_SUPPORT) is a Skillars cancellation, never "coach unavailable".
     */
    private static String refundDescription(BookingCancelledByAdminEvent event) {
        String cause = BookingCancelledByAdminEvent.REASON_ADMIN_SUPPORT.equals(event.getCancelReason())
            ? "Cancelled by Skillars" : "Coach unavailable";
        return event.getSessionPackPurchaseId() != null
            ? cause + " — expired pack refund"
            : cause + " — full refund";
    }
}
