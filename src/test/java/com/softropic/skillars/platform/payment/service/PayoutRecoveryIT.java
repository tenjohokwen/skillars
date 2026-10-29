package com.softropic.skillars.platform.payment.service;

import com.softropic.skillars.platform.admin.service.DisputeService;
import com.softropic.skillars.platform.outbox.service.OutboxService;
import com.softropic.skillars.platform.payment.BasePaymentIT;
import com.softropic.skillars.platform.payment.contract.CoachPayoutStatus;
import com.softropic.skillars.platform.payment.contract.event.CoachStripeOnboardingCompleteEvent;
import com.softropic.skillars.platform.payment.contract.exception.CoachPayoutTransferException;
import com.softropic.skillars.platform.payment.repo.CoachPayout;
import com.softropic.skillars.platform.payment.repo.CoachPayoutRepository;
import com.softropic.skillars.platform.payment.repo.ParentCreditLedgerRepository;
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
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * fix/payment-payout-recovery, against a real database (V205), through the real outbox handlers and the shared
 * {@code StubPaymentGateway} spy — no new mock beans:
 * <ul>
 *   <li>payouts H1 — UPHOLD_COACH after a FULL_CREDIT reversed the released payout pays the coach the full net
 *       again, by a top-up transfer;</li>
 *   <li>payouts H3 — a HOLD payout is re-driven automatically once the coach's account can receive transfers,
 *       and by the admin re-drive; a mismatch HOLD is left for the operator;</li>
 *   <li>payouts M5 / stripe M5 — a reversal Stripe refuses becomes coach debt, repaid from the coach's next
 *       payout;</li>
 *   <li>payouts L1 / L2 / L3 — the keyset and revenue queries run against the real schema.</li>
 * </ul>
 */
class PayoutRecoveryIT extends BasePaymentIT {

    private static final long PARENT_ID = 98301L;
    private static final long PLAYER_ID = 98302L;
    private static final long COACH_USER_ID = 98303L;

    @Autowired CoachPayoutOutboxSupport coachPayoutOutboxSupport;
    @Autowired CoachPayoutRepository coachPayoutRepository;
    @Autowired CoachPayoutHoldRecoveryService holdRecoveryService;
    @Autowired ParentCreditLedgerRepository parentCreditLedgerRepository;
    @Autowired OutboxService outboxService;
    @Autowired DisputeService disputeService;
    @Autowired ApplicationEventPublisher eventPublisher;

    private UUID coachId;
    private int slot;

    @BeforeEach
    void seed() {
        coachId = insertTestCoach(COACH_USER_ID, "payout_recovery_coach@test.com", "Recovery Coach");
        insertTestParent(PARENT_ID, "payout_recovery_parent@test.com");
        insertTestPlayer(PLAYER_ID, PARENT_ID);
    }

    // ── fixtures ──

    private UUID completedAndCharged() {
        UUID bookingId = UUID.randomUUID();
        Instant start = Instant.now().minus(2 * (++slot), ChronoUnit.HOURS);
        transactionTemplate.execute(s -> {
            jdbcTemplate.update(
                "INSERT INTO booking.bookings (id, parent_id, player_id, coach_id, requested_start_time, "
                    + "requested_end_time, status, canonical_timezone, version, created_at, updated_at, status_changed_at) "
                    + "VALUES (?, ?, ?, ?, ?, ?, 'COMPLETED', 'UTC', 0, now(), now(), now())",
                bookingId, PARENT_ID, PLAYER_ID, coachId, Timestamp.from(start),
                Timestamp.from(start.plus(1, ChronoUnit.HOURS)));
            jdbcTemplate.update(
                "INSERT INTO payment.booking_payments (booking_id, credit_debited, stripe_charged, status, captured_at, "
                    + "commission_rate, stripe_payment_intent_id) VALUES (?, 0, 50.00, 'CAPTURED', now(), 0.10, ?)",
                bookingId, "pi_recovery_" + bookingId);
            return null;
        });
        transactionTemplate.execute(s -> {
            coachPayoutOutboxSupport.enqueuePayout(bookingId, coachId);
            return null;
        });
        return bookingId;
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

    private void connectAccount(String onboarding, String capability) {
        transactionTemplate.execute(s -> jdbcTemplate.update(
            "INSERT INTO payment.coach_stripe_accounts (coach_id, version, stripe_account_id, onboarding_status, "
                + "charges_enabled, payouts_enabled, updated_at, transfers_capability) "
                + "VALUES (?, 0, 'acct_recovery', ?, true, true, now(), ?) "
                + "ON CONFLICT (coach_id) DO UPDATE SET onboarding_status = EXCLUDED.onboarding_status, "
                + "transfers_capability = EXCLUDED.transfers_capability",
            coachId, onboarding, capability));
    }

    private CoachPayout payout(UUID bookingId) {
        return coachPayoutRepository.findById(bookingId).orElseThrow();
    }

    private static CoachPayoutTransferException hold(String reason) {
        return new CoachPayoutTransferException("payment.coachTransferFailed", false, reason,
            new IllegalStateException(reason));
    }

    // ── H1: UPHOLD_COACH tops a reversed payout up to the full net ──

    @Test
    void upholdCoach_afterAFullCreditReversedThePayout_topsTheCoachUpToTheFullNet() {
        UUID bookingId = completedAndCharged();
        releaseHoldAndDrain(bookingId);
        assertThat(payout(bookingId).getStatus()).isEqualTo(CoachPayoutStatus.RELEASED);
        assertThat(payout(bookingId).getOriginalNetAmount()).isEqualByComparingTo("45.00");

        UUID parentDispute = disputeService.raiseDispute(bookingId, "OTHER", "no-show", PARENT_ID, "PARENT");
        disputeService.resolveDispute(parentDispute, "FULL_CREDIT", null, "refund", 1L);
        releaseHoldAndDrain(bookingId);
        assertThat(payout(bookingId).getStatus()).isEqualTo(CoachPayoutStatus.REVERSED);

        UUID coachDispute = disputeService.raiseDispute(bookingId, "OTHER", "I was there", COACH_USER_ID, "COACH");
        disputeService.resolveDispute(coachDispute, "UPHOLD_COACH", null, "GPS check-in", 1L);
        releaseHoldAndDrain(bookingId);

        CoachPayout row = payout(bookingId);
        assertThat(row.getStatus()).isEqualTo(CoachPayoutStatus.RELEASED);
        assertThat(row.getEffectiveNetAmount()).isEqualByComparingTo("45.00");
        assertThat(row.getTopupAmount()).isEqualByComparingTo("45.00");
        assertThat(row.getTopupTransferId()).isNotNull().isNotEqualTo(row.getStripeTransferId());
        verify(paymentGateway).transferToCoach(argThat(group -> !bookingId.equals(group)), eq(coachId),
            eq(new BigDecimal("45.00")), any());

        // A re-delivered top-up message changes nothing (nothing is owed any more).
        releaseHoldAndDrain(bookingId);
        assertThat(payout(bookingId).getTopupAmount()).isEqualByComparingTo("45.00");
    }

    // ── H3: HOLD recovery ──

    @Test
    void heldPayout_isReDrivenWhenTheAccountCanReceive_andByTheAdmin_butAMismatchHoldIsLeftAlone() {
        UUID held = completedAndCharged();
        UUID mismatch = completedAndCharged();
        doThrow(hold("ACCOUNT_NOT_READY")).when(paymentGateway).transferToCoach(eq(held), any(), any(), any());
        doThrow(hold(StripePaymentGateway.REASON_AMOUNT_MISMATCH))
            .when(paymentGateway).transferToCoach(eq(mismatch), any(), any(), any());
        releaseHoldAndDrain(held);
        releaseHoldAndDrain(mismatch);
        assertThat(payout(held).getStatus()).isEqualTo(CoachPayoutStatus.HOLD);
        assertThat(payout(mismatch).getStatus()).isEqualTo(CoachPayoutStatus.HOLD);

        // L2: the coach sees the held money.
        Instant from = Instant.now().minus(1, ChronoUnit.DAYS);
        Instant to = Instant.now().plus(1, ChronoUnit.DAYS);
        assertThat(coachPayoutRepository.sumHeldNetByCoachAndPeriod(coachId, from, to)).isEqualByComparingTo("90.00");
        assertThat(coachPayoutRepository.countHeldByCoachAndPeriod(coachId, from, to)).isEqualTo(2L);

        // Still restricted: the automatic path does nothing.
        connectAccount("RESTRICTED", "inactive");
        eventPublisher.publishEvent(new CoachStripeOnboardingCompleteEvent(coachId));
        assertThat(payout(held).getStatus()).isEqualTo(CoachPayoutStatus.HOLD);

        // The account can receive again: the held payout goes back on the release path, the mismatch does not.
        org.mockito.Mockito.reset(paymentGateway);
        connectAccount("COMPLETE", "active");
        eventPublisher.publishEvent(new CoachStripeOnboardingCompleteEvent(coachId));
        // The re-enqueued transfer may already have been drained after commit.
        assertThat(payout(held).getStatus()).isIn(CoachPayoutStatus.PENDING_RELEASE, CoachPayoutStatus.RELEASED);
        assertThat(payout(mismatch).getStatus()).isEqualTo(CoachPayoutStatus.HOLD);
        releaseHoldAndDrain(held);
        assertThat(payout(held).getStatus()).isEqualTo(CoachPayoutStatus.RELEASED);
        verify(paymentGateway, never()).transferToCoach(eq(mismatch), any(), any(), any());

        // The admin re-drive is safe to repeat and also skips the mismatch.
        CoachPayoutHoldRecoveryService.Result r = holdRecoveryService.redriveHeld(coachId, false, "IT");
        assertThat(r.released()).isZero();
        assertThat(r.skipped()).isEqualTo(1);
        assertThat(payout(mismatch).getStatus()).isEqualTo(CoachPayoutStatus.HOLD);
    }

    @Test
    void adminReDrive_ofAHoldStillUnderADispute_freezesItInsteadOfReleasing() {
        UUID held = completedAndCharged();
        doThrow(hold("ACCOUNT_NOT_READY")).when(paymentGateway).transferToCoach(eq(held), any(), any(), any());
        releaseHoldAndDrain(held);
        disputeService.raiseDispute(held, "OTHER", "late", PARENT_ID, "PARENT");
        assertThat(payout(held).getStatus()).isEqualTo(CoachPayoutStatus.HOLD);

        CoachPayoutHoldRecoveryService.Result r = holdRecoveryService.redriveHeld(coachId, false, "IT");

        assertThat(r.stillFrozen()).isEqualTo(1);
        assertThat(payout(held).getStatus()).isEqualTo(CoachPayoutStatus.FROZEN);
        assertThat(payout(held).getLastError()).isEqualTo(CoachPayoutAdjustmentService.REASON_DISPUTE_OPEN);
    }

    // ── M5: a refused reversal is coach debt, repaid from the next payout ──

    @Test
    void refusedReversal_isRecordedAsCoachDebt_andRepaidFromTheNextPayout() {
        UUID first = completedAndCharged();
        releaseHoldAndDrain(first);
        doThrow(hold("COACH_BALANCE_INSUFFICIENT")).when(paymentGateway).reverseTransfer(any(), any(), any());

        UUID dispute = disputeService.raiseDispute(first, "OTHER", "no-show", PARENT_ID, "PARENT");
        disputeService.resolveDispute(dispute, "FULL_CREDIT", null, "refund", 1L);
        releaseHoldAndDrain(first);

        CoachPayout reversed = payout(first);
        assertThat(reversed.getStatus()).isEqualTo(CoachPayoutStatus.REVERSED);
        assertThat(reversed.getLastError()).startsWith("REVERSAL_DEBT COACH_BALANCE_INSUFFICIENT");
        assertThat(jdbcTemplate.queryForObject("SELECT amount FROM payment.coach_payout_debts WHERE booking_id = ? "
            + "AND status = 'OPEN'", BigDecimal.class, first)).isEqualByComparingTo("45.00");
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM admin.admin_alerts WHERE reference_id = ? "
            + "AND type = 'PAYOUT_REVERSAL_FAILED' AND status = 'OPEN'", Long.class, first.toString())).isEqualTo(1L);

        // The coach's next session (net 45.00) repays it entirely: no Stripe transfer, the row says why.
        UUID second = completedAndCharged();
        releaseHoldAndDrain(second);
        CoachPayout next = payout(second);
        assertThat(next.getStatus()).isEqualTo(CoachPayoutStatus.RELEASED);
        assertThat(next.getDebtOffsetAmount()).isEqualByComparingTo("45.00");
        assertThat(next.getStripeTransferId()).isNull();
        verify(paymentGateway, never()).transferToCoach(eq(second), any(), any(), any());
        assertThat(jdbcTemplate.queryForObject("SELECT status FROM payment.coach_payout_debts WHERE booking_id = ?",
            String.class, first)).isEqualTo("RECOVERED");
    }

    // ── L1 / L2: keyset paging and the coach refund figure against the real schema ──

    @Test
    void staleKeysetAndRefundQueries_runAgainstTheSchema() {
        UUID a = completedAndCharged();
        UUID b = completedAndCharged();
        doThrow(hold("ACCOUNT_NOT_READY")).when(paymentGateway).transferToCoach(any(), any(), any(), any());
        releaseHoldAndDrain(a);
        releaseHoldAndDrain(b);
        transactionTemplate.execute(s -> jdbcTemplate.update(
            "UPDATE payment.coach_payouts SET updated_at = now() - interval '30 days' WHERE booking_id IN (?, ?)", a, b));
        Instant cutoff = Instant.now().minus(14, ChronoUnit.DAYS);

        var first = coachPayoutRepository.findStaleUnreleased(cutoff, org.springframework.data.domain.PageRequest.of(0, 1));
        assertThat(first).hasSize(1);
        var rest = coachPayoutRepository.findStaleUnreleasedAfter(cutoff, first.get(0).getUpdatedAt(),
            first.get(0).getBookingId(), 10);
        assertThat(rest).extracting(CoachPayout::getBookingId)
            .contains(first.get(0).getBookingId().equals(a) ? b : a)
            .doesNotContain(first.get(0).getBookingId());

        transactionTemplate.execute(s -> jdbcTemplate.update(
            "INSERT INTO payment.parent_credit_ledger (parent_id, amount, type, reference_id, description) "
                + "VALUES (?, 20.00, 'BOOKING_REFUND', ?, 'it')", PARENT_ID, a));
        assertThat(parentCreditLedgerRepository.sumRefundsByCoachAndPeriod(coachId,
            Instant.now().minus(1, ChronoUnit.DAYS), Instant.now().plus(1, ChronoUnit.DAYS)))
            .isEqualByComparingTo("20.00");
    }
}
