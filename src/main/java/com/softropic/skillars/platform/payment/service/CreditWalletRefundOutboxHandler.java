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
        if (ledgerRepository.existsByReferenceIdAndType(p.bookingId(), CreditLedgerEntryType.BOOKING_REFUND)) {
            log.info("[CREDIT_WALLET_REFUND] BOOKING_REFUND already present for booking {} — re-drive is a no-op",
                p.bookingId());
            return;
        }
        creditWalletService.writeLedgerEntry(p.parentId(), p.amount(), CreditLedgerEntryType.BOOKING_REFUND,
            p.bookingId(), p.description(), p.sourcePaymentIntentId(), p.sourceCardAmount());
        // fix/payment-dispute-credit-trace-and-lost-dispute: credit funded by a charge that already lost a
        // card dispute is taken back at once (the bank refunded the payer).
        chargebackCreditReversalService.onRefundCredited(p.bookingId(), p.sourcePaymentIntentId());
        log.info("[CREDIT_WALLET_REFUND] re-driven BOOKING_REFUND for booking {} parentId {} amount {}",
            p.bookingId(), p.parentId(), p.amount());
    }
}
