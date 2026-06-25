package com.softropic.skillars.platform.payment.service;

import com.softropic.skillars.platform.config.service.ConfigService;
import com.softropic.skillars.platform.payment.contract.PaymentGateway;
import com.softropic.skillars.platform.payment.contract.exception.PaymentGatewayException;
import com.softropic.skillars.platform.payment.repo.StripeCustomer;
import com.softropic.skillars.platform.payment.repo.StripeCustomerRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CashOutServiceTest {

    @Mock CreditWalletService creditWalletService;
    @Mock StripeCustomerRepository stripeCustomerRepository;
    @Mock PaymentGateway paymentGateway;
    @Mock ConfigService configService;

    @InjectMocks CashOutService cashOutService;

    private static final Long PARENT_ID = 5001L;
    private static final String PAYMENT_INTENT_ID = "pi_test_cashout_intent";
    private static final BigDecimal AMOUNT = new BigDecimal("100.00");

    private void stubFeeConfig() {
        when(configService.getString("payment.stripe.feeRate")).thenReturn("0.025");
        when(configService.getString("payment.stripe.feeFixed")).thenReturn("0.25");
    }

    @Test
    void processCashOut_feeCalculatedCorrectly_refundIssuedWithNetAmount() {
        stubFeeConfig();
        when(creditWalletService.getBalance(PARENT_ID)).thenReturn(new BigDecimal("200.00"));
        StripeCustomer customer = new StripeCustomer();
        customer.setParentId(PARENT_ID);
        customer.setLastPaymentIntentId(PAYMENT_INTENT_ID);
        when(stripeCustomerRepository.findById(PARENT_ID)).thenReturn(Optional.of(customer));

        cashOutService.processCashOut(PARENT_ID, AMOUNT);

        // feeAmount = 100 * 0.025 + 0.25 = 2.75; net = 100 - 2.75 = 97.25
        verify(paymentGateway).refund(eq(PAYMENT_INTENT_ID), eq(new BigDecimal("97.25")));
        ArgumentCaptor<BigDecimal> amountCaptor = ArgumentCaptor.forClass(BigDecimal.class);
        ArgumentCaptor<String> typeCaptor = ArgumentCaptor.forClass(String.class);
        // writeLedgerEntry called for CASH_OUT_DEBIT and STRIPE_FEE_DEBIT
        verify(creditWalletService).writeLedgerEntry(eq(PARENT_ID), eq(new BigDecimal("-100.00")),
            eq("CASH_OUT_DEBIT"), isNull(), any());
        verify(creditWalletService).writeLedgerEntry(eq(PARENT_ID), eq(new BigDecimal("-2.75")),
            eq("STRIPE_FEE_DEBIT"), isNull(), any());
    }

    @Test
    void processCashOut_insufficientBalance_throwsException() {
        when(creditWalletService.getBalance(PARENT_ID)).thenReturn(new BigDecimal("50.00"));

        assertThatThrownBy(() -> cashOutService.processCashOut(PARENT_ID, AMOUNT))
            .isInstanceOf(PaymentGatewayException.class)
            .hasMessageContaining("payment.insufficientCredit");

        verify(paymentGateway, never()).refund(any(), any());
    }

    @Test
    void processCashOut_noStripeCustomer_throwsException() {
        when(creditWalletService.getBalance(PARENT_ID)).thenReturn(new BigDecimal("200.00"));
        when(stripeCustomerRepository.findById(PARENT_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> cashOutService.processCashOut(PARENT_ID, AMOUNT))
            .isInstanceOf(PaymentGatewayException.class)
            .hasMessageContaining("payment.noPaymentMethod");

        verify(paymentGateway, never()).refund(any(), any());
    }

    @Test
    void processCashOut_stripeRefundFails_writesReversalAndRethrows() {
        stubFeeConfig();
        when(creditWalletService.getBalance(PARENT_ID)).thenReturn(new BigDecimal("200.00"));
        StripeCustomer customer = new StripeCustomer();
        customer.setParentId(PARENT_ID);
        customer.setLastPaymentIntentId(PAYMENT_INTENT_ID);
        when(stripeCustomerRepository.findById(PARENT_ID)).thenReturn(Optional.of(customer));
        doThrow(new PaymentGatewayException("payment.refundFailed"))
            .when(paymentGateway).refund(any(), any());

        assertThatThrownBy(() -> cashOutService.processCashOut(PARENT_ID, AMOUNT))
            .isInstanceOf(PaymentGatewayException.class)
            .hasMessageContaining("payment.refundFailed");

        // Two CASH_OUT_REVERSAL entries: one for the full requested amount, one for the fee
        verify(creditWalletService).writeLedgerEntry(eq(PARENT_ID), eq(AMOUNT),
            eq("CASH_OUT_REVERSAL"), isNull(), any());
        verify(creditWalletService).writeLedgerEntry(eq(PARENT_ID), eq(new BigDecimal("2.75")),
            eq("CASH_OUT_REVERSAL"), isNull(), any());
    }
}
