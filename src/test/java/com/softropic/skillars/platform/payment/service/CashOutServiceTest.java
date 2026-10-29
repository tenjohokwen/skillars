package com.softropic.skillars.platform.payment.service;

import com.softropic.skillars.platform.config.service.ConfigService;
import com.softropic.skillars.platform.payment.contract.PaymentGateway;
import com.softropic.skillars.platform.payment.contract.exception.PaymentGatewayException;
import com.softropic.skillars.platform.payment.repo.ParentCreditLedgerRepository;
import com.softropic.skillars.platform.payment.repo.StripeCustomer;
import com.softropic.skillars.platform.payment.repo.StripeCustomerRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CashOutServiceTest {

    @Mock CreditWalletService creditWalletService;
    @Mock StripeCustomerRepository stripeCustomerRepository;
    @Mock PaymentGateway paymentGateway;
    @Mock ConfigService configService;
    @Mock CashOutReconciliationService cashOutReconciliationService;
    @Mock ParentCreditLedgerRepository ledgerRepository;

    @InjectMocks CashOutService cashOutService;

    private static final Long PARENT_ID = 5001L;
    private static final String PAYMENT_INTENT_ID = "pi_test_cashout_intent";
    private static final BigDecimal AMOUNT = new BigDecimal("100.00");
    // fee = 100 * 0.025 + 0.25 = 2.75; net = 100 - 2.75 = 97.25
    private static final BigDecimal FEE = new BigDecimal("2.75");
    private static final BigDecimal NET = new BigDecimal("97.25");

    /** A wallet whose credit is all cashable. */
    private static CreditWalletService.WalletBalance cashable(BigDecimal total) {
        return new CreditWalletService.WalletBalance(total, BigDecimal.ZERO, total);
    }

    private void stubFeeConfig() {
        lenient().when(configService.getString("payment.stripe.feeRate")).thenReturn("0.025");
        lenient().when(configService.getString("payment.stripe.feeFixed")).thenReturn("0.25");
    }

    private void stubCustomer() {
        StripeCustomer customer = new StripeCustomer();
        customer.setParentId(PARENT_ID);
        customer.setLastPaymentIntentId(PAYMENT_INTENT_ID);
        when(stripeCustomerRepository.findById(PARENT_ID)).thenReturn(Optional.of(customer));
    }

    private void stubHappyPath(BigDecimal balance) {
        stubFeeConfig();
        when(creditWalletService.getWalletBalance(PARENT_ID)).thenReturn(cashable(balance));
        stubCustomer();
        // lenient: not read when a funding charge is the refund target
        lenient().when(paymentGateway.getRefundableAmount(PAYMENT_INTENT_ID)).thenReturn(new BigDecimal("500.00"));
    }

    @Test
    void processCashOut_debitsExactlyTheRequestedAmount_andRefundsTheNet() {
        stubHappyPath(new BigDecimal("200.00"));

        cashOutService.processCashOut(PARENT_ID, AMOUNT);

        // The wallet loses exactly what was asked for (net + fee = 100.00), not requested + fee.
        verify(creditWalletService).debitForCashOut(eq(PARENT_ID), any(UUID.class), eq(NET), eq(FEE), eq(PAYMENT_INTENT_ID));
        assertThat(NET.add(FEE)).isEqualByComparingTo(AMOUNT);
        verify(paymentGateway).refund(eq(PAYMENT_INTENT_ID), eq(NET), anyString());
    }

    @Test
    void processCashOut_amountEqualsBalance_succeedsWithoutOverdrawing() {
        stubHappyPath(AMOUNT);

        cashOutService.processCashOut(PARENT_ID, AMOUNT);

        verify(creditWalletService).debitForCashOut(eq(PARENT_ID), any(UUID.class), eq(NET), eq(FEE), eq(PAYMENT_INTENT_ID));
        verify(paymentGateway).refund(eq(PAYMENT_INTENT_ID), eq(NET), anyString());
    }

    @Test
    void processCashOut_idempotencyKeyIsPerCashOut_notPerPaymentIntent() {
        stubHappyPath(new BigDecimal("500.00"));

        cashOutService.processCashOut(PARENT_ID, AMOUNT);
        cashOutService.processCashOut(PARENT_ID, AMOUNT);

        ArgumentCaptor<String> keys = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<UUID> cashOutIds = ArgumentCaptor.forClass(UUID.class);
        verify(paymentGateway, times(2)).refund(eq(PAYMENT_INTENT_ID), eq(NET), keys.capture());
        verify(creditWalletService, times(2)).debitForCashOut(eq(PARENT_ID), cashOutIds.capture(), eq(NET), eq(FEE), eq(PAYMENT_INTENT_ID));
        List<String> k = keys.getAllValues();
        assertThat(k).doesNotHaveDuplicates()
            .noneMatch(key -> key.contains(PAYMENT_INTENT_ID))
            .containsExactly("cashout-" + cashOutIds.getAllValues().get(0),
                "cashout-" + cashOutIds.getAllValues().get(1));
    }

    @Test
    void processCashOut_netAboveRefundableOnLastPaymentIntent_rejectedBeforeAnyDebit() {
        stubFeeConfig();
        when(creditWalletService.getWalletBalance(PARENT_ID)).thenReturn(cashable(new BigDecimal("200.00")));
        stubCustomer();
        when(paymentGateway.getRefundableAmount(PAYMENT_INTENT_ID)).thenReturn(new BigDecimal("50.00"));

        assertThatThrownBy(() -> cashOutService.processCashOut(PARENT_ID, AMOUNT))
            .isInstanceOf(PaymentGatewayException.class)
            .hasMessageContaining("payment.cashOutExceedsRefundable");

        verify(creditWalletService, never()).debitForCashOut(any(), any(), any(), any(), any());
        verify(paymentGateway, never()).refund(any(), any(), any());
    }

    @Test
    void processCashOut_insufficientBalance_throwsException() {
        stubFeeConfig();
        when(creditWalletService.getWalletBalance(PARENT_ID)).thenReturn(cashable(new BigDecimal("50.00")));

        assertThatThrownBy(() -> cashOutService.processCashOut(PARENT_ID, AMOUNT))
            .isInstanceOf(PaymentGatewayException.class)
            .hasMessageContaining("payment.insufficientCredit");

        verify(creditWalletService, never()).debitForCashOut(any(), any(), any(), any(), any());
        verify(paymentGateway, never()).refund(any(), any(), any());
    }

    @Test
    void processCashOut_lockedCheckFails_noRefund() {
        stubHappyPath(new BigDecimal("200.00"));
        doThrow(new PaymentGatewayException("payment.insufficientCredit"))
            .when(creditWalletService).debitForCashOut(eq(PARENT_ID), any(UUID.class), any(), any(), any());

        assertThatThrownBy(() -> cashOutService.processCashOut(PARENT_ID, AMOUNT))
            .isInstanceOf(PaymentGatewayException.class)
            .hasMessageContaining("payment.insufficientCredit");

        verify(paymentGateway, never()).refund(any(), any(), any());
    }

    @Test
    void processCashOut_noStripeCustomer_throwsException() {
        stubFeeConfig();
        when(creditWalletService.getWalletBalance(PARENT_ID)).thenReturn(cashable(new BigDecimal("200.00")));
        when(stripeCustomerRepository.findById(PARENT_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> cashOutService.processCashOut(PARENT_ID, AMOUNT))
            .isInstanceOf(PaymentGatewayException.class)
            .hasMessageContaining("payment.noPaymentMethod");

        verify(paymentGateway, never()).refund(any(), any(), any());
    }

    @Test
    void processCashOut_tooSmall_rejected() {
        stubFeeConfig();

        assertThatThrownBy(() -> cashOutService.processCashOut(PARENT_ID, new BigDecimal("0.25")))
            .isInstanceOf(PaymentGatewayException.class)
            .hasMessageContaining("payment.cashOutTooSmall");
    }

    @Test
    void processCashOut_stripeRefundFails_writesReversalOfNetAndFeeAndRethrows() {
        stubHappyPath(new BigDecimal("200.00"));
        doThrow(new PaymentGatewayException("payment.refundFailed"))
            .when(paymentGateway).refund(any(), any(), any());

        assertThatThrownBy(() -> cashOutService.processCashOut(PARENT_ID, AMOUNT))
            .isInstanceOf(PaymentGatewayException.class)
            .hasMessageContaining("payment.refundFailed");

        ArgumentCaptor<UUID> cashOutId = ArgumentCaptor.forClass(UUID.class);
        verify(creditWalletService).debitForCashOut(eq(PARENT_ID), cashOutId.capture(), eq(NET), eq(FEE), eq(PAYMENT_INTENT_ID));
        // Reversal restores what was debited, together with closing the record (exactly-once).
        verify(cashOutReconciliationService).reverseAfterRejection(eq(cashOutId.getValue()), anyString());
        verify(cashOutReconciliationService, never()).markCompleted(any());
    }

    @Test
    void processCashOut_ambiguousRefundOutcome_retriesUnderTheSameKey_thenSucceeds() {
        stubHappyPath(new BigDecimal("200.00"));
        doThrow(new PaymentGatewayException("payment.refundOutcomeUnknown"))
            .doNothing()
            .when(paymentGateway).refund(any(), any(), any());

        cashOutService.processCashOut(PARENT_ID, AMOUNT);

        ArgumentCaptor<String> keys = ArgumentCaptor.forClass(String.class);
        verify(paymentGateway, times(2)).refund(eq(PAYMENT_INTENT_ID), eq(NET), keys.capture());
        assertThat(keys.getAllValues()).hasSize(2).containsOnly(keys.getAllValues().get(0));
        verify(cashOutReconciliationService, never()).reverseAfterRejection(any(), any());
        verify(cashOutReconciliationService).markCompleted(any(UUID.class));
        verify(cashOutReconciliationService, never()).markUnknown(any(), any());
    }

    @Test
    void processCashOut_refundOutcomeStaysUnknown_neverRestoresCredit() {
        // The refund may have reached the card: restoring credit could pay the payer twice.
        stubHappyPath(new BigDecimal("200.00"));
        doThrow(new PaymentGatewayException("payment.refundOutcomeUnknown"))
            .when(paymentGateway).refund(any(), any(), any());

        assertThatThrownBy(() -> cashOutService.processCashOut(PARENT_ID, AMOUNT))
            .isInstanceOf(PaymentGatewayException.class)
            .hasMessageContaining("payment.refundOutcomeUnknown");

        verify(paymentGateway, times(CashOutService.MAX_REFUND_ATTEMPTS)).refund(any(), any(), any());
        verify(cashOutReconciliationService, never()).reverseAfterRejection(any(), any());
        // The durable record goes UNKNOWN (admin alert + automatic re-check) instead of just a log line.
        verify(cashOutReconciliationService).markUnknown(any(UUID.class), eq("payment.refundOutcomeUnknown"));
    }

    @Test
    void processCashOut_refundThrowsUnexpectedly_treatedAsUnknown_noReversal() {
        stubHappyPath(new BigDecimal("200.00"));
        doThrow(new IllegalStateException("boom")).when(paymentGateway).refund(any(), any(), any());

        assertThatThrownBy(() -> cashOutService.processCashOut(PARENT_ID, AMOUNT))
            .isInstanceOf(PaymentGatewayException.class)
            .hasMessageContaining("payment.refundOutcomeUnknown");

        verify(cashOutReconciliationService, never()).reverseAfterRejection(any(), any());
        verify(cashOutReconciliationService).markUnknown(any(UUID.class), anyString());
    }

    @Test
    void processCashOut_success_closesTheReconciliationRecord() {
        stubHappyPath(new BigDecimal("200.00"));

        cashOutService.processCashOut(PARENT_ID, AMOUNT);

        ArgumentCaptor<UUID> cashOutId = ArgumentCaptor.forClass(UUID.class);
        verify(creditWalletService).debitForCashOut(eq(PARENT_ID), cashOutId.capture(), eq(NET), eq(FEE),
            eq(PAYMENT_INTENT_ID));
        verify(cashOutReconciliationService).markCompleted(cashOutId.getValue());
    }

    @Test
    void processCashOut_refundSucceededButRecordCloseFails_stillSucceeds_jobConfirmsLater() {
        stubHappyPath(new BigDecimal("200.00"));
        doThrow(new IllegalStateException("db down")).when(cashOutReconciliationService).markCompleted(any());

        cashOutService.processCashOut(PARENT_ID, AMOUNT);

        verify(paymentGateway, times(1)).refund(any(), any(), any());
        verify(cashOutReconciliationService, never()).reverseAfterRejection(any(), any());
    }

    // ── fix/payment-wallet-dispute-integrity ─────────────────────────────────────────────

    @Test
    void processCashOut_refundsOntoTheChargeThatFundedTheCredit_notTheLatestCharge() {
        stubHappyPath(new BigDecimal("200.00"));
        when(ledgerRepository.findCashOutRefundTargets(PARENT_ID, CashOutService.MAX_REFUND_TARGET_CANDIDATES))
            .thenReturn(List.of("pi_funding"));
        when(paymentGateway.getRefundableAmount("pi_funding")).thenReturn(new BigDecimal("100.00"));

        cashOutService.processCashOut(PARENT_ID, AMOUNT);

        verify(creditWalletService).debitForCashOut(eq(PARENT_ID), any(UUID.class), eq(NET), eq(FEE), eq("pi_funding"));
        verify(paymentGateway).refund(eq("pi_funding"), eq(NET), anyString());
        verify(paymentGateway, never()).refund(eq(PAYMENT_INTENT_ID), any(), any());
    }

    @Test
    void processCashOut_fundingChargeWithoutHeadroom_fallsBackToTheLatestCharge() {
        stubHappyPath(new BigDecimal("200.00"));
        when(ledgerRepository.findCashOutRefundTargets(PARENT_ID, CashOutService.MAX_REFUND_TARGET_CANDIDATES))
            .thenReturn(List.of("pi_small", "pi_unreadable"));
        when(paymentGateway.getRefundableAmount("pi_small")).thenReturn(new BigDecimal("10.00"));
        when(paymentGateway.getRefundableAmount("pi_unreadable")).thenThrow(new PaymentGatewayException("payment.stripeError"));

        cashOutService.processCashOut(PARENT_ID, AMOUNT);

        verify(paymentGateway).refund(eq(PAYMENT_INTENT_ID), eq(NET), anyString());
    }

    @Test
    void processCashOut_aboveTheCashablePart_isRefused_spendOnlyCreditNeverLeaves() {
        stubFeeConfig();
        when(creditWalletService.getWalletBalance(PARENT_ID))
            .thenReturn(new CreditWalletService.WalletBalance(new BigDecimal("200.00"), new BigDecimal("150.00"),
                new BigDecimal("50.00")));

        assertThatThrownBy(() -> cashOutService.processCashOut(PARENT_ID, AMOUNT))
            .isInstanceOf(PaymentGatewayException.class)
            .hasMessageContaining("payment.cashOutExceedsCashable");

        verify(creditWalletService, never()).debitForCashOut(any(), any(), any(), any(), any());
        verify(paymentGateway, never()).refund(any(), any(), any());
    }
}
