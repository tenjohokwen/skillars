package com.softropic.skillars.platform.payment.service;

import com.softropic.skillars.platform.booking.contract.CoachNoShowEvent;
import com.softropic.skillars.platform.payment.contract.event.PaymentIntegrityAlertEvent;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.math.BigDecimal;

/**
 * S-07 (owner decision 2026-10-31, option B): settles the conflict zone — the coach quick-completed WITHOUT a live
 * Start and the booker reports a coach no-show, so neither side is established. The fee is split 50/50 and the
 * coach is not struck ({@code CancellationRefundService.onCoachNoShow} skips a split).
 *
 * <p>Both halves are written in the reporting transaction ({@code BEFORE_COMMIT}, like
 * {@link RefundEnqueueListener} and {@link CoachPayoutEnqueueListener}, which both ignore a split): the coach's
 * half through the ordinary payout row ({@link CoachPayoutOutboxSupport#enqueueSplitPayout} — commission on the
 * retained half, the normal and new-coach release holds), the payer's half as a wallet refund. One listener, so the
 * two halves are computed from one number and cannot drift.
 *
 * <p>A pack session is CONSUMED (never restored): only the monetary half goes back, as booking-only credit
 * (spend-only, like every other pack-money refund that is not an outright coach fault), and the coach is paid
 * their half of the session's ledger share.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CoachNoShowSplitListener {

    private final CoachPayoutOutboxSupport coachPayoutOutboxSupport;
    private final RefundOutboxSupport refundOutboxSupport;
    private final ApplicationEventPublisher eventPublisher;

    @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT)
    public void onCoachNoShow(CoachNoShowEvent event) {
        if (!event.isCoachSplit()) {
            return;
        }
        BigDecimal refund = coachPayoutOutboxSupport.enqueueSplitPayout(
            event.getBookingId(), event.getCoachId(), event.getSessionPrice());
        if (refund == null || refund.signum() <= 0) {
            log.error("[NO_SHOW_SPLIT] booking={} parent={} — the payer's half is {} (nothing charged, or the pack "
                + "session could not be allocated); nothing refunded, admin alert raised",
                event.getBookingId(), event.getParentId(), refund);
            eventPublisher.publishEvent(new PaymentIntegrityAlertEvent(this,
                PaymentIntegrityAlertEvent.Kind.BOOKING_ACTION_REQUIRED, event.getBookingId().toString(),
                PaymentIntegrityAlertEvent.Reference.BOOKING, "NO_SHOW_SPLIT_REFUND_UNKNOWN parent " + event.getParentId()));
            return;
        }
        String description = "Coach no-show — session fee shared 50/50";
        if (event.getSessionPackPurchaseId() != null) {
            refundOutboxSupport.enqueueSpendOnlyBookingRefund(event.getParentId(), refund, event.getBookingId(),
                description + " (booking credit only)");
        } else {
            refundOutboxSupport.enqueueBookingRefund(event.getParentId(), refund, event.getBookingId(), description);
        }
        log.info("[NO_SHOW_SPLIT] booking={} refunded {} to parent {}, remainder to coach {}",
            event.getBookingId(), refund, event.getParentId(), event.getCoachId());
    }
}
