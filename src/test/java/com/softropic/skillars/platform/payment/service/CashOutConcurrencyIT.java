package com.softropic.skillars.platform.payment.service;

import com.softropic.skillars.platform.payment.BasePaymentIT;
import com.softropic.skillars.platform.payment.contract.CreditLedgerEntryType;
import com.softropic.skillars.platform.payment.contract.exception.PaymentGatewayException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * fix/payment-money-integrity: a credit cash-out's balance check and its debit rows must be atomic
 * per payer. Before the fix {@code CashOutService} read the balance in one transaction and wrote the
 * debits in separate ones, so two concurrent cash-outs both passed the check and the wallet went
 * negative; it also debited {@code requested + fee} for a refund of {@code requested - fee}.
 *
 * <p>Uses the shared {@code StubPaymentGateway} (no-op refund, generous refundable amount) — the
 * assertions are on the ledger, which is where the overdraw showed up.
 */
class CashOutConcurrencyIT extends BasePaymentIT {

    private static final long PARENT_ID = 96101L;

    @Autowired CashOutService cashOutService;
    @Autowired CreditWalletService creditWalletService;

    @BeforeEach
    void seedPayer() {
        insertTestParent(PARENT_ID, "cashout_concurrency_parent@test.com");
        transactionTemplate.execute(status -> {
            jdbcTemplate.update(
                "INSERT INTO payment.stripe_customers (parent_id, stripe_customer_id, last_payment_intent_id) "
                    + "VALUES (?, 'cus_cashout_it', 'pi_cashout_it') ON CONFLICT (parent_id) DO NOTHING",
                PARENT_ID);
            return null;
        });
        credit(new BigDecimal("100.00"));
    }

    @Test
    @Timeout(60)
    void concurrentCashOuts_neverOverdrawTheWallet() throws Exception {
        int threads = 4;
        BigDecimal each = new BigDecimal("80.00");
        CyclicBarrier start = new CyclicBarrier(threads);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<Future<?>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> {
                    start.await(10, TimeUnit.SECONDS);
                    cashOutService.processCashOut(PARENT_ID, each);
                    return null;
                }));
            }
            int succeeded = 0;
            List<Throwable> failures = new ArrayList<>();
            for (Future<?> f : futures) {
                try {
                    f.get(30, TimeUnit.SECONDS);
                    succeeded++;
                } catch (ExecutionException e) {
                    failures.add(e.getCause());
                }
            }

            assertThat(succeeded).as("only one 80.00 cash-out fits in a 100.00 wallet").isEqualTo(1);
            assertThat(failures).hasSize(threads - 1).allSatisfy(t ->
                assertThat(t).isInstanceOf(PaymentGatewayException.class)
                    .hasMessageContaining("payment.insufficientCredit"));
            // 100 - 80 (net 77.75 + fee 2.25): the wallet loses exactly the requested amount.
            assertThat(balance()).isEqualByComparingTo("20.00");
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @Timeout(60)
    void lockedBalanceCheck_seesADebitCommittedWhileItWaitedForTheLock() throws Exception {
        CountDownLatch lockHeld = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            // Holder takes the per-payer wallet lock, then spends 90.00 of the 100.00 and commits.
            Future<?> holder = pool.submit(() -> transactionTemplate.execute(status -> {
                jdbcTemplate.query("SELECT parent_id FROM payment.stripe_customers WHERE parent_id = ? FOR UPDATE",
                    rs -> { }, PARENT_ID);
                lockHeld.countDown();
                sleep(800);
                creditWalletService.writeLedgerEntry(PARENT_ID, new BigDecimal("-90.00"),
                    CreditLedgerEntryType.BOOKING_DEDUCTION, UUID.randomUUID(), "IT concurrent spend");
                return null;
            }));
            assertThat(lockHeld.await(5, TimeUnit.SECONDS)).isTrue();

            // Contender read 100.00 before the lock; it must re-check under the lock and refuse.
            Future<?> contender = pool.submit(() -> {
                creditWalletService.debitForCashOut(PARENT_ID, UUID.randomUUID(),
                    new BigDecimal("47.50"), new BigDecimal("2.50"), "pi_cashout_it");
                return null;
            });

            holder.get(15, TimeUnit.SECONDS);
            assertThatThrownBy(() -> contender.get(15, TimeUnit.SECONDS))
                .isInstanceOf(ExecutionException.class)
                .cause().isInstanceOf(PaymentGatewayException.class)
                .hasMessageContaining("payment.insufficientCredit");
            assertThat(balance()).isEqualByComparingTo("10.00");
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void cashOutDebitsTheRequestedAmount_splitNetAndFee_taggedWithOneCashOutId() {
        cashOutService.processCashOut(PARENT_ID, new BigDecimal("100.00"));

        // Full-balance cash-out: 100.00 = net 97.25 + fee 2.75 (2.5% + 0.25), wallet lands on 0, not -2.75.
        assertThat(balance()).isEqualByComparingTo("0.00");
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
            "SELECT type, amount, reference_id FROM payment.parent_credit_ledger "
                + "WHERE parent_id = ? AND type IN ('CASH_OUT_DEBIT', 'STRIPE_FEE_DEBIT') ORDER BY type",
            PARENT_ID);
        assertThat(rows).hasSize(2);
        assertThat((BigDecimal) rows.get(0).get("amount")).isEqualByComparingTo("-97.25");
        assertThat((BigDecimal) rows.get(1).get("amount")).isEqualByComparingTo("-2.75");
        assertThat(rows.get(0).get("reference_id")).isNotNull().isEqualTo(rows.get(1).get("reference_id"));
    }

    private void credit(BigDecimal amount) {
        transactionTemplate.execute(status -> {
            creditWalletService.writeLedgerEntry(PARENT_ID, amount, CreditLedgerEntryType.BOOKING_REFUND,
                UUID.randomUUID(), "IT seed credit");
            return null;
        });
    }

    private BigDecimal balance() {
        return creditWalletService.getBalance(PARENT_ID);
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
