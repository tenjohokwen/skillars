package com.softropic.skillars.platform.payment.service;

import com.softropic.skillars.infrastructure.persistence.PessimisticLockRetryer;
import com.softropic.skillars.platform.payment.contract.CreditLedgerEntryType;
import com.softropic.skillars.platform.payment.repo.CashOutReconciliationRepository;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** feat/payment-forfeit-payouts-and-clawback: a refund reversal never takes the wallet below zero. */
@ExtendWith(MockitoExtension.class)
class CreditWalletServiceRefundReversalTest {

    @Mock ParentCreditLedgerRepository ledgerRepository;
    @Mock StripeCustomerRepository stripeCustomerRepository;
    @Mock PessimisticLockRetryer lockRetryer;
    @Mock CashOutReconciliationRepository cashOutReconciliationRepository;

    private CreditWalletService service;
    private final UUID bookingId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new CreditWalletService(ledgerRepository, stripeCustomerRepository, lockRetryer,
            cashOutReconciliationRepository);
        when(stripeCustomerRepository.findByIdForUpdateWaiting(5L)).thenReturn(Optional.of(new StripeCustomer()));
    }

    @Test
    void sufficientBalance_reversesTheWholeRefund() {
        when(ledgerRepository.sumByParentId(5L)).thenReturn(Optional.of(new BigDecimal("80.00")));

        assertThat(service.debitRefundReversal(5L, bookingId, new BigDecimal("40.00"))).isEqualByComparingTo("40.00");

        ArgumentCaptor<ParentCreditLedger> row = ArgumentCaptor.forClass(ParentCreditLedger.class);
        verify(ledgerRepository).save(row.capture());
        assertThat(row.getValue().getAmount()).isEqualByComparingTo("-40.00");
        assertThat(row.getValue().getType()).isEqualTo(CreditLedgerEntryType.BOOKING_REFUND_REVERSAL);
        assertThat(row.getValue().getReferenceId()).isEqualTo(bookingId);
    }

    @Test
    void insufficientBalance_isCappedAtTheBalance() {
        when(ledgerRepository.sumByParentId(5L)).thenReturn(Optional.of(new BigDecimal("12.50")));

        assertThat(service.debitRefundReversal(5L, bookingId, new BigDecimal("40.00"))).isEqualByComparingTo("12.50");

        ArgumentCaptor<ParentCreditLedger> row = ArgumentCaptor.forClass(ParentCreditLedger.class);
        verify(ledgerRepository).save(row.capture());
        assertThat(row.getValue().getAmount()).isEqualByComparingTo("-12.50");
    }

    @Test
    void emptyOrNegativeWallet_writesNothing() {
        when(ledgerRepository.sumByParentId(5L)).thenReturn(Optional.of(new BigDecimal("-3.00")));

        assertThat(service.debitRefundReversal(5L, bookingId, new BigDecimal("40.00"))).isEqualByComparingTo("0");
        verify(ledgerRepository, never()).save(any());
    }
}
