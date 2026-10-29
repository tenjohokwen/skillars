package com.softropic.skillars.platform.payment.service;

import com.softropic.skillars.platform.admin.service.DisputeService;
import com.softropic.skillars.platform.booking.service.BookingService;
import com.softropic.skillars.platform.notification.contract.EmailTemplate;
import com.softropic.skillars.platform.outbox.service.OutboxService;
import com.softropic.skillars.platform.payment.BasePaymentIT;
import com.softropic.skillars.platform.payment.contract.CoachPayoutStatus;
import com.softropic.skillars.platform.payment.repo.CoachPayout;
import com.softropic.skillars.platform.payment.repo.CoachPayoutRepository;
import com.softropic.skillars.utils.TestMailManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;

/**
 * feat/payment-forfeit-payouts-and-clawback, against a real database:
 * <ul>
 *   <li>a player no-show and a late (non-refundable) parent cancellation pay the coach through the normal
 *       coach_payouts row + COACH_PAYOUT_TRANSFER, after the hold;</li>
 *   <li>an UPHOLD_COACH dispute resolution claws the parent's refund back — capped at the wallet balance,
 *       never below zero — and pays the coach the full net either way.</li>
 * </ul>
 * Uses the shared {@code StubPaymentGateway} spy; no new mock beans.
 */
class ForfeitPayoutAndClawbackIT extends BasePaymentIT {

    private static final long COACH_USER_ID = 98303L;
    /** A fresh payer per test: the ledger is not cleaned between tests and balances are asserted absolutely. */
    private static final java.util.concurrent.atomic.AtomicLong NEXT_PARENT = new java.util.concurrent.atomic.AtomicLong(983100L);

    @Autowired BookingService bookingService;
    @Autowired DisputeService disputeService;
    @Autowired CoachPayoutRepository coachPayoutRepository;
    @Autowired OutboxService outboxService;
    @Autowired TestMailManager testMailManager;

    private UUID coachId;
    private long parentId;
    private long playerId;

    @BeforeEach
    void seed() {
        coachId = insertTestCoach(COACH_USER_ID, "forfeit_coach@test.com", "Forfeit Coach");
        parentId = NEXT_PARENT.getAndAdd(2);
        playerId = parentId + 1;
        insertTestParent(parentId, parentEmail());
        insertTestPlayer(playerId, parentId);
    }

    // ── fixtures ──

    private String parentEmail() {
        return "forfeit_parent_" + parentId + "@test.com";
    }

    /**
     * fix/forfeit-family-emails: the family's forfeit notices of {@code template} for this booking's payer. The
     * outbox drain runs on a SyncTaskExecutor under the test profile, so the email has been handed to the
     * TestMailManager by the time the service call returns; the in-app row commits with the business write.
     */
    private long familyEmails(EmailTemplate template) {
        return testMailManager.getEnvelopes().values().stream()
            .filter(e -> e.emailTemplate() == template)
            .filter(e -> e.recipients().stream().anyMatch(r -> parentEmail().equals(r.getEmail())))
            .count();
    }

    private int familyInboxRows(EmailTemplate template) {
        return jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM main.user_notifications WHERE recipient_user_id = ? AND type = ?",
            Integer.class, parentId, template.name());
    }

    private UUID seedBooking(String status, Instant start) {
        UUID bookingId = UUID.randomUUID();
        transactionTemplate.execute(s -> jdbcTemplate.update(
            "INSERT INTO booking.bookings (id, parent_id, player_id, coach_id, requested_start_time, requested_end_time, "
                + "status, canonical_timezone, version, created_at, updated_at, status_changed_at) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, 'UTC', 0, now(), now(), now())",
            bookingId, parentId, playerId, coachId, Timestamp.from(start),
            Timestamp.from(start.plus(1, ChronoUnit.HOURS)), status));
        transactionTemplate.execute(s -> jdbcTemplate.update(
            "INSERT INTO payment.booking_payments (booking_id, credit_debited, stripe_charged, status, captured_at, "
                + "commission_rate, stripe_payment_intent_id) VALUES (?, 0, 50.00, 'CAPTURED', now(), 0.10, ?)",
            bookingId, "pi_forfeit_" + bookingId));
        return bookingId;
    }

    private void ledger(String amount, String type, UUID referenceId) {
        transactionTemplate.execute(s -> jdbcTemplate.update(
            "INSERT INTO payment.parent_credit_ledger (parent_id, amount, type, reference_id, description) "
                + "VALUES (?, ?::numeric, ?, ?, 'it')", parentId, amount, type, referenceId));
    }

    private BigDecimal balance() {
        return jdbcTemplate.queryForObject(
            "SELECT COALESCE(SUM(amount), 0) FROM payment.parent_credit_ledger WHERE parent_id = ?",
            BigDecimal.class, parentId);
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

    // ── forfeits pay the coach ──

    @Test
    void playerNoShow_paysTheCoachAfterTheHold() {
        UUID bookingId = seedBooking("UPCOMING", Instant.now().minus(2, ChronoUnit.HOURS));

        bookingService.recordNoShowPlayer(bookingId, COACH_USER_ID);

        // fix/forfeit-family-emails: the payer (Booking.parentId — the player row is a PlayerProfile id) is told.
        assertThat(familyEmails(EmailTemplate.SESSION_FORFEITED_NO_SHOW)).isEqualTo(1);
        assertThat(familyInboxRows(EmailTemplate.SESSION_FORFEITED_NO_SHOW)).isEqualTo(1);

        CoachPayout row = payout(bookingId);
        assertThat(row.getStatus()).isEqualTo(CoachPayoutStatus.PENDING_RELEASE);
        assertThat(row.getNetAmount()).isEqualByComparingTo("45.00");
        assertThat(row.getReleaseAfter()).as("the usual release hold").isAfter(Instant.now());

        releaseHoldAndDrain(bookingId);
        assertThat(payout(bookingId).getStatus()).isEqualTo(CoachPayoutStatus.RELEASED);
        verify(paymentGateway).transferToCoach(eq(bookingId), eq(coachId), eq(new BigDecimal("45.00")), any());
    }

    @Test
    void lateParentCancel_isAForfeitThatPaysTheCoach_andADisputeFreezesIt() {
        UUID bookingId = seedBooking("CONFIRMED", Instant.now().plus(2, ChronoUnit.HOURS));

        bookingService.cancelBookingAsParent(bookingId, parentId);

        assertThat(payout(bookingId).getStatus()).isEqualTo(CoachPayoutStatus.PENDING_RELEASE);
        assertThat(payout(bookingId).getNetAmount()).isEqualByComparingTo("45.00");
        assertThat(balance()).as("no refund on a <24h cancel").isEqualByComparingTo("0");
        // fix/forfeit-family-emails: the family's only email for its own cancellation states the forfeit.
        assertThat(familyEmails(EmailTemplate.SESSION_FORFEITED_LATE_CANCEL)).isEqualTo(1);
        assertThat(familyInboxRows(EmailTemplate.SESSION_FORFEITED_LATE_CANCEL)).isEqualTo(1);

        // CANCELLED_PARENT is dispute-eligible; the open dispute freezes the forfeit payout.
        disputeService.raiseDispute(bookingId, "OTHER", "coach agreed to waive", parentId, "PARENT");
        assertThat(payout(bookingId).getStatus()).isEqualTo(CoachPayoutStatus.FROZEN);
    }

    @Test
    void refundedParentCancel_isNotAForfeit_andSendsTheFamilyNoForfeitNotice() {
        UUID bookingId = seedBooking("CONFIRMED", Instant.now().plus(3, ChronoUnit.DAYS));

        bookingService.cancelBookingAsParent(bookingId, parentId);

        assertThat(coachPayoutRepository.findById(bookingId)).as(">24h notice: refunded, coach not paid").isEmpty();
        assertThat(familyEmails(EmailTemplate.SESSION_FORFEITED_LATE_CANCEL)).isZero();
        assertThat(familyInboxRows(EmailTemplate.SESSION_FORFEITED_LATE_CANCEL)).isZero();
    }

    // ── UPHOLD_COACH clawback ──

    @Test
    void upholdCoach_sufficientBalance_reversesTheWholeRefund_andPaysTheCoach() {
        UUID bookingId = seedBooking("NO_SHOW_COACH", Instant.now().minus(3, ChronoUnit.HOURS));
        ledger("50.00", "BOOKING_REFUND", bookingId);
        ledger("30.00", "BOOKING_REFUND", UUID.randomUUID()); // other credit in the wallet
        UUID disputeId = disputeService.raiseDispute(bookingId, "OTHER", "I was there", COACH_USER_ID, "COACH");

        disputeService.resolveDispute(disputeId, "UPHOLD_COACH", null, "GPS check-in confirms the session", 1L);

        assertThat(balance()).isEqualByComparingTo("30.00");
        assertThat(jdbcTemplate.queryForObject("SELECT amount FROM payment.parent_credit_ledger "
            + "WHERE reference_id = ? AND type = 'BOOKING_REFUND_REVERSAL'", BigDecimal.class, bookingId))
            .isEqualByComparingTo("-50.00");
        assertThat(jdbcTemplate.queryForObject(
            "SELECT recovered_amount FROM admin.disputes WHERE id = ?", BigDecimal.class, disputeId))
            .isEqualByComparingTo("50.00");
        CoachPayout row = payout(bookingId);
        assertThat(row.getStatus()).as("not frozen by the dispute it resolves").isEqualTo(CoachPayoutStatus.PENDING_RELEASE);
        assertThat(row.getNetAmount()).isEqualByComparingTo("45.00");

        releaseHoldAndDrain(bookingId);
        verify(paymentGateway).transferToCoach(eq(bookingId), eq(coachId), eq(new BigDecimal("45.00")), any());
    }

    @Test
    void upholdCoach_insufficientBalance_neverGoesNegative_andTheCoachIsStillPaidInFull() {
        UUID bookingId = seedBooking("NO_SHOW_COACH", Instant.now().minus(3, ChronoUnit.HOURS));
        ledger("50.00", "BOOKING_REFUND", bookingId);
        ledger("-35.00", "BOOKING_DEDUCTION", UUID.randomUUID()); // most of the refund already spent
        UUID disputeId = disputeService.raiseDispute(bookingId, "OTHER", "I was there", COACH_USER_ID, "COACH");

        disputeService.resolveDispute(disputeId, "UPHOLD_COACH", null, "session happened", 1L);

        assertThat(balance()).isEqualByComparingTo("0");
        assertThat(jdbcTemplate.queryForObject(
            "SELECT absorbed_amount FROM admin.disputes WHERE id = ?", BigDecimal.class, disputeId))
            .isEqualByComparingTo("35.00");
        assertThat(payout(bookingId).getNetAmount()).isEqualByComparingTo("45.00");
        assertThat(payout(bookingId).getStatus()).isEqualTo(CoachPayoutStatus.PENDING_RELEASE);
    }

    @Test
    void upholdCoach_withoutARefund_isRejected() {
        UUID bookingId = seedBooking("NO_SHOW_COACH", Instant.now().minus(3, ChronoUnit.HOURS));
        UUID disputeId = disputeService.raiseDispute(bookingId, "OTHER", "x", COACH_USER_ID, "COACH");

        org.assertj.core.api.Assertions.assertThatThrownBy(
                () -> disputeService.resolveDispute(disputeId, "UPHOLD_COACH", null, "x", 1L))
            .hasMessageContaining("disputes.noRefundToReverse");
        assertThat(coachPayoutRepository.findById(bookingId)).isEmpty();
    }
}
