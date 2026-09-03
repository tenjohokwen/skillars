package com.softropic.skillars.platform.payment.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.softropic.skillars.platform.outbox.contract.OutboxMessageHandler;
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
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CreditWalletRefundOutboxHandler implements OutboxMessageHandler {

    private final CreditWalletService creditWalletService;
    private final ParentCreditLedgerRepository ledgerRepository;
    private final ObjectMapper objectMapper;

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
        if (ledgerRepository.existsByReferenceIdAndType(p.bookingId(), "BOOKING_REFUND")) {
            log.info("[CREDIT_WALLET_REFUND] BOOKING_REFUND already present for booking {} — re-drive is a no-op",
                p.bookingId());
            return;
        }
        creditWalletService.writeLedgerEntry(p.parentId(), p.amount(), "BOOKING_REFUND",
            p.bookingId(), p.description());
        log.info("[CREDIT_WALLET_REFUND] re-driven BOOKING_REFUND for booking {} parentId {} amount {}",
            p.bookingId(), p.parentId(), p.amount());
    }
}
