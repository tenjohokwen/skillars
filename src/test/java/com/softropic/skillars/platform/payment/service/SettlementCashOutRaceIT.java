package com.softropic.skillars.platform.payment.service;

import com.softropic.skillars.platform.booking.contract.BookingAcceptedEvent;
import com.softropic.skillars.platform.payment.BasePaymentIT;
import com.softropic.skillars.platform.payment.contract.CreditLedgerEntryType;
import com.softropic.skillars.platform.payment.contract.exception.PaymentGatewayException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * feat/coach-payouts-pack-and-credit (C3 item 3, the #318 follow-up): a booking settlement's credit
 * split and debit take the same per-payer wallet lock ({@code stripe_customers} row) as a cash-out, so
 * the two racing can never overdraw the wallet. Before the fix the settlement read the balance unlocked
 * and wrote its {@code BOOKING_DEDUCTION} later, so a cash-out committing in between left the wallet
 * negative.
 */
class SettlementCashOutRaceIT extends BasePaymentIT {

    private static final long PARENT_ID = 98201L;
    private static final long PLAYER_ID = 98202L;
    private static final long COACH_USER_ID = 98203L;
    private static final BigDecimal PRICE = new BigDecimal("50.00");

    @Autowired PaymentLifecycleService paymentLifecycleService;
    @Autowired CreditWalletService creditWalletService;

    private UUID coachId;

    @BeforeEach
    void seed() {
        coachId = insertTestCoach(COACH_USER_ID, "race_coach@test.com", "Race Coach");
        insertTestParent(PARENT_ID, "race_parent@test.com");
        insertTestPlayer(PLAYER_ID, PARENT_ID);
        transactionTemplate.execute(s -> {
            jdbcTemplate.update("INSERT INTO payment.stripe_customers (parent_id, stripe_customer_id, "
                + "stripe_payment_method_id, last_payment_intent_id) VALUES (?, 'cus_race', 'pm_race', 'pi_race') "
                + "ON CONFLICT (parent_id) DO NOTHING", PARENT_ID);
            creditWalletService.writeLedgerEntry(PARENT_ID, new BigDecimal("100.00"), CreditLedgerEntryType.BOOKING_REFUND,
                UUID.randomUUID(), "IT seed credit");
            return null;
        });
    }

    private UUID seedPendingBooking() {
        UUID bookingId = UUID.randomUUID();
        Instant start = Instant.now().plus(72, ChronoUnit.HOURS);
        transactionTemplate.execute(s -> jdbcTemplate.update(
            "INSERT INTO booking.bookings (id, parent_id, player_id, coach_id, requested_start_time, requested_end_time, "
                + "status, canonical_timezone, version, created_at, updated_at) "
                + "VALUES (?, ?, ?, ?, ?, ?, 'PAYMENT_PENDING', 'UTC', 0, now(), now())",
            bookingId, PARENT_ID, PLAYER_ID, coachId, Timestamp.from(start), Timestamp.from(start.plus(1, ChronoUnit.HOURS))));
        return bookingId;
    }

    private BigDecimal balance() {
        return creditWalletService.getBalance(PARENT_ID);
    }

    /**
     * A cash-out holds the wallet lock and spends 90.00 of the 100.00 while the settlement of a 50.00
     * booking is in flight (it read 100.00 unlocked). The settlement must re-read under the lock and
     * split 10.00 credit + 40.00 card — not debit 50.00 of credit and leave the wallet at -40.00.
     */
    @Test
    @Timeout(60)
    void cashOutCommittingMidSettlement_settlementResplitsUnderTheLock_walletNeverNegative() throws Exception {
        UUID bookingId = seedPendingBooking();
        CountDownLatch lockHeld = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> cashOut = pool.submit(() -> transactionTemplate.execute(s -> {
                jdbcTemplate.query("SELECT parent_id FROM payment.stripe_customers WHERE parent_id = ? FOR UPDATE",
                    rs -> { }, PARENT_ID);
                lockHeld.countDown();
                sleep(800);
                creditWalletService.writeLedgerEntry(PARENT_ID, new BigDecimal("-90.00"),
                    CreditLedgerEntryType.CASH_OUT_DEBIT, UUID.randomUUID(), "IT concurrent cash-out");
                return null;
            }));
            assertThat(lockHeld.await(5, TimeUnit.SECONDS)).isTrue();

            Future<?> settle = pool.submit(() -> paymentLifecycleService.onBookingAccepted(new BookingAcceptedEvent(
                this, bookingId, PARENT_ID, coachId, PRICE, null, "race_parent@test.com", "Race Coach",
                Instant.now().plus(72, ChronoUnit.HOURS), "UTC")));

            cashOut.get(15, TimeUnit.SECONDS);
            settle.get(15, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }

        Map<String, Object> bp = jdbcTemplate.queryForMap(
            "SELECT status, credit_debited, stripe_charged FROM payment.booking_payments WHERE booking_id = ?", bookingId);
        assertThat(bp.get("status")).isEqualTo("CAPTURED");
        assertThat((BigDecimal) bp.get("credit_debited")).isEqualByComparingTo("10.00");
        assertThat((BigDecimal) bp.get("stripe_charged")).isEqualByComparingTo("40.00");
        assertThat(balance()).as("the wallet is never overdrawn").isEqualByComparingTo("0.00");
    }

    /**
     * The settlement side holds the wallet lock (as it does from its locked balance read until its
     * transaction commits the BOOKING_DEDUCTION); a cash-out that read 100.00 before the lock must
     * re-check under it and refuse.
     */
    @Test
    @Timeout(60)
    void settlementHoldingTheWalletLock_cashOutRechecksAndRefuses() throws Exception {
        CountDownLatch lockHeld = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> settlement = pool.submit(() -> transactionTemplate.execute(s -> {
                BigDecimal locked = creditWalletService.lockAndGetBalance(PARENT_ID);
                assertThat(locked).isEqualByComparingTo("100.00");
                lockHeld.countDown();
                sleep(800);
                creditWalletService.writeLedgerEntry(PARENT_ID, new BigDecimal("-90.00"),
                    CreditLedgerEntryType.BOOKING_DEDUCTION, UUID.randomUUID(), "IT settlement deduction");
                return null;
            }));
            assertThat(lockHeld.await(5, TimeUnit.SECONDS)).isTrue();

            Future<?> cashOut = pool.submit(() -> {
                creditWalletService.debitForCashOut(PARENT_ID, UUID.randomUUID(),
                    new BigDecimal("47.50"), new BigDecimal("2.50"), "pi_race");
                return null;
            });

            settlement.get(15, TimeUnit.SECONDS);
            assertThatThrownBy(() -> cashOut.get(15, TimeUnit.SECONDS))
                .isInstanceOf(ExecutionException.class)
                .cause().isInstanceOf(PaymentGatewayException.class)
                .hasMessageContaining("payment.insufficientCredit");
        } finally {
            pool.shutdownNow();
        }
        assertThat(balance()).isEqualByComparingTo("10.00");
        assertThat(jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM payment.cash_out_reconciliations WHERE parent_id = ?", Long.class, PARENT_ID))
            .as("a refused cash-out leaves no reconciliation record").isZero();
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
