package com.softropic.skillars.platform.payment.service;

import com.softropic.skillars.infrastructure.persistence.PessimisticLockRetryer;
import com.softropic.skillars.platform.payment.contract.CreditLedgerEntryType;
import com.softropic.skillars.platform.payment.repo.ParentCreditLedger;
import com.softropic.skillars.platform.payment.repo.ParentCreditLedgerRepository;
import com.softropic.skillars.platform.payment.repo.SessionPackPurchase;
import com.softropic.skillars.platform.payment.repo.SessionPackPurchaseRepository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** feat/payment-forfeit-payouts-and-clawback: the payer side of an UPHOLD_COACH dispute resolution. */
@ExtendWith(MockitoExtension.class)
class RefundClawbackServiceTest {

    @Mock ParentCreditLedgerRepository ledgerRepository;
    @Mock CreditWalletService creditWalletService;
    @Mock SessionPackPurchaseRepository sessionPackPurchaseRepository;
    @Mock PackSessionService packSessionService;
    @Mock PessimisticLockRetryer lockRetryer;
    @Mock PackRefundAllocationService packRefundAllocationService;

    private RefundClawbackService service;
    private final UUID bookingId = UUID.randomUUID();
    private final UUID packId = UUID.randomUUID();

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        service = new RefundClawbackService(ledgerRepository, creditWalletService,
            sessionPackPurchaseRepository, packSessionService, packRefundAllocationService, lockRetryer);
        lenient().when(lockRetryer.withBoundedRetry(anyString(), any(Supplier.class)))
            .thenAnswer(inv -> ((Supplier<?>) inv.getArgument(1)).get());
    }

    private ParentCreditLedger refund(String amount) {
        ParentCreditLedger l = new ParentCreditLedger();
        l.setParentId(5L);
        l.setAmount(new BigDecimal(amount));
        l.setType(CreditLedgerEntryType.BOOKING_REFUND);
        l.setReferenceId(bookingId);
        return l;
    }

    private SessionPackPurchase pack(int remaining) {
        SessionPackPurchase p = new SessionPackPurchase();
        p.setAmountCharged(new BigDecimal("100.00"));
        p.setSessionCount(3);
        p.setRemainingSessions(remaining);
        return p;
    }

    private void restoreMarker(boolean present) {
        when(sessionPackPurchaseRepository.existsRestoreMarker(bookingId)).thenReturn(present);
    }

    @Test
    void walletRefund_isDebitedUpToTheBalance() {
        when(ledgerRepository.findFirstByReferenceIdAndType(bookingId, CreditLedgerEntryType.BOOKING_REFUND))
            .thenReturn(Optional.of(refund("40.00")));
        when(creditWalletService.debitRefundReversal(5L, bookingId, new BigDecimal("40.00"), false))
            .thenReturn(new BigDecimal("15.00"));

        RefundClawbackService.RefundClawback result = service.clawBack(bookingId, null).orElseThrow();

        assertThat(result.refunded()).isEqualByComparingTo("40.00");
        assertThat(result.recovered()).isEqualByComparingTo("15.00");
        assertThat(result.absorbed()).isEqualByComparingTo("25.00");
        assertThat(result.packSession()).isFalse();
    }

    /**
     * fix/payment-wallet-dispute-integrity: an admin dispute credit is its own DISPUTE_CREDIT row (V208); an
     * UPHOLD_COACH after it must still find and claw it back (together with any BOOKING_REFUND), and a dispute
     * credit is not a pack refund to un-settle.
     */
    @Test
    void disputeCredits_areClawedBackTogetherWithTheBookingRefund() {
        ParentCreditLedger credit = refund("20.00");
        credit.setType(CreditLedgerEntryType.DISPUTE_CREDIT);
        when(ledgerRepository.findByReferenceIdAndTypeOrderByParentId(bookingId, CreditLedgerEntryType.DISPUTE_CREDIT))
            .thenReturn(java.util.List.of(credit));
        when(creditWalletService.debitRefundReversal(5L, bookingId, new BigDecimal("20.00"), false))
            .thenReturn(new BigDecimal("20.00"));

        RefundClawbackService.RefundClawback result = service.clawBack(bookingId, packId).orElseThrow();

        assertThat(result.refunded()).isEqualByComparingTo("20.00");
        assertThat(result.recovered()).isEqualByComparingTo("20.00");
        verify(packRefundAllocationService, org.mockito.Mockito.never()).releaseSessionRefund(any(), any());
    }

    @Test
    void expiredPackWalletRefund_isUnsettledOnThePack_soThePayoutThatFollowsSettlesItInstead() {
        when(ledgerRepository.findFirstByReferenceIdAndType(bookingId, CreditLedgerEntryType.BOOKING_REFUND))
            .thenReturn(Optional.of(refund("33.34")));
        when(creditWalletService.debitRefundReversal(5L, bookingId, new BigDecimal("33.34"), false))
            .thenReturn(new BigDecimal("33.34"));

        service.clawBack(bookingId, packId).orElseThrow();

        verify(packRefundAllocationService).releaseSessionRefund(packId, bookingId);
    }

    @Test
    void alreadyReversed_findsNothing() {
        when(ledgerRepository.existsByReferenceIdAndType(bookingId, CreditLedgerEntryType.BOOKING_REFUND_REVERSAL))
            .thenReturn(true);
        assertThat(service.clawBack(bookingId, null)).isEmpty();
        verifyNoInteractions(creditWalletService);
    }

    @Test
    void noRefundAtAll_findsNothing() {
        when(ledgerRepository.findFirstByReferenceIdAndType(bookingId, CreditLedgerEntryType.BOOKING_REFUND))
            .thenReturn(Optional.empty());
        assertThat(service.clawBack(bookingId, null)).isEmpty();
    }

    @Test
    void restoredPackSession_isDeductedAgain() {
        when(ledgerRepository.findFirstByReferenceIdAndType(bookingId, CreditLedgerEntryType.BOOKING_REFUND))
            .thenReturn(Optional.empty());
        restoreMarker(true);
        SessionPackPurchase pack = pack(2);
        when(sessionPackPurchaseRepository.findByIdForUpdate(packId)).thenReturn(Optional.of(pack));
        when(packSessionService.deductSessionForClawback(pack)).thenReturn(true);

        RefundClawbackService.RefundClawback result = service.clawBack(bookingId, packId).orElseThrow();

        // fix/payment-pack-integrity: the clawback path, never deductSession (whose pause guard threw).
        verify(packSessionService).deductSessionForClawback(pack);
        verify(packSessionService, never()).deductSession(any());
        assertThat(result.sessionRededucted()).isTrue();
        assertThat(result.refunded()).isEqualByComparingTo("33.33");
        assertThat(result.absorbed()).isEqualByComparingTo("0.00");
    }

    @Test
    void restoredPackSession_withNoneLeft_isAbsorbed() {
        when(ledgerRepository.findFirstByReferenceIdAndType(bookingId, CreditLedgerEntryType.BOOKING_REFUND))
            .thenReturn(Optional.empty());
        restoreMarker(true);
        SessionPackPurchase pack = pack(0);
        when(sessionPackPurchaseRepository.findByIdForUpdate(packId)).thenReturn(Optional.of(pack));
        when(packSessionService.deductSessionForClawback(pack)).thenReturn(false);

        RefundClawbackService.RefundClawback result = service.clawBack(bookingId, packId).orElseThrow();

        verify(packSessionService, never()).deductSession(any());
        assertThat(result.sessionRededucted()).isFalse();
        assertThat(result.absorbed()).isEqualByComparingTo("33.33");
    }

    @Test
    void packBookingThatWasNeverRestored_findsNothing() {
        when(ledgerRepository.findFirstByReferenceIdAndType(bookingId, CreditLedgerEntryType.BOOKING_REFUND))
            .thenReturn(Optional.empty());
        restoreMarker(false);
        // A forfeited parent cancellation keeps its session consumed: nothing to claw back.
        assertThat(service.clawBack(bookingId, packId)).isEmpty();
        verifyNoInteractions(packSessionService);
    }
}
