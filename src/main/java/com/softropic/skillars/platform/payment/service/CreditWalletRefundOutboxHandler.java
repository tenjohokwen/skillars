package com.softropic.skillars.platform.payment.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.softropic.skillars.platform.outbox.contract.OutboxMessageHandler;
import com.softropic.skillars.platform.payment.contract.CreditLedgerEntryType;
import com.softropic.skillars.platform.payment.repo.ParentCreditLedgerRepository;
import com.softropic.skillars.platform.payment.service.RefundOutboxSupport.BookingRefundPayload;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.UncheckedIOException;
import java.math.BigDecimal;

/**
 * skillars-deferred-91 AC2: re-drives a credit-wallet {@code BOOKING_REFUND} enqueued by
 * {@link RefundOutboxSupport}. Idempotent: it no-ops if a {@code BOOKING_REFUND} ledger row already
 * exists for the booking (the first, actually-committed attempt), and the
 * {@code uq_pcl_booking_refund} partial unique index (V127) backstops the concurrent case.
 *
 * <p>fix/payment-dispute-credit-trace-and-lost-dispute: records the source charge the payload names (V199)
 * and hands the new credit to {@link ChargebackCreditReversalService#onRefundCredited}.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CreditWalletRefundOutboxHandler implements OutboxMessageHandler {

    private final CreditWalletService creditWalletService;
    private final ParentCreditLedgerRepository ledgerRepository;
    private final ObjectMapper objectMapper;
    private final ChargebackCreditReversalService chargebackCreditReversalService;

    @Override
    public String aggregateType() {
        return RefundOutboxSupport.AGGREGATE_TYPE;
    }

    @Override
    public void handle(String payload) {
        final BookingRefundPayload p;
        try {
            p = objectMapper.readValue(payload, BookingRefundPayload.class);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new UncheckedIOException(e);
        }
        // fix/payment-wallet-dispute-integrity: one row per booking per refund kind — a batch-share refund
        // (BATCH_SHARE_REFUND) and the booking's own cancellation refund (BOOKING_REFUND) no longer collide.
        String type = p.ledgerType();
        if (ledgerRepository.existsByReferenceIdAndType(p.bookingId(), type)) {
            log.info("[CREDIT_WALLET_REFUND] {} already present for booking {} — re-drive is a no-op",
                type, p.bookingId());
            return;
        }
        if (p.amount() == null || p.amount().signum() <= 0) {
            // Never enqueued since fix/payment-wallet-dispute-integrity (RefundEnqueueListener alerts instead); an
            // older 0.00 row would otherwise fail on every drain forever.
            log.error("[CREDIT_WALLET_REFUND] booking {} payload amount {} is not positive — dropped, reconcile by hand",
                p.bookingId(), p.amount());
            return;
        }
        // fix/payment-wallet-dispute-integrity: a spend-only refund puts the whole amount in the spend-only pool.
        // fix/audit3-wallet-cashout-integrity: any other cancellation refund gives back to the spend-only pool the
        // share the booking's own deduction took from it (computed here, under the payer's wallet lock, at write
        // time) — the rest (the card-charged / cashable part) stays cashable. A batch-share refund is card money
        // for a booking whose credit share was never debited, so it stays cashable.
        BigDecimal spendOnly;
        if (p.spendOnly()) {
            spendOnly = p.amount();
        } else if (CreditLedgerEntryType.BOOKING_REFUND.equals(type)) {
            spendOnly = creditWalletService.bookingSpendOnlyOutstanding(p.parentId(), p.bookingId()).min(p.amount());
        } else {
            spendOnly = BigDecimal.ZERO;
        }
        creditWalletService.writeLedgerEntry(p.parentId(), p.amount(), type, p.bookingId(), p.description(),
            p.sourcePaymentIntentId(), p.sourceCardAmount(), spendOnly);
        // fix/payment-dispute-credit-trace-and-lost-dispute: credit funded by a charge that already lost a
        // card dispute is taken back at once (the bank refunded the payer).
        chargebackCreditReversalService.onRefundCredited(p.bookingId(), p.sourcePaymentIntentId());
        log.info("[CREDIT_WALLET_REFUND] re-driven BOOKING_REFUND for booking {} parentId {} amount {}",
            p.bookingId(), p.parentId(), p.amount());
    }
}
