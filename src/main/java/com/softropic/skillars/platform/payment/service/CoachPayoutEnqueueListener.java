package com.softropic.skillars.platform.payment.service;

import com.softropic.skillars.platform.booking.contract.BookingCompletedEvent;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * skillars-deferred-106 AC4.1: releases the coach's payout on {@code BookingCompletedEvent}, through
 * the durable outbox. Mirrors {@code RefundEnqueueListener}: a {@code BEFORE_COMMIT} listener on the
 * publishing (completion) transaction, so the {@code coach_payouts} row and the
 * {@code COACH_PAYOUT_TRANSFER} outbox message are written atomically with the booking's completion —
 * a failed enqueue rolls the completion back to the caller rather than silently losing a payout.
 *
 * <p>All three {@code BookingCompletedEvent} publish sites
 * ({@code BookingCompletionService.submitWrapUp} LIVE path, {@code confirmCompletion}, and
 * {@code QuickCompleteTimeoutService.processExpiredQuickCompletes}) publish inside an active
 * transaction, so {@code BEFORE_COMMIT} + {@code MANDATORY} enqueue is atomic in every case
 * (verified during skillars-deferred-106 impl — no fourth publisher, no out-of-transaction path).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CoachPayoutEnqueueListener {

    private final CoachPayoutOutboxSupport coachPayoutOutboxSupport;

    @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT)
    public void onBookingCompleted(BookingCompletedEvent event) {
        coachPayoutOutboxSupport.enqueuePayout(event.getBookingId(), event.getCoachId());
    }
}
