package com.softropic.skillars.platform.payment.service;

import com.softropic.skillars.platform.payment.contract.PaymentGateway;
import com.softropic.skillars.platform.payment.contract.event.CashOutRefundUnresolvedEvent;
import com.softropic.skillars.platform.payment.contract.exception.PaymentGatewayException;
import com.softropic.skillars.platform.payment.repo.CashOutReconciliation;
import com.softropic.skillars.platform.payment.repo.CashOutReconciliationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** feat/coach-payouts-pack-and-credit (C3 item 4): reconciliation transitions are exactly-once. */
@ExtendWith(MockitoExtension.class)
class CashOutReconciliationServiceTest {

    @Mock CashOutReconciliationRepository repository;
    @Mock CreditWalletService creditWalletService;
    @Mock PaymentGateway paymentGateway;
    @Mock ApplicationEventPublisher eventPublisher;

    private CashOutReconciliationService service;
    private final UUID cashOutId = UUID.randomUUID();
    private CashOutReconciliation row;

    @BeforeEach
    void setUp() {
        service = new CashOutReconciliationService(repository, creditWalletService, paymentGateway, eventPublisher);
        ReflectionTestUtils.setField(service, "self", service);
        row = CashOutReconciliation.pending(cashOutId, 42L, "pi_1", new BigDecimal("97.25"), new BigDecimal("2.75"));
        lenient().when(repository.findByIdForUpdate(cashOutId)).thenAnswer(inv -> Optional.of(row));
        lenient().when(repository.findById(cashOutId)).thenAnswer(inv -> Optional.of(row));
    }

    @Test
    void reverseAfterRejection_restoresCreditOnce_secondCallIsNoOp() {
        assertThat(service.reverseAfterRejection(cashOutId, "payment.refundFailed")).isTrue();
        assertThat(service.reverseAfterRejection(cashOutId, "payment.refundFailed")).isFalse();

        verify(creditWalletService).reverseCashOut(42L, cashOutId, new BigDecimal("97.25"), new BigDecimal("2.75"));
        assertThat(row.getStatus()).isEqualTo(CashOutReconciliation.REVERSED);
    }

    @Test
    void completedRow_isNeverReversed() {
        service.markCompleted(cashOutId);

        assertThat(service.reverseAfterRejection(cashOutId, "x")).isFalse();
        verify(creditWalletService, never()).reverseCashOut(any(), any(), any(), any());
        assertThat(row.getStatus()).isEqualTo(CashOutReconciliation.COMPLETED);
    }

    @Test
    void markUnknown_raisesTheAdminAlertOnlyTheFirstTime() {
        service.markUnknown(cashOutId, "payment.refundOutcomeUnknown");
        service.markUnknown(cashOutId, "payment.refundOutcomeUnknown");

        verify(eventPublisher, org.mockito.Mockito.times(1)).publishEvent(any(CashOutRefundUnresolvedEvent.class));
        assertThat(row.getStatus()).isEqualTo(CashOutReconciliation.UNKNOWN);
        assertThat(row.getAttempts()).isEqualTo(2);
    }

    @Test
    void recheck_success_retriesUnderTheOriginalKey_andCompletes() {
        row.setStatus(CashOutReconciliation.UNKNOWN);

        service.recheckOne(cashOutId);

        verify(paymentGateway).refund("pi_1", new BigDecimal("97.25"), "cashout-" + cashOutId);
        assertThat(row.getStatus()).isEqualTo(CashOutReconciliation.COMPLETED);
    }

    @Test
    void recheck_rejected_restoresCredit() {
        doThrow(new PaymentGatewayException("payment.refundFailed")).when(paymentGateway).refund(any(), any(), any());

        service.recheckOne(cashOutId);

        verify(creditWalletService).reverseCashOut(eq(42L), eq(cashOutId), any(), any());
        assertThat(row.getStatus()).isEqualTo(CashOutReconciliation.REVERSED);
    }

    @Test
    void recheck_stillUnknown_keepsTheDebit() {
        doThrow(new PaymentGatewayException("payment.refundOutcomeUnknown"))
            .when(paymentGateway).refund(any(), any(), anyString());

        service.recheckOne(cashOutId);

        verify(creditWalletService, never()).reverseCashOut(any(), any(), any(), any());
        assertThat(row.getStatus()).isEqualTo(CashOutReconciliation.UNKNOWN);
    }

    @Test
    void recheck_ofAClosedRow_neverCallsStripe() {
        row.setStatus(CashOutReconciliation.REVERSED);

        service.recheckOne(cashOutId);

        verify(paymentGateway, never()).refund(any(), any(), any());
    }

    @Test
    void recheckJob_escalatesRowsPastTheIdempotencyWindowWithoutCallingStripe() {
        when(repository.findExpiredPending(any(), any())).thenReturn(java.util.List.of(cashOutId));
        when(repository.findRetryable(any(), any(), any())).thenReturn(java.util.List.of());

        service.recheckOpenCashOuts();

        assertThat(row.getStatus()).isEqualTo(CashOutReconciliation.UNKNOWN);
        verify(paymentGateway, never()).refund(any(), any(), any());
        verify(eventPublisher).publishEvent(any(CashOutRefundUnresolvedEvent.class));
    }
}
