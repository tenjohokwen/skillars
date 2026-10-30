package com.softropic.skillars.platform.payment.service;

import com.softropic.skillars.platform.booking.contract.BookingCancelledByAdminEvent;
import com.softropic.skillars.platform.booking.contract.BookingCancelledByCoachEvent;
import com.softropic.skillars.platform.booking.contract.BookingCancelledByParentEvent;
import com.softropic.skillars.platform.booking.contract.BookingCancelledDueToPauseEvent;
import com.softropic.skillars.platform.booking.contract.CoachNoShowEvent;
import com.softropic.skillars.platform.payment.contract.event.PaymentIntegrityAlertEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * skillars-deferred-101 AC4: dedicated BEFORE_COMMIT listener for BOOKING_REFUND enqueue atomicity.
 *
 * <p>This listener runs in the publishing (business) transaction, so refund enqueue happens
 * atomically with the booking CANCELLED write. Runs before commit, so a failed enqueue rolls back
 * the entire transaction, surfacing the error to the caller rather than silently losing money.
 * This complements the separate AFTER_COMMIT listeners in {@link CancellationRefundService},
 * which handle cancellation history and strike issuance (which must survive refund failures — see AC4).
 *
 * <p>fix/payment-session-pack-integrity: pack session restores are enqueued here too, onto the durable
 * outbox ({@link PackRestoreOutboxSupport}), for the same atomicity reason — they used to run inline in
 * those AFTER_COMMIT listeners, where a failure lost the session.
 *
 * <p>Replicate the exact enqueue conditions from the six sites in CancellationRefundService.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RefundEnqueueListener {

    private final RefundOutboxSupport refundOutboxSupport;
    private final PackRestoreOutboxSupport packRestoreOutboxSupport;
    private final PackRefundAllocationService packRefundAllocationService;
    private final ApplicationEventPublisher eventPublisher;

    @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT)
    public void onBookingCancelledByParent(BookingCancelledByParentEvent event) {
        // skillars-10-2 D1, skillars-deferred-101 AC4: enqueue in the same tx as CANCELLED write.
        // fix/payment-pack-clawback-and-expired-cancel: a refund-eligible (>24h) cancellation on an EXPIRED pack
        // refunds the session's value to the wallet, settled on the pack ledger like the coach / admin / no-show
        // expired-pack refunds; restoring it to the expired pack would give back nothing usable.
        // fix/payment-wallet-dispute-integrity (owner decision 2026-10-29): that expired-pack refund is SPEND-ONLY
        // credit — usable for bookings, never cashed out. A card / credit booking's refund stays cashable.
        if (event.isWalletRefund()) {
            boolean expiredPack = event.getSessionPackPurchaseId() != null;
            enqueueWalletRefund(event.getParentId(), event.getSessionPackPurchaseId(), event.getBookingId(),
                event.getSessionPrice(),
                expiredPack
                    ? "Parent cancellation >24h — expired pack refund (booking credit only)"
                    : "Parent cancellation >24h — full refund",
                expiredPack);
        }
        if (event.isPackRestore()) {
            packRestoreOutboxSupport.enqueuePackRestore(
                event.getSessionPackPurchaseId(), event.getBookingId(), "PARENT_CANCELLATION");
        }
        // else: forfeited (<=24h), no action — session consumed
    }

    @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT)
    public void onBookingCancelledByCoach(BookingCancelledByCoachEvent event) {
        // C6: only money actually taken is refunded. isWalletRefund() is false for an ACCEPTED booking
        // (nothing charged yet) and for an unexpired pack booking (its session is restored by
        // CancellationRefundService instead) — the same rule as onBookingCancelledByAdmin.
        // fix/audit3-wallet-cashout-integrity: a MUTUAL_AGREEMENT cancel is the parent agreeing to drop the session,
        // so an expired-pack refund is SPEND-ONLY exactly as for the parent's own >24h cancel (otherwise a parent
        // could turn an expired pack into cash by asking the coach to cancel). Other coach reasons stay cashable.
        // A card / credit booking's refund is split by CreditWalletRefundOutboxHandler either way.
        boolean mutual = isMutualAgreement(event.getCancelReason());
        if (event.isWalletRefund()) {
            boolean expiredPack = event.getSessionPackPurchaseId() != null;
            enqueueWalletRefund(event.getParentId(), event.getSessionPackPurchaseId(), event.getBookingId(),
                event.getSessionPrice(),
                expiredPack
                    ? (mutual ? "Coach cancellation (mutual agreement) — expired pack refund (booking credit only)"
                              : "Coach cancellation — expired pack refund")
                    : "Coach cancellation — full refund", expiredPack && mutual);
        }
        if (event.isPackRestore()) {
            packRestoreOutboxSupport.enqueuePackRestore(
                event.getSessionPackPurchaseId(), event.getBookingId(),
                mutual ? PackSessionService.RESTORE_REASON_COACH_MUTUAL_AGREEMENT : "COACH_CANCELLATION");
        }
    }

    @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT)
    public void onCoachNoShow(CoachNoShowEvent event) {
        if (event.isWalletRefund()) {
            enqueueWalletRefund(event.getParentId(), event.getSessionPackPurchaseId(), event.getBookingId(),
                event.getSessionPrice(),
                event.getSessionPackPurchaseId() != null
                    ? "Coach no-show — expired pack refund"
                    : "Coach no-show — full refund", false);
        }
        if (event.isPackRestore()) {
            packRestoreOutboxSupport.enqueuePackRestore(
                event.getSessionPackPurchaseId(), event.getBookingId(), "COACH_NO_SHOW");
        }
    }

    @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT)
    public void onBookingCancelledByAdmin(BookingCancelledByAdminEvent event) {
        // C2 (DECISIONS.md item 2): a coach suspension / child-safety hold makes the payer whole —
        // but only for money actually taken. isWalletRefund() is false for a pre-capture booking
        // (REQUESTED / ACCEPTED / PAYMENT_PENDING: nothing was charged) and for an unexpired pack
        // booking (its session is restored by CancellationRefundService instead).
        if (event.isWalletRefund()) {
            enqueueWalletRefund(event.getParentId(), event.getSessionPackPurchaseId(), event.getBookingId(),
                event.getRefundAmount(), refundDescription(event), false);
        }
        if (event.isPackRestore()) {
            packRestoreOutboxSupport.enqueuePackRestore(
                event.getSessionPackPurchaseId(), event.getBookingId(), "ADMIN_CANCELLATION");
        }
    }

    /**
     * fix/payment-session-pack-integrity: a pack pause cancelling a paid booking used to return nothing (the
     * only listener was the coach email). Captured money comes back — the pack session, or the amount
     * actually paid as wallet credit; a not-yet-captured booking was never charged. No coach strike: the
     * parent paused.
     */
    @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT)
    public void onBookingCancelledDueToPause(BookingCancelledDueToPauseEvent event) {
        if (event.isWalletRefund() && event.getRefundAmount() != null && event.getRefundAmount().signum() > 0) {
            enqueueWalletRefund(event.getParentId(), event.getSessionPackPurchaseId(), event.getBookingId(),
                event.getRefundAmount(),
                event.getSessionPackPurchaseId() != null
                    ? "Pack pause — expired pack refund (booking credit only)"
                    : "Pack pause — full refund",
                // fix/audit3-wallet-cashout-integrity: the parent paused — an expired-pack refund is spend-only,
                // as for the parent's own >24h cancel (PackSessionService does the same at restore time).
                event.getSessionPackPurchaseId() != null);
        }
        if (event.isPackRestore()) {
            packRestoreOutboxSupport.enqueuePackRestore(
                event.getSessionPackPurchaseId(), event.getBookingId(), "PACK_PAUSE");
        }
    }

    /**
     * fix/payment-pack-refund-rounding: a wallet refund of a pack session (the pack expired, so restoring the
     * session would be worthless) is settled on the pack's ledger under its row lock — the per-session share,
     * or the pack's unsettled remainder when it is the last session to settle — instead of the rounded-down
     * share the cancellation computed, which lost up to {@code session_count - 1} cents per pack. A card /
     * credit booking refunds what was paid, unchanged.
     */
    private void enqueueWalletRefund(Long parentId, UUID packId, UUID bookingId,
                                     BigDecimal computed, String description, boolean spendOnly) {
        BigDecimal amount = packId == null
            ? computed
            : packRefundAllocationService.allocateSessionRefund(packId, bookingId, computed);
        if (packId != null && (amount == null || amount.signum() <= 0)) {
            log.warn("[PACK_REFUND] booking={} pack={} — the session settles at 0.00, no wallet credit enqueued",
                bookingId, packId);
            return;
        }
        if (amount == null || amount.signum() <= 0) {
            // fix/payment-wallet-dispute-integrity: a refund is owed but its amount is unknown (e.g. an admin
            // cancel of a captured booking with no payment row and no agreed price). A 0.00 payload used to be
            // enqueued and fail on every drain forever; an operator refunds it by hand instead.
            log.error("[REFUND_AMOUNT_UNKNOWN] booking={} parentId={} — refund owed but its amount is {}; nothing "
                + "enqueued, admin alert raised", bookingId, parentId, amount);
            eventPublisher.publishEvent(new PaymentIntegrityAlertEvent(this,
                PaymentIntegrityAlertEvent.Kind.BOOKING_ACTION_REQUIRED, bookingId.toString(),
                PaymentIntegrityAlertEvent.Reference.BOOKING, "REFUND_AMOUNT_UNKNOWN parent " + parentId));
            return;
        }
        if (spendOnly) {
            refundOutboxSupport.enqueueSpendOnlyBookingRefund(parentId, amount, bookingId, description);
        } else {
            refundOutboxSupport.enqueueBookingRefund(parentId, amount, bookingId, description);
        }
    }

    static boolean isMutualAgreement(String cancelReason) {
        return BookingCancelledByCoachEvent.REASON_MUTUAL_AGREEMENT.equals(cancelReason);
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
