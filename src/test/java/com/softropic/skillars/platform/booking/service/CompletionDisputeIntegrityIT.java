package com.softropic.skillars.platform.booking.service;

import com.softropic.skillars.platform.admin.service.DisputeService;
import com.softropic.skillars.platform.booking.contract.ActorRole;
import com.softropic.skillars.platform.booking.contract.BookingError;
import com.softropic.skillars.platform.booking.contract.ForceCompleteOutcome;
import com.softropic.skillars.platform.booking.contract.ScheduleBookingItem;
import com.softropic.skillars.platform.booking.contract.WrapUpRequest;
import com.softropic.skillars.platform.notification.contract.EmailTemplate;
import com.softropic.skillars.platform.payment.BasePaymentIT;
import com.softropic.skillars.platform.payment.contract.CoachPayoutStatus;
import com.softropic.skillars.platform.payment.repo.CoachPayout;
import com.softropic.skillars.platform.payment.repo.CoachPayoutRepository;
import com.softropic.skillars.platform.payment.service.CoachPayoutOutboxSupport;
import com.softropic.skillars.platform.security.contract.exception.OperationNotAllowedException;
import com.softropic.skillars.utils.TestMailManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.server.ResponseStatusException;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * fix/audit3-completion-dispute-integrity, against a real database (V213):
 * <ul>
 *   <li>a Quick Complete asks the family at once (one email), they can dispute it, and a session completed while
 *       that dispute is open gets a frozen payout;</li>
 *   <li>a never-started Quick Complete can be reported as a coach no-show (refund path + strike), a started one
 *       cannot; a one-minute live session is not a LIVE completion;</li>
 *   <li>a confirm before any wrap-up completes instead of 404ing;</li>
 *   <li>coach and family can each hold an open dispute on one booking (V213 index), the payout stays frozen and
 *       the alert open until the last one is settled, and a raiser's refiling is capped;</li>
 *   <li>a coach cancel after the start, and a parent's refund-eligible late cancel, strike the coach.</li>
 * </ul>
 * No new mock beans.
 */
class CompletionDisputeIntegrityIT extends BasePaymentIT {

    private static final long COACH_USER_ID = 98511L;
    private static final long PARENT_ID = 98512L;
    private static final long PLAYER_ID = 98513L;
    private static final String PARENT_EMAIL = "completion_dispute_parent@test.com";

    @Autowired BookingService bookingService;
    @Autowired BookingCompletionService bookingCompletionService;
    @Autowired DisputeService disputeService;
    @Autowired CoachPayoutRepository coachPayoutRepository;
    @Autowired CoachPayoutOutboxSupport coachPayoutOutboxSupport;
    @Autowired TestMailManager testMailManager;

    private UUID coachId;

    @BeforeEach
    void seed() {
        coachId = insertTestCoach(COACH_USER_ID, "completion_dispute_coach@test.com", "Dispute Coach");
        insertTestParent(PARENT_ID, PARENT_EMAIL);
        insertTestPlayer(PLAYER_ID, PARENT_ID);
    }

    // ── fixtures ──

    /** One Instant for start and end (chk_bkg_end_after_start); a captured 50.00 card payment. */
    private UUID seedBooking(String status, Instant start) {
        UUID bookingId = UUID.randomUUID();
        Instant end = start.plus(1, ChronoUnit.HOURS);
        transactionTemplate.execute(s -> {
            jdbcTemplate.update(
                "INSERT INTO booking.bookings (id, parent_id, player_id, coach_id, requested_start_time, "
                    + "requested_end_time, status, canonical_timezone, version, created_at, updated_at, "
                    + "status_changed_at, agreed_price) VALUES (?, ?, ?, ?, ?, ?, ?, 'UTC', 0, now(), now(), now(), 50.00)",
                bookingId, PARENT_ID, PLAYER_ID, coachId, Timestamp.from(start), Timestamp.from(end), status);
            return jdbcTemplate.update(
                "INSERT INTO payment.booking_payments (booking_id, credit_debited, stripe_charged, status, captured_at, "
                    + "commission_rate, stripe_payment_intent_id) VALUES (?, 0, 50.00, 'CAPTURED', now(), 0.10, ?)",
                bookingId, "pi_cdi_" + bookingId);
        });
        return bookingId;
    }

    private String statusOf(UUID bookingId) {
        return jdbcTemplate.queryForObject("SELECT status FROM booking.bookings WHERE id = ?", String.class, bookingId);
    }

    private long confirmationEmails() {
        return testMailManager.getEnvelopes().values().stream()
            .filter(e -> e.emailTemplate() == EmailTemplate.BOOKING_QUICK_COMPLETE_CONFIRM)
            .filter(e -> e.recipients().stream().anyMatch(r -> PARENT_EMAIL.equals(r.getEmail())))
            .count();
    }

    private int strikes(UUID bookingId, String reason) {
        return jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM marketplace.coach_reliability_strikes WHERE booking_id = ? AND reason = ?",
            Integer.class, bookingId, reason);
    }

    private int openDisputeAlerts(UUID bookingId) {
        return jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM admin.admin_alerts WHERE reference_id = ? AND type = 'DISPUTE_RAISED' "
                + "AND status = 'OPEN'", Integer.class, bookingId.toString());
    }

    private CoachPayout payout(UUID bookingId) {
        return coachPayoutRepository.findById(bookingId).orElseThrow();
    }

    // ── exploit 1: Quick Complete ──

    @Test
    void quickComplete_asksTheFamilyOnce_theyCanDispute_andTheSweptCompletionFreezesThePayout() {
        UUID bookingId = seedBooking("UPCOMING", Instant.now().minus(40, ChronoUnit.MINUTES));

        bookingCompletionService.initiateQuickComplete(bookingId, COACH_USER_ID);

        assertThat(statusOf(bookingId)).isEqualTo("COMPLETED_PENDING_CONFIRMATION");
        assertThat(jdbcTemplate.queryForObject(
            "SELECT confirmation_requested_at IS NOT NULL FROM booking.bookings WHERE id = ?", Boolean.class, bookingId))
            .isTrue();
        assertThat(confirmationEmails()).as("asked at once, not only at wrap-up submit").isEqualTo(1);

        assertThat(disputeService.raiseDispute(bookingId, "COACH_NO_SHOW", "the coach never came", PARENT_ID, "PARENT"))
            .isNotNull();

        // The stuck-completion sweeper (or the confirm timeout) completes it while the dispute is open.
        ForceCompleteOutcome outcome = transactionTemplate.execute(s ->
            bookingCompletionService.forceCompleteWithPlaceholderData(bookingId, ActorRole.SYSTEM, null));
        assertThat(outcome).isEqualTo(ForceCompleteOutcome.FORCE_COMPLETED);
        assertThat(statusOf(bookingId)).isEqualTo("COMPLETED");
        assertThat(payout(bookingId).isDisputeFrozen()).as("created frozen by the open dispute").isTrue();
        assertThat(payout(bookingId).getStatus()).isEqualTo(CoachPayoutStatus.FROZEN);
        assertThat(confirmationEmails()).isEqualTo(1);
    }

    @Test
    void neverStartedQuickComplete_canBeReportedAsACoachNoShow_withAStrikeAndNoPayout() {
        UUID bookingId = seedBooking("UPCOMING", Instant.now().minus(40, ChronoUnit.MINUTES));
        bookingCompletionService.initiateQuickComplete(bookingId, COACH_USER_ID);

        bookingService.recordNoShowCoach(bookingId, PARENT_ID);

        assertThat(statusOf(bookingId)).isEqualTo("NO_SHOW_COACH");
        assertThat(strikes(bookingId, "COACH_NO_SHOW")).isEqualTo(1);
        assertThat(coachPayoutRepository.findById(bookingId)).isEmpty();
    }

    /**
     * Exploit 7: confirming before any wrap-up used to 404 after the transition. It now completes and pays, with a
     * placeholder flagged awaiting_wrap_up and no timeline row yet (the development side waits for the wrap-up).
     */
    @Test
    void confirmBeforeAnyWrapUp_completesWithAnAwaitingPlaceholderRow() {
        UUID bookingId = seedBooking("UPCOMING", Instant.now().minus(40, ChronoUnit.MINUTES));
        bookingCompletionService.initiateQuickComplete(bookingId, COACH_USER_ID);

        bookingCompletionService.confirmCompletion(bookingId, PARENT_ID);

        assertThat(statusOf(bookingId)).isEqualTo("COMPLETED");
        assertThat(jdbcTemplate.queryForObject(
            "SELECT awaiting_wrap_up FROM booking.session_completion_data WHERE booking_id = ?", Boolean.class,
            bookingId)).isTrue();
        assertThat(coachPayoutRepository.findById(bookingId)).as("settled at the confirm").isPresent();
        assertThat(timelineRows(bookingId)).as("development side waits for the wrap-up").isZero();
    }

    /** Review MAJOR 1: the coach's wrap-up after the family's confirm fills the row in and credits development. */
    @Test
    void confirmThenWrapUp_recordsAttendanceAndTheTimelineOnce_withoutPayingAgain() {
        UUID bookingId = seedBooking("UPCOMING", Instant.now().minus(40, ChronoUnit.MINUTES));
        bookingCompletionService.initiateQuickComplete(bookingId, COACH_USER_ID);
        bookingCompletionService.confirmCompletion(bookingId, PARENT_ID);
        int payoutOutbox = payoutOutboxRows(bookingId);

        // Re-review MAJOR: the coach is routed to the wrap-up, never the recap-only dialog.
        Instant start = jdbcTemplate.queryForObject(
            "SELECT requested_start_time FROM booking.bookings WHERE id = ?", Timestamp.class, bookingId).toInstant();
        ScheduleBookingItem item = transactionTemplate.execute(s -> bookingService.getCoachWeekSchedule(coachId,
                start.atZone(java.time.ZoneOffset.UTC).toLocalDate(), java.time.ZoneOffset.UTC).stream()
            .filter(i -> i.bookingId().equals(bookingId)).findFirst().orElseThrow());
        assertThat(item.awaitingWrapUp()).isTrue();
        assertThat(item.wrapUpSubmitted()).isFalse();
        assertThatThrownBy(() -> bookingCompletionService.updateRecap(bookingId, COACH_USER_ID,
            new com.softropic.skillars.platform.booking.contract.UpdateSessionRecapRequest("text only", "next", null)))
            .isInstanceOf(OperationNotAllowedException.class)
            .satisfies(e -> assertThat(((OperationNotAllowedException) e).getErrorCode())
                .isEqualTo(BookingError.WRAP_UP_AWAITED));

        bookingCompletionService.submitWrapUp(bookingId, COACH_USER_ID, new WrapUpRequest(
            true, 4, 3, 5, List.of(), "QUICK", "Passing drills", "Finishing", null));

        assertThat(statusOf(bookingId)).isEqualTo("COMPLETED");
        assertThat(jdbcTemplate.queryForObject(
            "SELECT player_attended AND NOT awaiting_wrap_up AND effort_rating = 4 AND what_we_did = 'Passing drills' "
                + "FROM booking.session_completion_data WHERE booking_id = ?", Boolean.class, bookingId)).isTrue();
        assertThat(payoutOutboxRows(bookingId)).as("no second payout").isEqualTo(payoutOutbox);
        org.awaitility.Awaitility.await().atMost(java.time.Duration.ofSeconds(20)).untilAsserted(() ->
            assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM development.player_timeline_events WHERE reference_id = ? "
                    + "AND event_type = 'SESSION_COMPLETED' AND coach_id = ?", Integer.class, bookingId, coachId))
                .as("credited to the coach, attended").isEqualTo(1));

        // A second submit is refused and changes nothing.
        assertThatThrownBy(() -> bookingCompletionService.submitWrapUp(bookingId, COACH_USER_ID, new WrapUpRequest(
            false, null, null, null, List.of(), "QUICK", "again", "again", null)))
            .isInstanceOf(OperationNotAllowedException.class);
        assertThat(timelineRows(bookingId)).isEqualTo(1);
    }

    private int timelineRows(UUID bookingId) {
        return jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM development.player_timeline_events WHERE reference_id = ? "
                + "AND event_type = 'SESSION_COMPLETED'", Integer.class, bookingId);
    }

    private int payoutOutboxRows(UUID bookingId) {
        return jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM main.outbox_messages WHERE payload->>'bookingId' = ?", Integer.class,
            bookingId.toString());
    }

    // ── exploit 2: Start then End in a minute ──

    @Test
    void startThenEndAtOnce_isNotALiveCompletion_andCannotBeReportedAsANoShow() {
        UUID bookingId = seedBooking("UPCOMING", Instant.now().minus(40, ChronoUnit.MINUTES));
        bookingCompletionService.startSession(bookingId, COACH_USER_ID);
        bookingCompletionService.endSession(bookingId, COACH_USER_ID);

        assertThat(statusOf(bookingId)).isEqualTo("COMPLETED_PENDING_CONFIRMATION");
        assertThat(confirmationEmails()).isEqualTo(1);

        // The coach's "LIVE" wrap-up is QUICK: no completion, no payout, no second email.
        bookingCompletionService.submitWrapUp(bookingId, COACH_USER_ID, new WrapUpRequest(
            true, 4, 4, 4, List.of(), "LIVE", "Passing drills", "Finishing", null));
        assertThat(statusOf(bookingId)).isEqualTo("COMPLETED_PENDING_CONFIRMATION");
        assertThat(coachPayoutRepository.findById(bookingId)).isEmpty();
        assertThat(confirmationEmails()).isEqualTo(1);

        assertThatThrownBy(() -> bookingService.recordNoShowCoach(bookingId, PARENT_ID))
            .isInstanceOf(OperationNotAllowedException.class)
            .satisfies(e -> assertThat(((OperationNotAllowedException) e).getErrorCode())
                .isEqualTo(BookingError.SESSION_WAS_STARTED));
        // ...the family disputes it instead.
        assertThat(disputeService.raiseDispute(bookingId, "SESSION_QUALITY", "a one-minute session", PARENT_ID,
            "PARENT")).isNotNull();
    }

    // ── exploit 6: one open dispute per side ──

    @Test
    void coachAndFamilyDisputesCoexist_andThePayoutStaysFrozenUntilTheLastIsSettled() {
        UUID bookingId = seedBooking("COMPLETED", Instant.now().minus(3, ChronoUnit.HOURS));
        transactionTemplate.execute(s -> {
            coachPayoutOutboxSupport.enqueuePayout(bookingId, coachId);
            return null;
        });
        assertThat(payout(bookingId).getStatus()).isEqualTo(CoachPayoutStatus.PENDING_RELEASE);

        UUID coachDispute = disputeService.raiseDispute(bookingId, "OTHER", "coach side", COACH_USER_ID, "COACH");
        UUID familyDispute = disputeService.raiseDispute(bookingId, "SESSION_QUALITY", "family side", PARENT_ID,
            "PARENT");
        assertThat(familyDispute).as("the coach's open dispute no longer blocks the family's").isNotNull();
        assertThatThrownBy(() -> disputeService.raiseDispute(bookingId, "OTHER", "again", PARENT_ID, "PARENT"))
            .isInstanceOf(ResponseStatusException.class)
            .hasMessageContaining("disputes.alreadyRaised");

        disputeService.resolveDispute(familyDispute, "NO_ACTION", null, "no change", 1L);
        assertThat(payout(bookingId).isDisputeFrozen()).as("the coach's dispute is still open").isTrue();
        assertThat(payout(bookingId).getStatus()).isEqualTo(CoachPayoutStatus.FROZEN);
        assertThat(openDisputeAlerts(bookingId)).isEqualTo(1);

        disputeService.dismissDispute(coachDispute, "no merit", 1L);
        assertThat(payout(bookingId).isDisputeFrozen()).isFalse();
        assertThat(payout(bookingId).getStatus()).isEqualTo(CoachPayoutStatus.PENDING_RELEASE);
        assertThat(openDisputeAlerts(bookingId)).isZero();
    }

    // ── exploit 5: refiling cap ──

    @Test
    void aRaiserCannotRefileWithoutLimit() {
        UUID bookingId = seedBooking("COMPLETED", Instant.now().minus(3, ChronoUnit.HOURS));

        disputeService.dismissDispute(
            disputeService.raiseDispute(bookingId, "OTHER", "first", PARENT_ID, "PARENT"), "no", 1L);
        disputeService.dismissDispute(
            disputeService.raiseDispute(bookingId, "OTHER", "second", PARENT_ID, "PARENT"), "no", 1L);

        assertThatThrownBy(() -> disputeService.raiseDispute(bookingId, "OTHER", "third", PARENT_ID, "PARENT"))
            .isInstanceOf(ResponseStatusException.class)
            .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode().value()).isEqualTo(409))
            .hasMessageContaining("disputes.limitReached");
        // The coach side keeps its own allowance.
        assertThat(disputeService.raiseDispute(bookingId, "OTHER", "coach", COACH_USER_ID, "COACH")).isNotNull();
    }

    // ── exploits 3 and 4: strikes ──

    @Test
    void coachCancelAfterTheStart_withAnExcusedReason_strikesAndIsNotCountedAsExcused() {
        UUID bookingId = seedBooking("UPCOMING", Instant.now().minus(10, ChronoUnit.MINUTES));

        bookingService.cancelBookingAsCoach(bookingId, COACH_USER_ID, "WEATHER");

        assertThat(statusOf(bookingId)).isEqualTo("CANCELLED_COACH");
        assertThat(strikes(bookingId, "COACH_NO_SHOW")).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
            "SELECT after_start FROM payment.coach_cancellation_history WHERE booking_id = ?", Boolean.class, bookingId))
            .isTrue();
    }

    /** Owner decision (review MINOR 5): calling off a PAUSED (actually run) session keeps the excused rules. */
    @Test
    void coachCancelOfAPausedSession_withAnExcusedReason_doesNotStrike() {
        UUID bookingId = seedBooking("PAUSED", Instant.now().minus(20, ChronoUnit.MINUTES));
        transactionTemplate.execute(s -> jdbcTemplate.update(
            "UPDATE booking.bookings SET live_started_at = now() - interval '19 minutes' WHERE id = ?", bookingId));

        bookingService.cancelBookingAsCoach(bookingId, COACH_USER_ID, "WEATHER");

        assertThat(statusOf(bookingId)).isEqualTo("CANCELLED_COACH");
        assertThat(jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM marketplace.coach_reliability_strikes WHERE booking_id = ?", Integer.class, bookingId))
            .isZero();
        assertThat(jdbcTemplate.queryForObject(
            "SELECT after_start FROM payment.coach_cancellation_history WHERE booking_id = ?", Boolean.class, bookingId))
            .isFalse();
    }

    @Test
    void coachCancelBeforeTheStart_withAnExcusedReason_doesNotStrike() {
        UUID bookingId = seedBooking("UPCOMING", Instant.now().plus(3, ChronoUnit.HOURS));

        bookingService.cancelBookingAsCoach(bookingId, COACH_USER_ID, "WEATHER");

        assertThat(jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM marketplace.coach_reliability_strikes WHERE booking_id = ?", Integer.class, bookingId))
            .isZero();
    }

    @Test
    void parentLateCancelAfterTheCoachNeverShowed_strikesTheCoach() {
        UUID bookingId = seedBooking("UPCOMING", Instant.now().minus(40, ChronoUnit.MINUTES));

        bookingService.cancelBookingAsParent(bookingId, PARENT_ID);

        assertThat(statusOf(bookingId)).isEqualTo("CANCELLED_PARENT");
        assertThat(strikes(bookingId, "COACH_NO_SHOW")).isEqualTo(1);
    }
}
