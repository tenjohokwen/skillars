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
 * <p>All {@code BookingCompletedEvent} publish sites
 * ({@code BookingCompletionService.submitWrapUp} LIVE path, {@code confirmCompletion},
 * {@code QuickCompleteTimeoutService.processExpiredQuickCompletes}, and — as of
 * skillars-deferred-153 AC1 — {@code BookingCompletionService.forceCompleteWithPlaceholderData}'s
 * two callers, {@code StuckCompletionSweeperService} and {@code AdminBookingCompletionService})
 * publish inside an active transaction, so {@code BEFORE_COMMIT} + {@code MANDATORY} enqueue is
 * atomic in every case. This is a deliberate extension of existing behavior, not a new policy:
 * {@code processExpiredQuickCompletes} already releases payout on an auto-confirm with no explicit
 * parent confirmation, so a force-completed booking (sweeper- or admin-driven) releasing payout the
 * same way is consistent — but it is real money movement for a session nobody actively confirmed
 * happened, which is why skillars-deferred-153 AC1 discloses it explicitly rather than leaving it an
 * implicit side effect of adding a fourth/fifth publisher here.
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
