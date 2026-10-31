package com.softropic.skillars.platform.payment.service;

import com.softropic.skillars.platform.booking.contract.BookingConfirmedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * feat/security-s06-related-party: evaluates a booking for related-party collusion the moment its payment is confirmed
 * (card, wallet credit or session pack — {@code BookingPaymentPersistenceService} publishes
 * {@code BookingConfirmedEvent} on every path). {@code BEFORE_COMMIT}, so the flag commits with the confirmation and a
 * card fingerprint recorded by the same charge is already visible. An evaluation failure is logged and swallowed: it
 * must never roll back a paid booking; {@link RelatedPartyService#holdPayoutUntil} evaluates again before the payout.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RelatedPartyConfirmationListener {

    private final RelatedPartyService relatedPartyService;

    @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT)
    public void onBookingConfirmed(BookingConfirmedEvent event) {
        try {
            relatedPartyService.evaluate(event.getBookingId(), RelatedPartyService.TRIGGER_CONFIRMATION);
        } catch (RuntimeException e) {
            log.error("[RELATED_PARTY_EVAL_FAILED] booking={} trigger={}", event.getBookingId(),
                RelatedPartyService.TRIGGER_CONFIRMATION, e);
        }
    }
}
