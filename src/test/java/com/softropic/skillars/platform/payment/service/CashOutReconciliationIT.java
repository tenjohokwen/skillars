package com.softropic.skillars.platform.payment.service;

import com.softropic.skillars.platform.payment.BasePaymentIT;
import com.softropic.skillars.platform.payment.contract.CreditLedgerEntryType;
import com.softropic.skillars.platform.payment.contract.exception.PaymentGatewayException;
import com.softropic.skillars.platform.payment.repo.CashOutReconciliation;
import com.softropic.skillars.platform.payment.repo.CashOutReconciliationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;

/**
 * feat/coach-payouts-pack-and-credit (C3 item 4): every credit cash-out leaves a durable
 * {@code cash_out_reconciliations} record, written with the debit; an unconfirmed refund raises a
 * {@code CASH_OUT_UNRESOLVED} admin alert and is re-checked under its original idempotency key until
 * Stripe's answer settles it — credit restored at most once.
 */
class CashOutReconciliationIT extends BasePaymentIT {

    private static final long PARENT_ID = 98301L;

    @Autowired CashOutService cashOutService;
    @Autowired CashOutReconciliationService reconciliationService;
    @Autowired CashOutReconciliationRepository repository;
    @Autowired CreditWalletService creditWalletService;

    @BeforeEach
    void seed() {
        insertTestParent(PARENT_ID, "cashout_recon_parent@test.com");
        transactionTemplate.execute(s -> {
            jdbcTemplate.update("INSERT INTO payment.stripe_customers (parent_id, stripe_customer_id, last_payment_intent_id) "
                + "VALUES (?, 'cus_recon', 'pi_recon') ON CONFLICT (parent_id) DO NOTHING", PARENT_ID);
            creditWalletService.writeLedgerEntry(PARENT_ID, new BigDecimal("100.00"), CreditLedgerEntryType.BOOKING_REFUND,
                UUID.randomUUID(), "IT seed credit");
            return null;
        });
    }

    private CashOutReconciliation onlyRecord() {
        List<CashOutReconciliation> rows = repository.findAll().stream()
            .filter(r -> r.getParentId().equals(PARENT_ID)).toList();
        assertThat(rows).hasSize(1);
        return rows.get(0);
    }

    /** ShedLock's lockAtLeastFor would silently skip a second run within a minute — release it first. */
    private void recheck() {
        releaseSchedulerLock("CashOutReconciliationService_recheck");
        reconciliationService.recheckOpenCashOuts();
    }

    private BigDecimal balance() {
        return creditWalletService.getBalance(PARENT_ID);
    }

    @Test
    void successfulCashOut_recordIsCompleted() {
        cashOutService.processCashOut(PARENT_ID, new BigDecimal("40.00"));

        CashOutReconciliation r = onlyRecord();
        assertThat(r.getStatus()).isEqualTo(CashOutReconciliation.COMPLETED);
        assertThat(r.getPaymentIntentId()).isEqualTo("pi_recon");
        assertThat(r.getNetAmount().add(r.getFeeAmount())).isEqualByComparingTo("40.00");
        assertThat(balance()).isEqualByComparingTo("60.00");
    }

    @Test
    void unknownOutcome_recordUnknown_adminAlerted_thenRecheckConfirmsIt() {
        doThrow(new PaymentGatewayException("payment.refundOutcomeUnknown"))
            .when(paymentGateway).refund(any(), any(), any());

        assertThatThrownBy(() -> cashOutService.processCashOut(PARENT_ID, new BigDecimal("40.00")))
            .isInstanceOf(PaymentGatewayException.class)
            .hasMessageContaining("payment.refundOutcomeUnknown");

        CashOutReconciliation r = onlyRecord();
        assertThat(r.getStatus()).isEqualTo(CashOutReconciliation.UNKNOWN);
        assertThat(balance()).as("debit kept while the outcome is unknown").isEqualByComparingTo("60.00");
        assertThat(jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM admin.admin_alerts WHERE type = 'CASH_OUT_UNRESOLVED' AND reference_type = 'CASH_OUT' "
                + "AND reference_id = ? AND status = 'OPEN'", Long.class, r.getCashOutId().toString()))
            .isEqualTo(1L);

        // Stripe now answers (a replay under the same key): the job closes the record.
        doNothing().when(paymentGateway).refund(any(), any(), any());
        recheck();

        assertThat(onlyRecord().getStatus()).isEqualTo(CashOutReconciliation.COMPLETED);
        assertThat(balance()).isEqualByComparingTo("60.00");
        assertThat(jdbcTemplate.queryForObject(
            "SELECT status FROM admin.admin_alerts WHERE type = 'CASH_OUT_UNRESOLVED' AND reference_id = ?",
            String.class, r.getCashOutId().toString()))
            .as("the alert closes itself, so no admin re-pays a settled cash-out").isEqualTo("RESOLVED");
    }

    @Test
    void recheckFindsTheRefundRejected_restoresCreditExactlyOnce() {
        doThrow(new PaymentGatewayException("payment.refundOutcomeUnknown"))
            .when(paymentGateway).refund(any(), any(), any());
        assertThatThrownBy(() -> cashOutService.processCashOut(PARENT_ID, new BigDecimal("40.00")))
            .isInstanceOf(PaymentGatewayException.class);
        UUID cashOutId = onlyRecord().getCashOutId();

        doThrow(new PaymentGatewayException("payment.refundFailed")).when(paymentGateway).refund(any(), any(), any());
        recheck();

        assertThat(onlyRecord().getStatus()).isEqualTo(CashOutReconciliation.REVERSED);
        assertThat(balance()).isEqualByComparingTo("100.00");

        // A second compensation attempt (the request thread or another run) restores nothing.
        assertThat(reconciliationService.reverseAfterRejection(cashOutId, "again")).isFalse();
        assertThat(balance()).isEqualByComparingTo("100.00");
    }
}
