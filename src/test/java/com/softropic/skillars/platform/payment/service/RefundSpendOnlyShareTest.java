package com.softropic.skillars.platform.payment.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.softropic.skillars.platform.payment.contract.CreditLedgerEntryType;
import com.softropic.skillars.platform.payment.repo.ParentCreditLedgerRepository;
import com.softropic.skillars.platform.payment.service.RefundOutboxSupport.BookingRefundPayload;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * fix/audit3-wallet-cashout-integrity: a cancellation refund and an admin dispute credit give back to the
 * spend-only pool the share the booking's own deduction took from it — they used to be written all cashable,
 * so spend-only credit (an expired-pack refund) spent on a booking came back cashable when the booking was
 * cancelled and could be cashed out to a card.
 */
@ExtendWith(MockitoExtension.class)
class RefundSpendOnlyShareTest {

    private static final Long PARENT = 7L;
    private static final UUID BOOKING = UUID.randomUUID();

    @Mock CreditWalletService creditWalletService;
    @Mock ParentCreditLedgerRepository ledgerRepository;
    @Mock ChargebackCreditReversalService chargebackCreditReversalService;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private CreditWalletRefundOutboxHandler handler;
    private DisputeCreditService disputeCreditService;

    @BeforeEach
    void setUp() {
        handler = new CreditWalletRefundOutboxHandler(creditWalletService, ledgerRepository, objectMapper,
            chargebackCreditReversalService);
        disputeCreditService = new DisputeCreditService(ledgerRepository, creditWalletService,
            chargebackCreditReversalService);
    }

    private String payload(BookingRefundPayload p) throws Exception {
        return objectMapper.writeValueAsString(p);
    }

    /** The exploit: €40 spend-only spent on a €40 booking, cancelled >24h — the refund is €40 spend-only. */
    @Test
    void bookingRefund_ofASpendOnlyPaidBooking_isSpendOnly() throws Exception {
        when(creditWalletService.bookingSpendOnlyOutstanding(PARENT, BOOKING)).thenReturn(new BigDecimal("40.00"));

        handler.handle(payload(new BookingRefundPayload(PARENT, new BigDecimal("40.00"), BOOKING, "refund")));

        verify(creditWalletService).writeLedgerEntry(eq(PARENT), eq(new BigDecimal("40.00")),
            eq(CreditLedgerEntryType.BOOKING_REFUND), eq(BOOKING), anyString(), isNull(), isNull(),
            eq(new BigDecimal("40.00")));
    }

    /**
     * Lock order (deadlock 40P01 otherwise): the card-dispute rows are locked before the wallet lock that
     * bookingSpendOnlyOutstanding takes — the order ChargeDisputeWebhookService uses.
     */
    @Test
    void bookingRefund_locksTheDisputeRowsBeforeTheWallet() throws Exception {
        when(creditWalletService.bookingSpendOnlyOutstanding(PARENT, BOOKING)).thenReturn(BigDecimal.ZERO);

        handler.handle(payload(new BookingRefundPayload(PARENT, new BigDecimal("40.00"), BOOKING, "refund")));

        org.mockito.InOrder order = org.mockito.Mockito.inOrder(chargebackCreditReversalService, creditWalletService);
        order.verify(chargebackCreditReversalService).lockDisputesForCredit(BOOKING, null);
        order.verify(creditWalletService).bookingSpendOnlyOutstanding(PARENT, BOOKING);
        order.verify(chargebackCreditReversalService).onRefundCredited(BOOKING, null);
    }

    @Test
    void disputeCredit_locksTheDisputeRowsBeforeTheWallet() {
        UUID dispute = UUID.randomUUID();
        when(creditWalletService.bookingSpendOnlyOutstanding(PARENT, BOOKING)).thenReturn(BigDecimal.ZERO);

        disputeCreditService.credit(PARENT, BOOKING, dispute, new BigDecimal("10.00"), "credit");

        org.mockito.InOrder order = org.mockito.Mockito.inOrder(chargebackCreditReversalService, creditWalletService);
        order.verify(chargebackCreditReversalService).lockDisputesForCredit(BOOKING, null);
        order.verify(creditWalletService).bookingSpendOnlyOutstanding(PARENT, BOOKING);
        order.verify(chargebackCreditReversalService).onCreditWritten(dispute, BOOKING, null);
    }

    /** A mixed booking (spend-only credit + card): only the spend-only share stays spend-only. */
    @Test
    void bookingRefund_mixedBooking_onlyTheSpendOnlyShareIsSpendOnly() throws Exception {
        when(creditWalletService.bookingSpendOnlyOutstanding(PARENT, BOOKING)).thenReturn(new BigDecimal("15.00"));

        handler.handle(payload(new BookingRefundPayload(PARENT, new BigDecimal("50.00"), BOOKING, "refund")));

        verify(creditWalletService).writeLedgerEntry(eq(PARENT), eq(new BigDecimal("50.00")),
            eq(CreditLedgerEntryType.BOOKING_REFUND), eq(BOOKING), anyString(), isNull(), isNull(),
            eq(new BigDecimal("15.00")));
    }

    @Test
    void bookingRefund_spendOnlyShareNeverAboveTheRefund() throws Exception {
        when(creditWalletService.bookingSpendOnlyOutstanding(PARENT, BOOKING)).thenReturn(new BigDecimal("80.00"));

        handler.handle(payload(new BookingRefundPayload(PARENT, new BigDecimal("50.00"), BOOKING, "refund")));

        verify(creditWalletService).writeLedgerEntry(eq(PARENT), eq(new BigDecimal("50.00")), anyString(), eq(BOOKING),
            anyString(), isNull(), isNull(), eq(new BigDecimal("50.00")));
    }

    /** The expired-pack refund stays wholly spend-only (settled policy). */
    @Test
    void spendOnlyPayload_isWhollySpendOnly() throws Exception {
        handler.handle(payload(new BookingRefundPayload(PARENT, new BigDecimal("33.33"), BOOKING, "expired pack",
            null, null, null, true)));

        verify(creditWalletService, never()).bookingSpendOnlyOutstanding(any(), any());
        verify(creditWalletService).writeLedgerEntry(eq(PARENT), eq(new BigDecimal("33.33")), anyString(), eq(BOOKING),
            anyString(), isNull(), isNull(), eq(new BigDecimal("33.33")));
    }

    /** A batch-share refund is card money for a booking whose credit share was never debited — cashable. */
    @Test
    void batchShareRefund_staysCashable() throws Exception {
        handler.handle(payload(new BookingRefundPayload(PARENT, new BigDecimal("20.00"), BOOKING, "share",
            "pi_1", new BigDecimal("20.00"), CreditLedgerEntryType.BATCH_SHARE_REFUND, false)));

        verify(creditWalletService, never()).bookingSpendOnlyOutstanding(any(), any());
        verify(creditWalletService).writeLedgerEntry(eq(PARENT), eq(new BigDecimal("20.00")),
            eq(CreditLedgerEntryType.BATCH_SHARE_REFUND), eq(BOOKING), anyString(), eq("pi_1"),
            eq(new BigDecimal("20.00")), eq(BigDecimal.ZERO));
    }

    /** An admin dispute credit is no more cashable than what the booking was paid from cashable funds. */
    @Test
    void disputeCredit_returnsTheSpendOnlyShareFirst() {
        UUID dispute = UUID.randomUUID();
        when(creditWalletService.bookingSpendOnlyOutstanding(PARENT, BOOKING)).thenReturn(new BigDecimal("40.00"));

        disputeCreditService.credit(PARENT, BOOKING, dispute, new BigDecimal("25.00"), "credit");

        verify(creditWalletService).writeLedgerEntry(eq(PARENT), eq(new BigDecimal("25.00")),
            eq(CreditLedgerEntryType.DISPUTE_CREDIT), eq(BOOKING), anyString(), isNull(), isNull(),
            eq(new BigDecimal("25.00")));
        verify(chargebackCreditReversalService).onCreditWritten(dispute, BOOKING, null);
    }

    @Test
    void disputeCredit_cashablePaidBooking_staysCashable() {
        when(creditWalletService.bookingSpendOnlyOutstanding(PARENT, BOOKING)).thenReturn(BigDecimal.ZERO);

        disputeCreditService.credit(PARENT, BOOKING, UUID.randomUUID(), new BigDecimal("25.00"), "credit");

        verify(creditWalletService).writeLedgerEntry(eq(PARENT), eq(new BigDecimal("25.00")),
            eq(CreditLedgerEntryType.DISPUTE_CREDIT), eq(BOOKING), anyString(), isNull(), isNull(),
            eq(BigDecimal.ZERO));
    }
}
