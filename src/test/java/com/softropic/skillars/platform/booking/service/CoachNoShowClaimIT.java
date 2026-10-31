package com.softropic.skillars.platform.booking.service;

import com.softropic.skillars.platform.booking.contract.BookingError;
import com.softropic.skillars.platform.notification.contract.EmailTemplate;
import com.softropic.skillars.platform.outbox.service.OutboxService;
import com.softropic.skillars.platform.payment.BasePaymentIT;
import com.softropic.skillars.platform.payment.contract.CoachPayoutStatus;
import com.softropic.skillars.platform.payment.repo.CoachPayout;
import com.softropic.skillars.platform.payment.repo.CoachPayoutRepository;
import com.softropic.skillars.platform.security.contract.exception.OperationNotAllowedException;
import com.softropic.skillars.utils.TestMailManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * S-07 (owner decision 2026-10-31), against a real database (V218): the coach no-show claim window
 * (start + booking.lateCancel.graceMinutes .. start + booking.coachNoShow.reportWindowHours), the report and
 * cancel doors following one rule, first-action-wins against a late Start, and the conflict-zone 50/50 split
 * of a session the coach quick-completed without ever pressing Start. The exact boundary instants are pinned
 * in BookingServiceTest (a real clock cannot hit them here). No new mock beans.
 */
class CoachNoShowClaimIT extends BasePaymentIT {

    private static final long COACH_USER_ID = 98521L;
    private static final long PARENT_ID = 98522L;
    private static final long PLAYER_ID = 98523L;
    private static final String PARENT_EMAIL = "noshow_claim_parent@test.com";

    @Autowired BookingService bookingService;
    @Autowired BookingCompletionService bookingCompletionService;
    @Autowired CoachPayoutRepository coachPayoutRepository;
    @Autowired OutboxService outboxService;
    @Autowired TestMailManager testMailManager;

    private UUID coachId;

    @BeforeEach
    void seed() {
        coachId = insertTestCoach(COACH_USER_ID, "noshow_claim_coach@test.com", "Claim Coach");
        insertTestParent(PARENT_ID, PARENT_EMAIL);
        insertTestPlayer(PLAYER_ID, PARENT_ID);
        testMailManager.clear();
    }

    // ── fixtures ──

    /** One Instant for start and end (chk_bkg_end_after_start); a captured card payment of {@code card}. */
    private UUID seedBooking(String status, Instant start, String card) {
        UUID bookingId = UUID.randomUUID();
        Instant end = start.plus(1, ChronoUnit.HOURS);
        transactionTemplate.execute(s -> {
            jdbcTemplate.update(
                "INSERT INTO booking.bookings (id, parent_id, player_id, coach_id, requested_start_time, "
                    + "requested_end_time, status, canonical_timezone, version, created_at, updated_at, "
                    + "status_changed_at, agreed_price) VALUES (?, ?, ?, ?, ?, ?, ?, 'UTC', 0, now(), now(), now(), ?::numeric)",
                bookingId, PARENT_ID, PLAYER_ID, coachId, Timestamp.from(start), Timestamp.from(end), status, card);
            return jdbcTemplate.update(
                "INSERT INTO payment.booking_payments (booking_id, credit_debited, stripe_charged, status, captured_at, "
                    + "commission_rate, stripe_payment_intent_id) VALUES (?, 0, ?::numeric, 'CAPTURED', now(), 0.10, ?)",
                bookingId, card, "pi_ncl_" + bookingId);
        });
        return bookingId;
    }

    private UUID seedPackBooking(UUID packId, Instant start) {
        UUID bookingId = UUID.randomUUID();
        transactionTemplate.execute(s -> jdbcTemplate.update(
            "INSERT INTO booking.bookings (id, parent_id, player_id, coach_id, requested_start_time, requested_end_time, "
                + "status, canonical_timezone, session_pack_purchase_id, version, created_at, updated_at, status_changed_at) "
                + "VALUES (?, ?, ?, ?, ?, ?, 'UPCOMING', 'UTC', ?, 0, now(), now(), now())",
            bookingId, PARENT_ID, PLAYER_ID, coachId, Timestamp.from(start),
            Timestamp.from(start.plus(1, ChronoUnit.HOURS)), packId));
        return bookingId;
    }

    private UUID seedPack(String amountCharged, int sessions) {
        UUID tierId = UUID.randomUUID();
        UUID purchaseId = UUID.randomUUID();
        transactionTemplate.execute(s -> {
            jdbcTemplate.update("INSERT INTO payment.session_pack_tiers (pack_tier_id, coach_id, label, session_count, "
                + "total_price, price_per_session, is_active, version, created_at) "
                + "VALUES (?, ?, 'IT pack', ?, ?::numeric, 1.00, true, 0, now())",
                tierId, coachId, sessions, amountCharged);
            jdbcTemplate.update("INSERT INTO payment.session_pack_purchases (purchase_id, parent_id, player_id, coach_id, "
                + "pack_tier_id, price_per_session, amount_charged, session_count, commission_rate, remaining_sessions, "
                + "expires_at, version, created_at) VALUES (?, ?, ?, ?, ?, 1.00, ?::numeric, ?, 0.20, 1, ?, 0, now())",
                purchaseId, PARENT_ID, PLAYER_ID, coachId, tierId, amountCharged, sessions,
                Timestamp.from(Instant.now().plus(30, ChronoUnit.DAYS)));
            return null;
        });
        return purchaseId;
    }

    private static Instant ago(long minutes) {
        return Instant.now().minus(minutes, ChronoUnit.MINUTES).truncatedTo(ChronoUnit.SECONDS);
    }

    private String statusOf(UUID bookingId) {
        return jdbcTemplate.queryForObject("SELECT status FROM booking.bookings WHERE id = ?", String.class, bookingId);
    }

    private String claimOf(UUID bookingId) {
        return jdbcTemplate.queryForObject(
            "SELECT coach_no_show_claim FROM booking.bookings WHERE id = ?", String.class, bookingId);
    }

    private boolean claimedAtSet(UUID bookingId) {
        return Boolean.TRUE.equals(jdbcTemplate.queryForObject(
            "SELECT coach_no_show_claimed_at IS NOT NULL FROM booking.bookings WHERE id = ?", Boolean.class, bookingId));
    }

    private int strikes(UUID bookingId) {
        return jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM marketplace.coach_reliability_strikes WHERE booking_id = ? AND reason = 'COACH_NO_SHOW'",
            Integer.class, bookingId);
    }

    private BigDecimal refunded(UUID bookingId) {
        outboxService.drain();
        return jdbcTemplate.queryForObject(
            "SELECT COALESCE(SUM(amount), 0) FROM payment.parent_credit_ledger WHERE reference_id = ? AND type = 'BOOKING_REFUND'",
            BigDecimal.class, bookingId);
    }

    private CoachPayout payout(UUID bookingId) {
        return coachPayoutRepository.findById(bookingId).orElseThrow();
    }

    private static void assertWindowClosed(Throwable e) {
        assertThat(e).isInstanceOf(ResponseStatusException.class);
        assertThat(((ResponseStatusException) e).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(((ResponseStatusException) e).getReason()).isEqualTo("booking.noShowWindowClosed");
    }

    // ── report on a plain UPCOMING session: full refund + strike (unchanged) ──

    @Test
    void report_onAnUnstartedUpcomingSession_isAFullRefundAndAStrike() {
        UUID bookingId = seedBooking("UPCOMING", ago(40), "50.00");

        bookingService.recordNoShowCoach(bookingId, PARENT_ID);

        assertThat(statusOf(bookingId)).isEqualTo("NO_SHOW_COACH");
        assertThat(claimOf(bookingId)).isEqualTo("REPORT");
        assertThat(claimedAtSet(bookingId)).isTrue();
        assertThat(strikes(bookingId)).isEqualTo(1);
        assertThat(refunded(bookingId)).isEqualByComparingTo("50.00");
        assertThat(coachPayoutRepository.findById(bookingId)).isEmpty();
    }

    @Test
    void report_insideTheGrace_isTooEarly() {
        UUID bookingId = seedBooking("UPCOMING", ago(14), "50.00");

        assertThatThrownBy(() -> bookingService.recordNoShowCoach(bookingId, PARENT_ID))
            .isInstanceOf(OperationNotAllowedException.class)
            .satisfies(e -> assertThat(((OperationNotAllowedException) e).getErrorCode())
                .isEqualTo(BookingError.NO_SHOW_TOO_EARLY));
        assertThat(statusOf(bookingId)).isEqualTo("UPCOMING");
        assertThat(claimOf(bookingId)).isNull();
    }

    // ── the window closes: report and cancel both refuse, nothing moves ──

    @Test
    void afterTheWindow_bothDoorsAnswer409_andTheBookingIsUntouched() {
        UUID bookingId = seedBooking("UPCOMING", ago(5 * 60 + 5), "50.00");

        assertThatThrownBy(() -> bookingService.recordNoShowCoach(bookingId, PARENT_ID))
            .satisfies(CoachNoShowClaimIT::assertWindowClosed);
        assertThatThrownBy(() -> bookingService.cancelBookingAsParent(bookingId, PARENT_ID))
            .satisfies(CoachNoShowClaimIT::assertWindowClosed);

        assertThat(statusOf(bookingId)).isEqualTo("UPCOMING");
        assertThat(claimOf(bookingId)).isNull();
        assertThat(strikes(bookingId)).isZero();
        assertThat(refunded(bookingId)).isEqualByComparingTo("0");
    }

    @Test
    void afterTheWindow_aConflictZoneReportIsRefusedToo() {
        UUID bookingId = seedBooking("UPCOMING", ago(5 * 60 + 5), "50.00");
        bookingCompletionService.initiateQuickComplete(bookingId, COACH_USER_ID);

        assertThatThrownBy(() -> bookingService.recordNoShowCoach(bookingId, PARENT_ID))
            .satisfies(CoachNoShowClaimIT::assertWindowClosed);

        assertThat(statusOf(bookingId)).isEqualTo("COMPLETED_PENDING_CONFIRMATION");
    }

    // ── the cancel door is the same claim ──

    @Test
    void cancel_insideTheWindow_isTheSameClaimAsTheReport() {
        UUID bookingId = seedBooking("UPCOMING", ago(40), "50.00");

        bookingService.cancelBookingAsParent(bookingId, PARENT_ID);

        assertThat(statusOf(bookingId)).isEqualTo("CANCELLED_PARENT");
        assertThat(claimOf(bookingId)).isEqualTo("LATE_CANCEL");
        assertThat(claimedAtSet(bookingId)).isTrue();
        assertThat(strikes(bookingId)).as("same strike as the report").isEqualTo(1);
        assertThat(refunded(bookingId)).as("same refund as the report").isEqualByComparingTo("50.00");
        assertThat(coachPayoutRepository.findById(bookingId)).isEmpty();
    }

    @Test
    void cancel_beforeTheStart_isUntouched_aForfeitWithNoClaim() {
        UUID bookingId = seedBooking("UPCOMING", Instant.now().plus(2, ChronoUnit.HOURS).truncatedTo(ChronoUnit.SECONDS), "50.00");

        bookingService.cancelBookingAsParent(bookingId, PARENT_ID);

        assertThat(statusOf(bookingId)).isEqualTo("CANCELLED_PARENT");
        assertThat(claimOf(bookingId)).isNull();
        assertThat(strikes(bookingId)).isZero();
        assertThat(refunded(bookingId)).isEqualByComparingTo("0");
        assertThat(payout(bookingId).getGrossAmount()).as("the <24h forfeit still pays the coach").isEqualByComparingTo("50.00");
    }

    // ── first action wins ──

    @Test
    void aLateStartBeforeAnyClaim_makesTheClaimImpossible() {
        UUID bookingId = seedBooking("UPCOMING", ago(16), "50.00");

        bookingCompletionService.startSession(bookingId, COACH_USER_ID);

        assertThatThrownBy(() -> bookingService.recordNoShowCoach(bookingId, PARENT_ID))
            .isInstanceOf(OperationNotAllowedException.class)
            .satisfies(e -> assertThat(((OperationNotAllowedException) e).getErrorCode())
                .isEqualTo(BookingError.SESSION_WAS_STARTED));
        assertThatThrownBy(() -> bookingService.cancelBookingAsParent(bookingId, PARENT_ID))
            .isInstanceOf(OperationNotAllowedException.class)
            .satisfies(e -> assertThat(((OperationNotAllowedException) e).getErrorCode())
                .isEqualTo(BookingError.SESSION_IN_PROGRESS));
        assertThat(statusOf(bookingId)).isEqualTo("IN_PROGRESS");
        assertThat(claimOf(bookingId)).isNull();
    }

    @Test
    void onceAClaimIsFiled_theCoachCanNoLongerStartOrQuickComplete() {
        UUID bookingId = seedBooking("UPCOMING", ago(40), "50.00");
        bookingService.recordNoShowCoach(bookingId, PARENT_ID);

        assertThatThrownBy(() -> bookingCompletionService.startSession(bookingId, COACH_USER_ID))
            .isInstanceOf(OperationNotAllowedException.class);
        assertThatThrownBy(() -> bookingCompletionService.initiateQuickComplete(bookingId, COACH_USER_ID))
            .isInstanceOf(OperationNotAllowedException.class);
        assertThat(statusOf(bookingId)).isEqualTo("NO_SHOW_COACH");
    }

    // ── conflict zone: quick-completed without a live Start, then reported → 50/50 ──

    @Test
    void conflictZone_splitsTheFeeFiftyFifty_withNoStrike() {
        UUID bookingId = seedBooking("UPCOMING", ago(40), "50.00");
        bookingCompletionService.initiateQuickComplete(bookingId, COACH_USER_ID);

        bookingService.recordNoShowCoach(bookingId, PARENT_ID);

        assertThat(statusOf(bookingId)).isEqualTo("NO_SHOW_COACH");
        assertThat(claimOf(bookingId)).isEqualTo("SPLIT");
        assertThat(strikes(bookingId)).as("neither side is established — nobody is struck").isZero();
        assertThat(refunded(bookingId)).isEqualByComparingTo("25.00");
        CoachPayout row = payout(bookingId);
        assertThat(row.getStatus()).isEqualTo(CoachPayoutStatus.PENDING_RELEASE);
        assertThat(row.getGrossAmount()).isEqualByComparingTo("25.00");
        assertThat(row.getCommissionAmount()).as("commission on the retained half only").isEqualByComparingTo("2.50");
        assertThat(row.getNetAmount()).isEqualByComparingTo("22.50");
        assertThat(row.getReleaseAfter()).as("the normal / new-coach release hold applies").isAfter(Instant.now());
        assertThat(testMailManager.getEnvelopes().values().stream()
            .filter(e -> e.emailTemplate() == EmailTemplate.COACH_NO_SHOW)
            .filter(e -> e.recipients().stream().anyMatch(r -> PARENT_EMAIL.equals(r.getEmail())))
            .map(e -> e.data()))
            .singleElement()
            .satisfies(d -> assertThat(d).containsEntry("refundType", "SPLIT").containsEntry("refundAmount", "25.00"));
    }

    @Test
    void conflictZone_oddCent_goesToTheCoach_refundAndCoachSumToWhatWasPaid() {
        UUID bookingId = seedBooking("UPCOMING", ago(40), "33.33");
        bookingCompletionService.initiateQuickComplete(bookingId, COACH_USER_ID);

        bookingService.recordNoShowCoach(bookingId, PARENT_ID);

        BigDecimal refund = refunded(bookingId);
        CoachPayout row = payout(bookingId);
        assertThat(refund).as("floor of half").isEqualByComparingTo("16.66");
        assertThat(row.getGrossAmount()).as("the coach takes the odd cent").isEqualByComparingTo("16.67");
        assertThat(refund.add(row.getGrossAmount())).isEqualByComparingTo("33.33");
        assertThat(row.getCommissionAmount()).isEqualByComparingTo("1.67");
        assertThat(row.getNetAmount()).isEqualByComparingTo("15.00");
    }

    @Test
    void conflictZone_onAPackSession_consumesTheSession_andSettlesOneLedgerSlot() {
        UUID packId = seedPack("90.00", 3);
        UUID bookingId = seedPackBooking(packId, ago(40));
        bookingCompletionService.initiateQuickComplete(bookingId, COACH_USER_ID);

        bookingService.recordNoShowCoach(bookingId, PARENT_ID);

        assertThat(statusOf(bookingId)).isEqualTo("NO_SHOW_COACH");
        assertThat(strikes(bookingId)).isZero();
        assertThat(refunded(bookingId)).as("only the monetary half goes back").isEqualByComparingTo("15.00");
        CoachPayout row = payout(bookingId);
        assertThat(row.getGrossAmount()).isEqualByComparingTo("15.00");
        assertThat(row.getCommissionAmount()).as("at the rate stamped at pack purchase").isEqualByComparingTo("3.00");
        assertThat(row.getSessionPackPurchaseId()).isEqualTo(packId);
        Map<String, Object> pack = jdbcTemplate.queryForMap(
            "SELECT remaining_sessions, payout_sessions_allocated, payout_gross_allocated, refund_sessions_issued, "
                + "refund_gross_issued FROM payment.session_pack_purchases WHERE purchase_id = ?", packId);
        assertThat(pack.get("remaining_sessions")).as("the session is consumed, not restored").isEqualTo(1);
        assertThat(pack.get("payout_sessions_allocated")).isEqualTo(1);
        assertThat(pack.get("refund_sessions_issued")).as("one slot, not two").isEqualTo(0);
        assertThat((BigDecimal) pack.get("payout_gross_allocated")).isEqualByComparingTo("15.00");
        assertThat((BigDecimal) pack.get("refund_gross_issued")).isEqualByComparingTo("15.00");
    }

    @Test
    void conflictZone_aStartedSessionIsStillADisputeNotASplit() {
        UUID bookingId = seedBooking("UPCOMING", ago(40), "50.00");
        bookingCompletionService.startSession(bookingId, COACH_USER_ID);
        bookingCompletionService.endSession(bookingId, COACH_USER_ID);

        assertThatThrownBy(() -> bookingService.recordNoShowCoach(bookingId, PARENT_ID))
            .isInstanceOf(OperationNotAllowedException.class)
            .satisfies(e -> assertThat(((OperationNotAllowedException) e).getErrorCode())
                .isEqualTo(BookingError.SESSION_WAS_STARTED));
    }
}
