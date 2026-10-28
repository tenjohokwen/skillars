package com.softropic.skillars.platform.payment.service;

import com.softropic.skillars.platform.admin.contract.CoachReinstatedEvent;
import com.softropic.skillars.platform.admin.service.DisputeService;
import com.softropic.skillars.platform.outbox.service.OutboxService;
import com.softropic.skillars.platform.payment.BasePaymentIT;
import com.softropic.skillars.platform.payment.contract.CoachPayoutStatus;
import com.softropic.skillars.platform.payment.repo.CoachPayout;
import com.softropic.skillars.platform.payment.repo.CoachPayoutRepository;
import com.softropic.skillars.platform.security.contract.event.AccountSuspensionRequestedEvent;
import com.stripe.model.Event;
import com.stripe.net.ApiResource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * fix/payment-payouts-disputes-webhooks, against a real database:
 * <ul>
 *   <li>item 2 — an open in-app dispute freezes the not-yet-released payout (the 48h hold is shorter than
 *       the 14-day dispute window), and a safety-hold reinstatement does not release it while the dispute
 *       stands;</li>
 *   <li>item 3 — a partial credit cuts the pending payout pro rata and lets it release, instead of
 *       cancelling all of it;</li>
 *   <li>item 1 — a card dispute on a batch charge freezes the unreleased payouts it funded, alerts, and a
 *       lost dispute cuts / reverses each coach's share pro rata (a partial reversal keeps the row
 *       RELEASED with {@code reversed_amount}).</li>
 * </ul>
 * Uses the shared {@code StubPaymentGateway} spy; no new mock beans.
 */
class PayoutDisputeIntegrityIT extends BasePaymentIT {

    private static final long PARENT_ID = 98201L;
    private static final long PLAYER_ID = 98202L;
    private static final long COACH_USER_ID = 98203L;

    @Autowired CoachPayoutOutboxSupport coachPayoutOutboxSupport;
    @Autowired CoachPayoutFreezeService coachPayoutFreezeService;
    @Autowired CoachPayoutRepository coachPayoutRepository;
    @Autowired OutboxService outboxService;
    @Autowired DisputeService disputeService;
    @Autowired StripeWebhookService stripeWebhookService;
    @Autowired ApplicationEventPublisher eventPublisher;

    private UUID coachId;
    private int slot;

    @BeforeEach
    void seed() {
        coachId = insertTestCoach(COACH_USER_ID, "payout_dispute_coach@test.com", "Dispute Coach");
        insertTestParent(PARENT_ID, "payout_dispute_parent@test.com");
        insertTestPlayer(PLAYER_ID, PARENT_ID);
    }

    // ── fixtures ──

    private UUID seedCompletedBooking() {
        UUID bookingId = UUID.randomUUID();
        Instant start = Instant.now().minus(2 * (++slot), ChronoUnit.HOURS);
        transactionTemplate.execute(s -> jdbcTemplate.update(
            "INSERT INTO booking.bookings (id, parent_id, player_id, coach_id, requested_start_time, requested_end_time, "
                + "status, canonical_timezone, version, created_at, updated_at, status_changed_at) "
                + "VALUES (?, ?, ?, ?, ?, ?, 'COMPLETED', 'UTC', 0, now(), now(), now())",
            bookingId, PARENT_ID, PLAYER_ID, coachId, Timestamp.from(start),
            Timestamp.from(start.plus(1, ChronoUnit.HOURS))));
        return bookingId;
    }

    private void seedCapturedPayment(UUID bookingId, String card, String paymentIntentId) {
        transactionTemplate.execute(s -> jdbcTemplate.update(
            "INSERT INTO payment.booking_payments (booking_id, credit_debited, stripe_charged, status, captured_at, "
                + "commission_rate, stripe_payment_intent_id) VALUES (?, 0, ?::numeric, 'CAPTURED', now(), 0.10, ?)",
            bookingId, card, paymentIntentId));
    }

    private void enqueue(UUID bookingId) {
        transactionTemplate.execute(s -> {
            coachPayoutOutboxSupport.enqueuePayout(bookingId, coachId);
            return null;
        });
    }

    private void releaseHoldAndDrain(UUID bookingId) {
        transactionTemplate.execute(s -> {
            jdbcTemplate.update("UPDATE payment.coach_payouts SET release_after = ? WHERE booking_id = ?",
                Timestamp.from(Instant.now().minus(1, ChronoUnit.HOURS)), bookingId);
            jdbcTemplate.update("UPDATE main.outbox_messages SET next_attempt_at = now() - interval '1 minute' "
                + "WHERE payload->>'bookingId' = ?", bookingId.toString());
            return null;
        });
        outboxService.drain();
    }

    private CoachPayout payout(UUID bookingId) {
        return coachPayoutRepository.findById(bookingId).orElseThrow();
    }

    private String openAlertReason(String referenceId, String type) {
        return jdbcTemplate.queryForList(
                "SELECT reason FROM admin.admin_alerts WHERE reference_id = ? AND type = ? AND status = 'OPEN'",
                String.class, referenceId, type)
            .stream().findFirst().orElse(null);
    }

    private void webhook(String id, String type, String object) {
        Event event = ApiResource.GSON.fromJson("""
            {"id": "%s", "object": "event", "type": "%s", "created": %d, "data": {"object": %s}}
            """.formatted(id, type, Instant.now().getEpochSecond(), object), Event.class);
        stripeWebhookService.handleEventAtomically(event);
    }

    private static String dispute(String id, String pi, long amountCents, String status) {
        return """
            {"id": "%s", "object": "dispute", "payment_intent": "%s", "charge": "ch_it", "amount": %d,
             "currency": "eur", "status": "%s", "created": %d}
            """.formatted(id, pi, amountCents, status, Instant.now().getEpochSecond());
    }

    // ── items 2 + 3: in-app dispute ──

    @Test
    void openDispute_freezesPayout_safetyHoldLiftDoesNotRelease_partialCreditCutsItProRataAndReleases() {
        UUID bookingId = seedCompletedBooking();
        seedCapturedPayment(bookingId, "50.00", "pi_it_" + bookingId);
        enqueue(bookingId);
        assertThat(payout(bookingId).getStatus()).isEqualTo(CoachPayoutStatus.PENDING_RELEASE);

        UUID disputeId = disputeService.raiseDispute(bookingId, "SESSION_QUALITY", "late", PARENT_ID, "PARENT");
        assertThat(payout(bookingId).getStatus()).isEqualTo(CoachPayoutStatus.FROZEN);
        assertThat(payout(bookingId).isDisputeFrozen()).isTrue();

        // Past the 48h hold, the dispute still stops the money.
        releaseHoldAndDrain(bookingId);
        assertThat(payout(bookingId).getStatus()).isEqualTo(CoachPayoutStatus.FROZEN);
        verify(paymentGateway, never()).transferToCoach(eq(bookingId), any(), any(), any());

        // A safety hold on top, then reinstatement: the safety freeze lifts, the dispute freeze stands.
        coachPayoutFreezeService.onAccountSuspensionRequested(
            new AccountSuspensionRequestedEvent(coachId.toString(), UUID.randomUUID()));
        transactionTemplate.execute(s -> {
            eventPublisher.publishEvent(new CoachReinstatedEvent(this, coachId, 1L));
            return null;
        });
        assertThat(payout(bookingId).getStatus()).as("the dispute still holds it").isEqualTo(CoachPayoutStatus.FROZEN);

        // 10.00 of 50.00 credited back -> the coach bears a fifth of the 45.00 net.
        disputeService.resolveDispute(disputeId, "PARTIAL_CREDIT", new BigDecimal("10.00"), "partly late", 1L);
        CoachPayout cut = payout(bookingId);
        assertThat(cut.isDisputeFrozen()).isFalse();
        assertThat(cut.getNetAmount()).isEqualByComparingTo("36.00");
        assertThat(cut.getGrossAmount()).isEqualByComparingTo("40.00");
        assertThat(cut.getCommissionAmount()).isEqualByComparingTo("4.00");
        assertThat(cut.getStatus()).isIn(CoachPayoutStatus.PENDING_RELEASE, CoachPayoutStatus.RELEASED);

        releaseHoldAndDrain(bookingId);
        assertThat(payout(bookingId).getStatus()).isEqualTo(CoachPayoutStatus.RELEASED);
        verify(paymentGateway).transferToCoach(eq(bookingId), eq(coachId), eq(new BigDecimal("36.00")), any());
    }

    @Test
    void dismissedDispute_releasesTheWholePayout() {
        UUID bookingId = seedCompletedBooking();
        seedCapturedPayment(bookingId, "50.00", "pi_it_" + bookingId);
        enqueue(bookingId);
        UUID disputeId = disputeService.raiseDispute(bookingId, "OTHER", "x", PARENT_ID, "PARENT");
        assertThat(payout(bookingId).getStatus()).isEqualTo(CoachPayoutStatus.FROZEN);

        disputeService.dismissDispute(disputeId, "no merit", 1L);
        releaseHoldAndDrain(bookingId);

        assertThat(payout(bookingId).getStatus()).isEqualTo(CoachPayoutStatus.RELEASED);
        assertThat(payout(bookingId).getNetAmount()).isEqualByComparingTo("45.00");
    }

    // ── item 1: card dispute on a batch charge ──

    @Test
    void chargeback_onBatchCharge_freezesUnreleased_alerts_andLostCutsAndReversesProRata() {
        String pi = "pi_it_batch_" + UUID.randomUUID().toString().substring(0, 8);
        UUID released = seedCompletedBooking();
        UUID pending = seedCompletedBooking();
        seedCapturedPayment(released, "50.00", pi);
        seedCapturedPayment(pending, "50.00", pi);
        enqueue(released);
        releaseHoldAndDrain(released);
        enqueue(pending);
        assertThat(payout(released).getStatus()).isEqualTo(CoachPayoutStatus.RELEASED);
        assertThat(payout(pending).getStatus()).isEqualTo(CoachPayoutStatus.PENDING_RELEASE);

        String disputeId = "dp_it_" + UUID.randomUUID().toString().substring(0, 8);
        webhook("evt_" + UUID.randomUUID(), "charge.dispute.created", dispute(disputeId, pi, 5_000, "needs_response"));

        assertThat(payout(pending).getStatus()).isEqualTo(CoachPayoutStatus.FROZEN);
        assertThat(payout(pending).isChargebackFrozen()).isTrue();
        assertThat(payout(released).getStatus()).as("released: alert only").isEqualTo(CoachPayoutStatus.RELEASED);
        assertThat(openAlertReason(pi, "CHARGEBACK")).startsWith("OPENED").contains("frozen=1").contains("released=1");
        releaseHoldAndDrain(pending);
        verify(paymentGateway, never()).transferToCoach(eq(pending), any(), any(), any());

        // 50.00 of the 100.00 batch charge lost -> each payout loses half of its 45.00 net.
        webhook("evt_" + UUID.randomUUID(), "charge.dispute.closed", dispute(disputeId, pi, 5_000, "lost"));
        assertThat(payout(pending).getNetAmount()).isEqualByComparingTo("22.50");
        assertThat(payout(pending).isChargebackFrozen()).isFalse();
        assertThat(openAlertReason(pi, "CHARGEBACK")).startsWith("LOST").contains("adjusted=2");

        releaseHoldAndDrain(released);
        releaseHoldAndDrain(pending);
        CoachPayout reversed = payout(released);
        assertThat(reversed.getStatus()).as("partial reversal keeps it RELEASED").isEqualTo(CoachPayoutStatus.RELEASED);
        assertThat(reversed.getReversedAmount()).isEqualByComparingTo("22.50");
        verify(paymentGateway).reverseTransfer(eq("tr_stub_" + released), eq(new BigDecimal("22.50")), any());
        assertThat(payout(pending).getStatus()).isEqualTo(CoachPayoutStatus.RELEASED);
        verify(paymentGateway).transferToCoach(eq(pending), any(), eq(new BigDecimal("22.50")), any());

        // Earnings count the coach's remaining net on the partially reversed payout.
        assertThat(coachPayoutRepository.sumReleasedNetByCoachAndPeriod(coachId,
            Instant.now().minus(1, ChronoUnit.DAYS), Instant.now().plus(1, ChronoUnit.DAYS)))
            .isEqualByComparingTo("45.00");

        // A re-delivered close (new event id) changes nothing.
        webhook("evt_" + UUID.randomUUID(), "charge.dispute.closed", dispute(disputeId, pi, 5_000, "lost"));
        assertThat(payout(released).getReversedAmount()).isEqualByComparingTo("22.50");
    }

    @Test
    void chargeback_wonLiftsTheFreeze_unknownIntentAndExternalRefundOnlyAlert() {
        String pi = "pi_it_won_" + UUID.randomUUID().toString().substring(0, 8);
        UUID bookingId = seedCompletedBooking();
        seedCapturedPayment(bookingId, "50.00", pi);
        enqueue(bookingId);
        String disputeId = "dp_it_" + UUID.randomUUID().toString().substring(0, 8);
        webhook("evt_" + UUID.randomUUID(), "charge.dispute.created", dispute(disputeId, pi, 5_000, "needs_response"));
        assertThat(payout(bookingId).getStatus()).isEqualTo(CoachPayoutStatus.FROZEN);

        webhook("evt_" + UUID.randomUUID(), "charge.dispute.closed", dispute(disputeId, pi, 5_000, "won"));
        releaseHoldAndDrain(bookingId);
        assertThat(payout(bookingId).getStatus()).isEqualTo(CoachPayoutStatus.RELEASED);
        assertThat(payout(bookingId).getNetAmount()).isEqualByComparingTo("45.00");
        assertThat(openAlertReason(pi, "CHARGEBACK")).startsWith("WON");

        // Never throws for a charge the app does not know (a throw would make Stripe retry forever).
        String unknown = "pi_it_unknown_" + UUID.randomUUID().toString().substring(0, 8);
        webhook("evt_" + UUID.randomUUID(), "charge.dispute.created",
            dispute("dp_it_" + UUID.randomUUID().toString().substring(0, 8), unknown, 1_000, "needs_response"));
        assertThat(openAlertReason(unknown, "CHARGEBACK")).startsWith("OPENED_UNKNOWN_INTENT");

        webhook("evt_" + UUID.randomUUID(), "charge.refunded", """
            {"id": "ch_it_ext", "object": "charge", "payment_intent": "%s", "amount": 5000, "amount_refunded": 2000}
            """.formatted(pi));
        assertThat(openAlertReason(pi, "EXTERNAL_REFUND")).isEqualTo("refunded 20.00 of 50.00");
        assertThat(payout(bookingId).getStatus()).as("no money moved").isEqualTo(CoachPayoutStatus.RELEASED);
    }
}
