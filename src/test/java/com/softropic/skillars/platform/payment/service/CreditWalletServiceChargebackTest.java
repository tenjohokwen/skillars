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
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * fix/payment-dispute-credit-trace-and-lost-dispute: the wallet side of a card dispute on a charge that had
 * funded wallet credit — the reversal never takes the wallet below zero, is written once per reference and
 * payer, the restore once, and no cash-out goes through while a dispute is open.
 */
@ExtendWith(MockitoExtension.class)
class CreditWalletServiceChargebackTest {

    @Mock ParentCreditLedgerRepository ledgerRepository;
    @Mock StripeCustomerRepository stripeCustomerRepository;
    @Mock PessimisticLockRetryer lockRetryer;
    @Mock CashOutReconciliationRepository cashOutReconciliationRepository;
    @Mock ChargeDisputeRepository chargeDisputeRepository;

    private CreditWalletService service;
    private final UUID reference = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new CreditWalletService(ledgerRepository, stripeCustomerRepository, lockRetryer,
            cashOutReconciliationRepository, chargeDisputeRepository);
    }

    private ParentCreditLedger savedRow() {
        ArgumentCaptor<ParentCreditLedger> row = ArgumentCaptor.forClass(ParentCreditLedger.class);
        verify(ledgerRepository).save(row.capture());
        return row.getValue();
    }

    @Test
    void reversal_cappedAtTheBalance_neverNegative() {
        when(stripeCustomerRepository.findByIdForUpdateWaiting(5L)).thenReturn(Optional.of(new StripeCustomer()));
        when(ledgerRepository.sumByParentId(5L)).thenReturn(Optional.of(new BigDecimal("12.00")));

        assertThat(service.debitChargebackCredit(5L, reference, new BigDecimal("30.00"), "x"))
            .isEqualByComparingTo("12.00");

        ParentCreditLedger row = savedRow();
        assertThat(row.getAmount()).isEqualByComparingTo("-12.00");
        assertThat(row.getType()).isEqualTo(CreditLedgerEntryType.CHARGEBACK_CREDIT_REVERSAL);
        assertThat(row.getReferenceId()).isEqualTo(reference);
    }

    @Test
    void reversal_emptyWallet_writesNothing() {
        when(stripeCustomerRepository.findByIdForUpdateWaiting(5L)).thenReturn(Optional.of(new StripeCustomer()));
        when(ledgerRepository.sumByParentId(5L)).thenReturn(Optional.of(new BigDecimal("-3.00")));

        assertThat(service.debitChargebackCredit(5L, reference, new BigDecimal("30.00"), "x")).isEqualByComparingTo("0");
        verify(ledgerRepository, never()).save(any());
    }

    @Test
    void reversal_alreadyWrittenForThisReference_isANoOp() {
        when(ledgerRepository.existsByReferenceIdAndParentIdAndType(reference, 5L,
            CreditLedgerEntryType.CHARGEBACK_CREDIT_REVERSAL)).thenReturn(true);

        assertThat(service.debitChargebackCredit(5L, reference, new BigDecimal("30.00"), "x")).isEqualByComparingTo("0");
        verify(ledgerRepository, never()).save(any());
        verify(stripeCustomerRepository, never()).findByIdForUpdateWaiting(any());
    }

    @Test
    void restore_writesOnce() {
        assertThat(service.restoreChargebackCredit(5L, reference, new BigDecimal("12.00"))).isTrue();
        ParentCreditLedger row = savedRow();
        assertThat(row.getAmount()).isEqualByComparingTo("12.00");
        assertThat(row.getType()).isEqualTo(CreditLedgerEntryType.CHARGEBACK_CREDIT_RESTORE);

        when(ledgerRepository.existsByReferenceIdAndParentIdAndType(reference, 5L,
            CreditLedgerEntryType.CHARGEBACK_CREDIT_RESTORE)).thenReturn(true);
        assertThat(service.restoreChargebackCredit(5L, reference, new BigDecimal("12.00"))).isFalse();
    }

    @Test
    void cashOut_refusedWhileACardDisputeIsOpen() {
        when(lockRetryer.withBoundedRetry(anyString(), any()))
            .thenAnswer(inv -> ((Supplier<?>) inv.getArgument(1)).get());
        when(stripeCustomerRepository.findByIdForUpdate(5L)).thenReturn(Optional.of(new StripeCustomer()));
        when(chargeDisputeRepository.existsOpenDisputeForPayer(5L)).thenReturn(true);

        assertThatThrownBy(() -> service.debitForCashOut(5L, UUID.randomUUID(), new BigDecimal("9.00"),
            new BigDecimal("1.00"), "pi_1"))
            .isInstanceOf(PaymentGatewayException.class)
            .hasMessageContaining("payment.cashOutBlockedByDispute");
        verify(ledgerRepository, never()).save(any());
    }
}
