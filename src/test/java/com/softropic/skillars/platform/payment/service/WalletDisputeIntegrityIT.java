package com.softropic.skillars.platform.payment.service;

import com.softropic.skillars.platform.outbox.service.OutboxService;
import com.softropic.skillars.platform.payment.BasePaymentIT;
import com.softropic.skillars.platform.payment.contract.CreditLedgerEntryType;
import com.softropic.skillars.platform.payment.contract.exception.PaymentGatewayException;
import com.softropic.skillars.platform.payment.repo.ParentCreditLedgerRepository;
import com.stripe.model.Event;
import com.stripe.net.ApiResource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * fix/payment-wallet-dispute-integrity, against a real database (V208 column / CHECKs / indexes and the native
 * queries):
 * <ul>
 *   <li>spend-only credit (owner decision: expired-pack parent-cancel refund) is bookable but never cashed out,
 *       and spends consume it first;</li>
 *   <li>a batch-share refund and the booking's own cancellation refund no longer collide on one row;</li>
 *   <li>a dispute credit's cap counts a cancellation refund still pending in the outbox;</li>
 *   <li>cash-out is refused while an in-app dispute is open on one of the payer's bookings, and targets the
 *       charge that funded the credit;</li>
 *   <li>an admin dispute credit for a charge that already lost a card dispute is taken back at once.</li>
 * </ul>
 * Uses the shared {@code StubPaymentGateway}; no new mock beans.
 */
class WalletDisputeIntegrityIT extends BasePaymentIT {

    private static final long PARENT_ID = 98401L;
    private static final long PLAYER_ID = 98402L;
    private static final long COACH_USER_ID = 98403L;

    @Autowired CreditWalletService creditWalletService;
    @Autowired RefundOutboxSupport refundOutboxSupport;
    @Autowired OutboxService outboxService;
    @Autowired ParentCreditLedgerRepository ledgerRepository;
    @Autowired DisputeCreditService disputeCreditService;
    @Autowired StripeWebhookService stripeWebhookService;

    private UUID coachId;
    private int slot;

    @BeforeEach
    void seed() {
        coachId = insertTestCoach(COACH_USER_ID, "wallet_int_coach@test.com", "Wallet Coach");
        insertTestParent(PARENT_ID, "wallet_int_parent@test.com");
        insertTestPlayer(PLAYER_ID, PARENT_ID);
        transactionTemplate.execute(s -> jdbcTemplate.update(
            "INSERT INTO payment.stripe_customers (parent_id, stripe_customer_id, last_payment_intent_id) "
                + "VALUES (?, 'cus_wallet_it', 'pi_wallet_last') ON CONFLICT (parent_id) DO NOTHING", PARENT_ID));
    }

    // ── fixtures ──

    private UUID seedBooking(String status) {
        UUID bookingId = UUID.randomUUID();
        Instant start = Instant.now().minus(2 * (++slot), ChronoUnit.HOURS);
        transactionTemplate.execute(s -> jdbcTemplate.update(
            "INSERT INTO booking.bookings (id, parent_id, player_id, coach_id, requested_start_time, requested_end_time, "
                + "status, canonical_timezone, version, created_at, updated_at, status_changed_at) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, 'UTC', 0, now(), now(), now())",
            bookingId, PARENT_ID, PLAYER_ID, coachId, Timestamp.from(start),
            Timestamp.from(start.plus(1, ChronoUnit.HOURS)), status));
        return bookingId;
    }

    private void seedCardPayment(UUID bookingId, String card, String pi) {
        transactionTemplate.execute(s -> jdbcTemplate.update(
            "INSERT INTO payment.booking_payments (booking_id, credit_debited, stripe_charged, status, captured_at, "
                + "commission_rate, stripe_payment_intent_id) VALUES (?, 0, ?::numeric, 'CAPTURED', now(), 0.10, ?)",
            bookingId, card, pi));
    }

    private void drainNow() {
        transactionTemplate.execute(s -> jdbcTemplate.update(
            "UPDATE main.outbox_messages SET next_attempt_at = now() - interval '1 minute'"));
        outboxService.drain();
    }

    private BigDecimal spendOnlyOf(String type, UUID reference) {
        return jdbcTemplate.queryForObject("SELECT COALESCE(SUM(spend_only_amount), 0) FROM payment.parent_credit_ledger "
            + "WHERE parent_id = ? AND type = ? AND reference_id = ?", BigDecimal.class, PARENT_ID, type, reference);
    }

    private BigDecimal sumOfType(String type) {
        return jdbcTemplate.queryForObject("SELECT COALESCE(SUM(amount), 0) FROM payment.parent_credit_ledger "
            + "WHERE parent_id = ? AND type = ?", BigDecimal.class, PARENT_ID, type);
    }

    private void cashOut(String net, String fee) {
        creditWalletService.debitForCashOut(PARENT_ID, UUID.randomUUID(), new BigDecimal(net), new BigDecimal(fee),
            "pi_wallet_last");
    }

    // ── spend-only credit ──

    @Test
    void spendOnlyRefund_isBookableButNeverCashedOut_andSpendsConsumeItFirst() {
        UUID expiredPackBooking = seedBooking("CANCELLED");
        transactionTemplate.execute(s -> {
            refundOutboxSupport.enqueueSpendOnlyBookingRefund(PARENT_ID, new BigDecimal("50.00"), expiredPackBooking,
                "Parent cancellation >24h — expired pack refund (booking credit only)");
            return null;
        });
        drainNow();

        assertThat(spendOnlyOf(CreditLedgerEntryType.BOOKING_REFUND, expiredPackBooking)).isEqualByComparingTo("50.00");
        CreditWalletService.WalletBalance onlySpendOnly = creditWalletService.getWalletBalance(PARENT_ID);
        assertThat(onlySpendOnly.total()).isEqualByComparingTo("50.00");
        assertThat(onlySpendOnly.cashable()).isEqualByComparingTo("0.00");
        assertThatThrownBy(() -> cashOut("9.00", "1.00"))
            .isInstanceOf(PaymentGatewayException.class)
            .hasMessageContaining("payment.cashOutExceedsCashable");

        // 20.00 of cashable credit arrives; a 30.00 booking then spends the spend-only pool first.
        creditWalletService.writeLedgerEntry(PARENT_ID, new BigDecimal("20.00"), CreditLedgerEntryType.BOOKING_REFUND,
            seedBooking("CANCELLED"), "cashable refund");
        UUID spend = UUID.randomUUID();
        transactionTemplate.execute(s -> {
            creditWalletService.lockAndGetBalance(PARENT_ID);
            creditWalletService.debitForBooking(PARENT_ID, new BigDecimal("30.00"), spend, "Session booking deduction");
            return null;
        });

        assertThat(spendOnlyOf(CreditLedgerEntryType.BOOKING_DEDUCTION, spend)).isEqualByComparingTo("-30.00");
        CreditWalletService.WalletBalance after = creditWalletService.getWalletBalance(PARENT_ID);
        assertThat(after.total()).isEqualByComparingTo("40.00");
        assertThat(after.spendOnly()).isEqualByComparingTo("20.00");
        assertThat(after.cashable()).isEqualByComparingTo("20.00");

        // Exactly the cashable part can leave; one cent more cannot.
        assertThatThrownBy(() -> cashOut("19.01", "1.00"))
            .hasMessageContaining("payment.cashOutExceedsCashable");
        cashOut("19.00", "1.00");
        assertThat(creditWalletService.getWalletBalance(PARENT_ID).cashable()).isEqualByComparingTo("0.00");
    }

    @Test
    void v206_spendOnlyCheck_rejectsAPartLargerThanTheAmount() {
        assertThatThrownBy(() -> transactionTemplate.execute(s -> jdbcTemplate.update(
            "INSERT INTO payment.parent_credit_ledger (parent_id, amount, type, reference_id, description, spend_only_amount) "
                + "VALUES (?, 10.00, 'BOOKING_REFUND', ?, 'bad', 11.00)", PARENT_ID, UUID.randomUUID())))
            .isInstanceOf(DataIntegrityViolationException.class);
    }

    // ── refund kinds ──

    @Test
    void batchShareRefund_andTheBookingsOwnCancellationRefund_bothLand() {
        UUID booking = seedBooking("CANCELLED");
        transactionTemplate.execute(s -> {
            refundOutboxSupport.enqueueBatchShareRefund(PARENT_ID, new BigDecimal("15.00"), booking,
                "batch share", "pi_batch", new BigDecimal("15.00"));
            return null;
        });
        drainNow();
        transactionTemplate.execute(s -> {
            refundOutboxSupport.enqueueBookingRefund(PARENT_ID, new BigDecimal("40.00"), booking, "real cancel refund");
            return null;
        });
        drainNow();

        assertThat(sumOfType(CreditLedgerEntryType.BATCH_SHARE_REFUND)).isEqualByComparingTo("15.00");
        assertThat(sumOfType(CreditLedgerEntryType.BOOKING_REFUND)).isEqualByComparingTo("40.00");
        // A re-drive of either is still a no-op.
        transactionTemplate.execute(s -> {
            refundOutboxSupport.enqueueBatchShareRefund(PARENT_ID, new BigDecimal("15.00"), booking,
                "batch share", "pi_batch", new BigDecimal("15.00"));
            return null;
        });
        drainNow();
        assertThat(sumOfType(CreditLedgerEntryType.BATCH_SHARE_REFUND)).isEqualByComparingTo("15.00");
    }

    @Test
    void disputeCreditCap_countsARefundStillPendingInTheOutbox() {
        UUID booking = seedBooking("CANCELLED");
        transactionTemplate.execute(s -> jdbcTemplate.update(
            "INSERT INTO main.outbox_messages (aggregate_type, payload, next_attempt_at) "
                + "VALUES ('CREDIT_WALLET_REFUND', CAST(? AS jsonb), now() + interval '1 hour')",
            "{\"parentId\":" + PARENT_ID + ",\"amount\":40.00,\"bookingId\":\"" + booking + "\",\"description\":\"x\"}"));

        BigDecimal remaining = transactionTemplate.execute(s ->
            disputeCreditService.remainingRefundable(booking, new BigDecimal("50.00")));

        assertThat(remaining).isEqualByComparingTo("10.00");
    }

    // ── cash-out ──

    @Test
    void cashOut_isRefusedWhileAnInAppDisputeIsOpenOnOneOfThePayersBookings() {
        UUID booking = seedBooking("CANCELLED_COACH");
        creditWalletService.writeLedgerEntry(PARENT_ID, new BigDecimal("50.00"), CreditLedgerEntryType.BOOKING_REFUND,
            booking, "coach cancel refund");
        UUID disputeId = UUID.randomUUID();
        transactionTemplate.execute(s -> jdbcTemplate.update(
            "INSERT INTO admin.disputes (id, booking_id, raised_by, raised_by_role, reason, details, status, version) "
                + "VALUES (?, ?, ?, 'COACH', 'OTHER', 'I was there', 'OPEN', 0)", disputeId, booking, COACH_USER_ID));

        assertThatThrownBy(() -> cashOut("9.00", "1.00"))
            .isInstanceOf(PaymentGatewayException.class)
            .hasMessageContaining("payment.cashOutBlockedByDispute");

        transactionTemplate.execute(s -> jdbcTemplate.update(
            "UPDATE admin.disputes SET status = 'DISMISSED' WHERE id = ?", disputeId));
        cashOut("9.00", "1.00");
        assertThat(creditWalletService.getBalance(PARENT_ID)).isEqualByComparingTo("40.00");
    }

    @Test
    void cashOutRefundTargets_areTheChargesThatFundedCashableCredit_newestFirst() {
        UUID older = seedBooking("CANCELLED");
        seedCardPayment(older, "30.00", "pi_fund_old");
        UUID newer = seedBooking("CANCELLED");
        seedCardPayment(newer, "30.00", "pi_fund_new");
        UUID spendOnly = seedBooking("CANCELLED");
        seedCardPayment(spendOnly, "30.00", "pi_fund_spend_only");
        creditWalletService.writeLedgerEntry(PARENT_ID, new BigDecimal("30.00"), CreditLedgerEntryType.BOOKING_REFUND,
            older, "older");
        creditWalletService.writeLedgerEntry(PARENT_ID, new BigDecimal("30.00"), CreditLedgerEntryType.BOOKING_REFUND,
            newer, "newer");
        creditWalletService.writeLedgerEntry(PARENT_ID, new BigDecimal("30.00"), CreditLedgerEntryType.BOOKING_REFUND,
            spendOnly, "spend-only", null, null, new BigDecimal("30.00"));

        assertThat(ledgerRepository.findCashOutRefundTargets(PARENT_ID, 3))
            .containsExactly("pi_fund_new", "pi_fund_old");
    }

    // ── dispute credit through the chargeback trace ──

    @Test
    void adminDisputeCredit_forAChargeThatAlreadyLostACardDispute_isTakenBackAtOnce() {
        String pi = "pi_wallet_lost_" + UUID.randomUUID().toString().substring(0, 8);
        UUID booking = seedBooking("COMPLETED");
        seedCardPayment(booking, "40.00", pi);
        String stripeDisputeId = "dp_wallet_" + UUID.randomUUID().toString().substring(0, 8);
        webhook("charge.dispute.created", dispute(stripeDisputeId, pi, 4_000, "needs_response"));
        webhook("charge.dispute.closed", dispute(stripeDisputeId, pi, 4_000, "lost"));

        transactionTemplate.execute(s -> {
            disputeCreditService.credit(PARENT_ID, booking, UUID.randomUUID(), new BigDecimal("40.00"),
                "Admin dispute resolution — full refund");
            return null;
        });

        assertThat(sumOfType(CreditLedgerEntryType.DISPUTE_CREDIT)).isEqualByComparingTo("40.00");
        assertThat(sumOfType(CreditLedgerEntryType.CHARGEBACK_CREDIT_REVERSAL)).isEqualByComparingTo("-40.00");
        assertThat(creditWalletService.getBalance(PARENT_ID)).isEqualByComparingTo("0.00");
    }

    private void webhook(String type, String object) {
        Event event = ApiResource.GSON.fromJson("""
            {"id": "evt_%s", "object": "event", "type": "%s", "created": %d, "data": {"object": %s}}
            """.formatted(UUID.randomUUID(), type, Instant.now().getEpochSecond(), object), Event.class);
        stripeWebhookService.handleEventAtomically(event);
    }

    private static String dispute(String id, String pi, long amountCents, String status) {
        return """
            {"id": "%s", "object": "dispute", "payment_intent": "%s", "charge": "ch_it", "amount": %d,
             "currency": "eur", "status": "%s", "created": %d}
            """.formatted(id, pi, amountCents, status, Instant.now().getEpochSecond());
    }
}
