package com.softropic.skillars.platform.payment.service;

import com.softropic.skillars.infrastructure.persistence.PessimisticLockRetryer;
import com.softropic.skillars.platform.payment.contract.CreditLedgerEntryType;
import com.softropic.skillars.platform.payment.contract.exception.PaymentGatewayException;
import com.softropic.skillars.platform.payment.repo.CashOutReconciliationRepository;
import com.softropic.skillars.platform.payment.repo.ChargeDisputeRepository;
import com.softropic.skillars.platform.payment.repo.ParentCreditLedger;
import com.softropic.skillars.platform.payment.repo.ParentCreditLedgerRepository;
import com.softropic.skillars.platform.payment.repo.StripeCustomer;
import com.softropic.skillars.platform.payment.repo.StripeCustomerRepository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * fix/payment-wallet-dispute-integrity: the wallet's spend-only pool (owner decision 2026-10-29 — an expired-pack
 * parent-cancel refund is usable for bookings, never cashed out). Each ledger row records how much of it belongs
 * to that pool ({@code spend_only_amount}); these tests pin where every debit takes it from.
 */
@ExtendWith(MockitoExtension.class)
class CreditWalletServiceSpendOnlyTest {

    private static final Long PARENT = 5L;

    @Mock ParentCreditLedgerRepository ledgerRepository;
    @Mock StripeCustomerRepository stripeCustomerRepository;
    @Mock PessimisticLockRetryer lockRetryer;
    @Mock CashOutReconciliationRepository cashOutReconciliationRepository;
    @Mock ChargeDisputeRepository chargeDisputeRepository;

    private CreditWalletService service;

    @BeforeEach
    void setUp() {
        service = new CreditWalletService(ledgerRepository, stripeCustomerRepository, lockRetryer,
            cashOutReconciliationRepository, chargeDisputeRepository);
        lenient().when(stripeCustomerRepository.findByIdForUpdateWaiting(PARENT))
            .thenReturn(Optional.of(new StripeCustomer()));
    }

    private void wallet(String total, String spendOnly) {
        when(ledgerRepository.sumByParentId(PARENT)).thenReturn(Optional.of(new BigDecimal(total)));
        when(ledgerRepository.sumSpendOnlyByParentId(PARENT)).thenReturn(new BigDecimal(spendOnly));
    }

    private ParentCreditLedger savedRow() {
        ArgumentCaptor<ParentCreditLedger> row = ArgumentCaptor.forClass(ParentCreditLedger.class);
        verify(ledgerRepository).save(row.capture());
        return row.getValue();
    }

    @Test
    void walletBalance_splitsTotalIntoCashableAndSpendOnly_clampedToTheTotal() {
        wallet("100.00", "60.00");
        CreditWalletService.WalletBalance b = service.getWalletBalance(PARENT);
        assertThat(b.total()).isEqualByComparingTo("100.00");
        assertThat(b.spendOnly()).isEqualByComparingTo("60.00");
        assertThat(b.cashable()).isEqualByComparingTo("40.00");
    }

    @Test
    void walletBalance_neverReportsMoreSpendOnlyThanTheWalletHolds() {
        wallet("30.00", "60.00");
        CreditWalletService.WalletBalance b = service.getWalletBalance(PARENT);
        assertThat(b.spendOnly()).isEqualByComparingTo("30.00");
        assertThat(b.cashable()).isEqualByComparingTo("0.00");
    }

    @Test
    void bookingDeduction_consumesTheSpendOnlyPoolFirst() {
        wallet("100.00", "60.00");

        service.debitForBooking(PARENT, new BigDecimal("80.00"), UUID.randomUUID(), "Session booking deduction");

        ParentCreditLedger row = savedRow();
        assertThat(row.getType()).isEqualTo(CreditLedgerEntryType.BOOKING_DEDUCTION);
        assertThat(row.getAmount()).isEqualByComparingTo("-80.00");
        assertThat(row.getSpendOnlyAmount()).isEqualByComparingTo("-60.00");
    }

    @Test
    void failedSettlementRestore_givesBackToThePoolsTheDeductionTookFrom() {
        UUID batch = UUID.randomUUID();
        when(ledgerRepository.sumSpendOnlyByParentIdAndReferenceIdAndType(PARENT, batch,
            CreditLedgerEntryType.BOOKING_DEDUCTION)).thenReturn(new BigDecimal("-60.00"));
        when(ledgerRepository.sumSpendOnlyByParentIdAndReferenceIdAndType(PARENT, batch,
            CreditLedgerEntryType.BOOKING_DEDUCTION_REVERSAL)).thenReturn(BigDecimal.ZERO);

        service.restoreBookingDeduction(PARENT, new BigDecimal("80.00"), batch, "Batch payment failed - credit restored");

        ParentCreditLedger row = savedRow();
        assertThat(row.getAmount()).isEqualByComparingTo("80.00");
        assertThat(row.getSpendOnlyAmount()).isEqualByComparingTo("60.00");
    }

    @Test
    void cashOut_isLimitedToTheCashablePool() {
        when(stripeCustomerRepository.findByIdForUpdate(PARENT)).thenReturn(Optional.of(new StripeCustomer()));
        when(lockRetryer.withBoundedRetry(any(), any())).thenAnswer(inv ->
            ((java.util.function.Supplier<?>) inv.getArgument(1)).get());
        wallet("100.00", "60.00");

        assertThatThrownBy(() -> service.debitForCashOut(PARENT, UUID.randomUUID(), new BigDecimal("47.00"),
            new BigDecimal("3.00"), "pi_1"))
            .isInstanceOf(PaymentGatewayException.class)
            .hasMessageContaining("payment.cashOutExceedsCashable");
        verify(ledgerRepository, never()).save(any());
    }

    @Test
    void cashOut_isBlockedWhileAnAdminDisputeIsOpenOnOneOfThePayersBookings() {
        when(stripeCustomerRepository.findByIdForUpdate(PARENT)).thenReturn(Optional.of(new StripeCustomer()));
        when(lockRetryer.withBoundedRetry(any(), any())).thenAnswer(inv ->
            ((java.util.function.Supplier<?>) inv.getArgument(1)).get());
        when(chargeDisputeRepository.existsOpenAdminDisputeForPayer(PARENT)).thenReturn(true);

        assertThatThrownBy(() -> service.debitForCashOut(PARENT, UUID.randomUUID(), new BigDecimal("10.00"),
            new BigDecimal("1.00"), "pi_1"))
            .isInstanceOf(PaymentGatewayException.class)
            .hasMessageContaining("payment.cashOutBlockedByDispute");
        verify(ledgerRepository, never()).save(any());
    }

    @Test
    void cashOut_withinTheCashablePool_writesCashableDebits() {
        when(stripeCustomerRepository.findByIdForUpdate(PARENT)).thenReturn(Optional.of(new StripeCustomer()));
        when(lockRetryer.withBoundedRetry(any(), any())).thenAnswer(inv ->
            ((java.util.function.Supplier<?>) inv.getArgument(1)).get());
        wallet("100.00", "60.00");

        service.debitForCashOut(PARENT, UUID.randomUUID(), new BigDecimal("37.00"), new BigDecimal("3.00"), "pi_1");

        ArgumentCaptor<ParentCreditLedger> rows = ArgumentCaptor.forClass(ParentCreditLedger.class);
        verify(ledgerRepository, org.mockito.Mockito.times(2)).save(rows.capture());
        assertThat(rows.getAllValues()).allSatisfy(r -> assertThat(r.getSpendOnlyAmount()).isEqualByComparingTo("0"));
    }

    @Test
    void refundReversalOfASpendOnlyRefund_takesFromTheSpendOnlyPoolFirst() {
        wallet("100.00", "30.00");

        assertThat(service.debitRefundReversal(PARENT, UUID.randomUUID(), new BigDecimal("50.00"), true))
            .isEqualByComparingTo("50.00");

        ParentCreditLedger row = savedRow();
        assertThat(row.getAmount()).isEqualByComparingTo("-50.00");
        assertThat(row.getSpendOnlyAmount()).isEqualByComparingTo("-30.00");
    }

    @Test
    void refundReversalOfACashableRefund_takesSpendOnlyCreditOnlyWhenTheCashablePoolRunsOut() {
        wallet("100.00", "70.00");

        service.debitRefundReversal(PARENT, UUID.randomUUID(), new BigDecimal("50.00"), false);

        ParentCreditLedger row = savedRow();
        assertThat(row.getSpendOnlyAmount()).isEqualByComparingTo("-20.00");
    }

    @Test
    void chargebackReversal_takesCashableCreditFirst() {
        UUID reference = UUID.randomUUID();
        wallet("100.00", "80.00");

        service.debitChargebackCredit(PARENT, reference, new BigDecimal("50.00"), "x");

        assertThat(savedRow().getSpendOnlyAmount()).isEqualByComparingTo("-30.00");
    }

    @Test
    void ledgerEntry_rejectsASpendOnlyPartLargerThanTheAmountOrOfTheOtherSign() {
        assertThatThrownBy(() -> service.writeLedgerEntry(PARENT, new BigDecimal("10.00"),
            CreditLedgerEntryType.BOOKING_REFUND, UUID.randomUUID(), "x", null, null, new BigDecimal("11.00")))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.writeLedgerEntry(PARENT, new BigDecimal("-10.00"),
            CreditLedgerEntryType.BOOKING_DEDUCTION, UUID.randomUUID(), "x", null, null, new BigDecimal("5.00")))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
