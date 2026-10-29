package com.softropic.skillars.platform.payment.service;

import com.softropic.skillars.platform.outbox.service.OutboxService;
import com.softropic.skillars.platform.payment.BasePaymentIT;
import com.softropic.skillars.platform.payment.contract.CoachPayoutStatus;
import com.softropic.skillars.platform.payment.contract.CreditLedgerEntryType;
import com.softropic.skillars.platform.payment.contract.exception.PaymentGatewayException;
import com.softropic.skillars.platform.payment.repo.CoachPayout;
import com.softropic.skillars.platform.payment.repo.CoachPayoutRepository;
import com.stripe.model.Event;
import com.stripe.net.ApiResource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * fix/payment-dispute-credit-trace-and-lost-dispute, against a real database (V199 CHECKs and unique
 * indexes, the native trace query):
 * <ul>
 *   <li>Gap 1 — a card charge refunded to wallet credit and then disputed: the unspent credit is reversed
 *       when the dispute opens (never below zero, shortfall alerted), cash-out is refused while it is open,
 *       a won dispute restores it once, a lost one keeps it, and credit written after a lost dispute is
 *       reversed at once;</li>
 *   <li>Gap 2 — a dispute lost before the session completed: the payout is cut (or cancelled) instead of
 *       parked in HOLD.</li>
 * </ul>
 * Uses the shared {@code StubPaymentGateway}; no new mock beans.
 */
class ChargebackCreditTraceIT extends BasePaymentIT {

    private static final long PARENT_ID = 98301L;
    private static final long PLAYER_ID = 98302L;
    private static final long COACH_USER_ID = 98303L;

    @Autowired StripeWebhookService stripeWebhookService;
    @Autowired CreditWalletService creditWalletService;
    @Autowired RefundOutboxSupport refundOutboxSupport;
    @Autowired OutboxService outboxService;
    @Autowired CoachPayoutOutboxSupport coachPayoutOutboxSupport;
    @Autowired CoachPayoutRepository coachPayoutRepository;

    private UUID coachId;
    private int slot;

    @BeforeEach
    void seed() {
        coachId = insertTestCoach(COACH_USER_ID, "cb_credit_coach@test.com", "Chargeback Coach");
        insertTestParent(PARENT_ID, "cb_credit_parent@test.com");
        insertTestPlayer(PLAYER_ID, PARENT_ID);
        transactionTemplate.execute(s -> jdbcTemplate.update(
            "INSERT INTO payment.stripe_customers (parent_id, stripe_customer_id, last_payment_intent_id) "
                + "VALUES (?, 'cus_cb_it', 'pi_cb_last') ON CONFLICT (parent_id) DO NOTHING", PARENT_ID));
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

    private void ledger(String amount, String type, UUID reference) {
        creditWalletService.writeLedgerEntry(PARENT_ID, new BigDecimal(amount), type, reference, "it");
    }

    private BigDecimal balance() {
        return creditWalletService.getBalance(PARENT_ID);
    }

    private BigDecimal sumOfType(String type) {
        return jdbcTemplate.queryForObject("SELECT COALESCE(SUM(amount), 0) FROM payment.parent_credit_ledger "
            + "WHERE parent_id = ? AND type = ?", BigDecimal.class, PARENT_ID, type);
    }

    private String openAlertReason(String referenceId, String type) {
        return jdbcTemplate.queryForList(
                "SELECT reason FROM admin.admin_alerts WHERE reference_id = ? AND type = ? AND status = 'OPEN'",
                String.class, referenceId, type)
            .stream().findFirst().orElse(null);
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

    private static String id(String prefix) {
        return prefix + UUID.randomUUID().toString().substring(0, 8);
    }

    private void cashOut() {
        creditWalletService.debitForCashOut(PARENT_ID, UUID.randomUUID(), new BigDecimal("9.00"),
            new BigDecimal("1.00"), "pi_cb_last");
    }

    // ── Gap 1 ──

    @Test
    void creditFromADisputedCharge_isReversedOnOpen_cashOutRefused_andRestoredOnceWhenWon() {
        String pi = id("pi_cb_won_");
        UUID booking = seedBooking("CANCELLED");
        seedCardPayment(booking, "50.00", pi);
        ledger("50.00", CreditLedgerEntryType.BOOKING_REFUND, booking);
        assertThat(balance()).isEqualByComparingTo("50.00");

        String disputeId = id("dp_cb_");
        webhook("charge.dispute.created", dispute(disputeId, pi, 5_000, "needs_response"));
        assertThat(balance()).as("the refunded credit is taken back").isEqualByComparingTo("0.00");
        assertThat(sumOfType(CreditLedgerEntryType.CHARGEBACK_CREDIT_REVERSAL)).isEqualByComparingTo("-50.00");

        // A re-delivered open debits nothing more.
        webhook("charge.dispute.created", dispute(disputeId, pi, 5_000, "needs_response"));
        assertThat(sumOfType(CreditLedgerEntryType.CHARGEBACK_CREDIT_REVERSAL)).isEqualByComparingTo("-50.00");

        // Other credit cannot be cashed out while the dispute is open.
        ledger("20.00", CreditLedgerEntryType.BOOKING_DEDUCTION_REVERSAL, UUID.randomUUID());
        assertThatThrownBy(this::cashOut).isInstanceOf(PaymentGatewayException.class)
            .hasMessageContaining("payment.cashOutBlockedByDispute");

        webhook("charge.dispute.closed", dispute(disputeId, pi, 5_000, "won"));
        assertThat(balance()).as("won: the credit is given back").isEqualByComparingTo("70.00");
        webhook("charge.dispute.closed", dispute(disputeId, pi, 5_000, "won"));
        assertThat(sumOfType(CreditLedgerEntryType.CHARGEBACK_CREDIT_RESTORE)).isEqualByComparingTo("50.00");
        assertThat(openAlertReason(pi, "CHARGEBACK")).startsWith("WON").contains("restored=50.00");

        cashOut();
        assertThat(balance()).isEqualByComparingTo("60.00");
    }

    @Test
    void spentCredit_isAShortfallAlert_neverANegativeWallet_andLostKeepsTheReversal() {
        String pi = id("pi_cb_lost_");
        UUID booking = seedBooking("CANCELLED");
        seedCardPayment(booking, "50.00", pi);
        ledger("50.00", CreditLedgerEntryType.BOOKING_REFUND, booking);
        ledger("-30.00", CreditLedgerEntryType.BOOKING_DEDUCTION, UUID.randomUUID());

        String disputeId = id("dp_cb_");
        webhook("charge.dispute.created", dispute(disputeId, pi, 5_000, "needs_response"));
        assertThat(balance()).isEqualByComparingTo("0.00");
        assertThat(sumOfType(CreditLedgerEntryType.CHARGEBACK_CREDIT_REVERSAL)).isEqualByComparingTo("-20.00");
        assertThat(openAlertReason(disputeId, "CHARGEBACK")).isEqualTo("CREDIT_SHORTFALL OPENED 30.00 of 50.00");

        webhook("charge.dispute.closed", dispute(disputeId, pi, 5_000, "lost"));
        assertThat(balance()).isEqualByComparingTo("0.00");
        assertThat(sumOfType(CreditLedgerEntryType.CHARGEBACK_CREDIT_RESTORE)).isEqualByComparingTo("0");
    }

    @Test
    void creditWrittenAfterTheDisputeWasLost_isReversedAtOnce() {
        String pi = id("pi_cb_late_");
        UUID booking = seedBooking("CANCELLED");
        seedCardPayment(booking, "40.00", pi);
        String disputeId = id("dp_cb_");
        webhook("charge.dispute.created", dispute(disputeId, pi, 4_000, "needs_response"));
        webhook("charge.dispute.closed", dispute(disputeId, pi, 4_000, "lost"));

        transactionTemplate.execute(s -> {
            refundOutboxSupport.enqueueBookingRefund(PARENT_ID, new BigDecimal("40.00"), booking, "late refund");
            return null;
        });
        transactionTemplate.execute(s -> jdbcTemplate.update(
            "UPDATE main.outbox_messages SET next_attempt_at = now() - interval '1 minute' "
                + "WHERE payload->>'bookingId' = ?", booking.toString()));
        outboxService.drain();

        assertThat(sumOfType(CreditLedgerEntryType.BOOKING_REFUND)).isEqualByComparingTo("40.00");
        assertThat(sumOfType(CreditLedgerEntryType.CHARGEBACK_CREDIT_REVERSAL)).isEqualByComparingTo("-40.00");
        assertThat(balance()).isEqualByComparingTo("0.00");
    }

    // ── Gap 2 ──

    private CoachPayout completeAndEnqueue(UUID bookingId) {
        transactionTemplate.execute(s -> {
            coachPayoutOutboxSupport.enqueuePayout(bookingId, coachId);
            return null;
        });
        return coachPayoutRepository.findById(bookingId).orElseThrow();
    }

    @Test
    void disputeLostBeforeTheSessionCompleted_payoutIsCutOrCancelled_notHeld() {
        String fullPi = id("pi_cb_full_");
        UUID full = seedBooking("CONFIRMED");
        seedCardPayment(full, "50.00", fullPi);
        String fullDispute = id("dp_cb_");
        webhook("charge.dispute.created", dispute(fullDispute, fullPi, 5_000, "needs_response"));
        webhook("charge.dispute.closed", dispute(fullDispute, fullPi, 5_000, "lost"));
        CoachPayout cancelled = completeAndEnqueue(full);
        assertThat(cancelled.getStatus()).isEqualTo(CoachPayoutStatus.CANCELLED);
        assertThat(cancelled.getLastError()).isEqualTo("CHARGEBACK_LOST");

        String partPi = id("pi_cb_part_");
        UUID part = seedBooking("CONFIRMED");
        seedCardPayment(part, "50.00", partPi);
        String partDispute = id("dp_cb_");
        webhook("charge.dispute.created", dispute(partDispute, partPi, 2_500, "needs_response"));
        webhook("charge.dispute.closed", dispute(partDispute, partPi, 2_500, "lost"));
        assertThat(jdbcTemplate.queryForObject(
            "SELECT payout_loss_fraction FROM payment.charge_disputes WHERE stripe_dispute_id = ?",
            BigDecimal.class, partDispute)).isEqualByComparingTo("0.5");
        CoachPayout cut = completeAndEnqueue(part);
        assertThat(cut.getStatus()).isEqualTo(CoachPayoutStatus.PENDING_RELEASE);
        assertThat(cut.getNetAmount()).isEqualByComparingTo("22.50");
    }
}
