package com.softropic.skillars.platform.booking.service;

import com.softropic.skillars.platform.notification.contract.EmailTemplate;
import com.softropic.skillars.platform.outbox.service.OutboxService;
import com.softropic.skillars.platform.payment.BasePaymentIT;
import com.softropic.skillars.utils.TestMailManager;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * S-07 part 2 (owner decision 2026-10-31), against a real database (V219): what happens when nobody records a session.
 * <ul>
 *   <li>a session nobody started, claimed or quick-completed is refunded in full 48h after its start, with no coach
 *       strike (card: wallet refund; pack: the session back), and a historic SESSION_NEVER_STARTED alert is closed;</li>
 *   <li>the coach gets "press Start" reminders (email + inbox row) at the start and at start + 10 minutes, once each;</li>
 *   <li>a payer's third coach no-show claim in the window raises ONE informational admin alert.</li>
 * </ul>
 * No new mock beans.
 */
class CoachNoShowSafetyNetIT extends BasePaymentIT {

    private static final long COACH_USER_ID = 98531L;
    private static final long PARENT_ID = 98532L;
    private static final long PLAYER_ID = 98533L;
    private static final String COACH_EMAIL = "safety_net_coach@test.com";
    private static final String PARENT_EMAIL = "safety_net_parent@test.com";

    @Autowired BookingService bookingService;
    @Autowired BookingCompletionService bookingCompletionService;
    @Autowired UnresolvedSessionSweeperService unresolvedSessionSweeperService;
    @Autowired CoachStartReminderScheduler coachStartReminderScheduler;
    @Autowired OutboxService outboxService;
    @Autowired TestMailManager testMailManager;

    private UUID coachId;

    @BeforeEach
    void seed() {
        coachId = insertTestCoach(COACH_USER_ID, COACH_EMAIL, "Safety Net Coach");
        insertTestParent(PARENT_ID, PARENT_EMAIL);
        insertTestPlayer(PLAYER_ID, PARENT_ID);
        testMailManager.clear();
        releaseSchedulerLock("UnresolvedSessionSweeperService_sweep");
        releaseSchedulerLock("CoachStartReminderScheduler_remind");
    }

    // ── fixtures ──

    private static Instant ago(long minutes) {
        return Instant.now().minus(minutes, ChronoUnit.MINUTES).truncatedTo(ChronoUnit.SECONDS);
    }

    /** One Instant for start and end (chk_bkg_end_after_start); a captured card payment of {@code card}. */
    private UUID seedBooking(String status, Instant start, String card) {
        UUID bookingId = UUID.randomUUID();
        transactionTemplate.execute(s -> {
            jdbcTemplate.update(
                "INSERT INTO booking.bookings (id, parent_id, player_id, coach_id, requested_start_time, "
                    + "requested_end_time, status, canonical_timezone, version, created_at, updated_at, "
                    + "status_changed_at, agreed_price) VALUES (?, ?, ?, ?, ?, ?, ?, 'UTC', 0, now(), now(), now(), ?::numeric)",
                bookingId, PARENT_ID, PLAYER_ID, coachId, Timestamp.from(start),
                Timestamp.from(start.plus(1, ChronoUnit.HOURS)), status, card);
            return jdbcTemplate.update(
                "INSERT INTO payment.booking_payments (booking_id, credit_debited, stripe_charged, status, captured_at, "
                    + "commission_rate, stripe_payment_intent_id) VALUES (?, 0, ?::numeric, 'CAPTURED', now(), 0.10, ?)",
                bookingId, card, "pi_snt_" + bookingId);
        });
        return bookingId;
    }

    private UUID seedPack(int remaining) {
        UUID tierId = UUID.randomUUID();
        UUID purchaseId = UUID.randomUUID();
        transactionTemplate.execute(s -> {
            jdbcTemplate.update("INSERT INTO payment.session_pack_tiers (pack_tier_id, coach_id, label, session_count, "
                + "total_price, price_per_session, is_active, version, created_at) "
                + "VALUES (?, ?, 'IT pack', 3, 90.00, 30.00, true, 0, now())", tierId, coachId);
            jdbcTemplate.update("INSERT INTO payment.session_pack_purchases (purchase_id, parent_id, player_id, coach_id, "
                + "pack_tier_id, price_per_session, amount_charged, session_count, commission_rate, remaining_sessions, "
                + "expires_at, version, created_at) VALUES (?, ?, ?, ?, ?, 30.00, 90.00, 3, 0.20, ?, ?, 0, now())",
                purchaseId, PARENT_ID, PLAYER_ID, coachId, tierId, remaining,
                Timestamp.from(Instant.now().plus(30, ChronoUnit.DAYS)));
            return null;
        });
        return purchaseId;
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

    private String statusOf(UUID bookingId) {
        return jdbcTemplate.queryForObject("SELECT status FROM booking.bookings WHERE id = ?", String.class, bookingId);
    }

    private int strikes(UUID bookingId) {
        return jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM marketplace.coach_reliability_strikes WHERE booking_id = ?", Integer.class, bookingId);
    }

    private BigDecimal refunded(UUID bookingId) {
        outboxService.drain();
        return jdbcTemplate.queryForObject(
            "SELECT COALESCE(SUM(amount), 0) FROM payment.parent_credit_ledger WHERE reference_id = ? AND type = 'BOOKING_REFUND'",
            BigDecimal.class, bookingId);
    }

    private List<Map<String, Object>> emailsTo(EmailTemplate template, String email) {
        outboxService.drain();
        return testMailManager.getEnvelopes().values().stream()
            .filter(e -> e.emailTemplate() == template)
            .filter(e -> e.recipients().stream().anyMatch(r -> email.equalsIgnoreCase(r.getEmail())))
            .map(e -> e.data())
            .toList();
    }

    private Timestamp sentAt(UUID bookingId, String column) {
        return jdbcTemplate.queryForObject(
            "SELECT " + column + " FROM booking.bookings WHERE id = ?", Timestamp.class, bookingId);
    }

    // ── rule 5: auto-refund ──

    @Test
    void anUnrecordedSession_isRefundedInFull48hAfterItsStart_withNoStrike() {
        UUID unrecorded = seedBooking("UPCOMING", ago(49 * 60), "50.00");
        UUID tooRecent = seedBooking("UPCOMING", ago(47 * 60), "50.00");
        // A historic alert from before this policy: nothing left for an admin to decide.
        transactionTemplate.execute(s -> jdbcTemplate.update(
            "INSERT INTO admin.admin_alerts (alert_id, type, reference_id, reference_type, status, created_at) "
                + "VALUES (?, 'SESSION_NEVER_STARTED', ?, 'BOOKING', 'OPEN', now())", UUID.randomUUID(), unrecorded.toString()));

        unresolvedSessionSweeperService.sweep();

        assertThat(statusOf(unrecorded)).isEqualTo("CANCELLED");
        assertThat(jdbcTemplate.queryForObject("SELECT cancel_reason FROM booking.bookings WHERE id = ?", String.class, unrecorded))
            .isEqualTo("NEVER_STARTED");
        assertThat(strikes(unrecorded)).as("the coach is not struck").isZero();
        assertThat(refunded(unrecorded)).isEqualByComparingTo("50.00");
        assertThat(jdbcTemplate.queryForObject(
            "SELECT status FROM admin.admin_alerts WHERE type = 'SESSION_NEVER_STARTED' AND reference_id = ?",
            String.class, unrecorded.toString())).isEqualTo("RESOLVED");
        assertThat(emailsTo(EmailTemplate.BOOKING_CANCELLED_COACH_UNAVAILABLE, PARENT_EMAIL)).singleElement()
            .satisfies(d -> assertThat(d).containsEntry("cancelledBy", "NEVER_STARTED").containsEntry("refundType", "CREDIT"));
        assertThat(emailsTo(EmailTemplate.BOOKING_CANCELLED_BY_SKILLARS, COACH_EMAIL)).singleElement()
            .satisfies(d -> assertThat(d).containsEntry("cancelledBy", "NEVER_STARTED"));

        assertThat(statusOf(tooRecent)).as("only 47h past its start").isEqualTo("UPCOMING");
        assertThat(refunded(tooRecent)).isEqualByComparingTo("0");
    }

    @Test
    void aQuickCompletedSession_isNeverAutoRefunded_itGoesToTheFamilysConfirmOrDispute() {
        UUID booking = seedBooking("UPCOMING", ago(49 * 60), "50.00");
        bookingCompletionService.initiateQuickComplete(booking, COACH_USER_ID);

        unresolvedSessionSweeperService.sweep();

        assertThat(statusOf(booking)).isEqualTo("COMPLETED_PENDING_CONFIRMATION");
        assertThat(refunded(booking)).isEqualByComparingTo("0");
    }

    @Test
    void anUnrecordedPackSession_isReturnedToThePack() {
        UUID packId = seedPack(0);
        UUID booking = seedPackBooking(packId, ago(49 * 60));

        unresolvedSessionSweeperService.sweep();

        assertThat(statusOf(booking)).isEqualTo("CANCELLED");
        assertThat(strikes(booking)).isZero();
        outboxService.drain();
        Awaitility.await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
            assertThat(jdbcTemplate.queryForObject(
                "SELECT remaining_sessions FROM payment.session_pack_purchases WHERE purchase_id = ?",
                Integer.class, packId)).isEqualTo(1));
    }

    // ── rule 6: coach reminders ──

    @Test
    void atTheStart_thenAtStartPlusTenMinutes_theCoachIsRemindedOnceEach_byEmailAndInbox() {
        UUID booking = seedBooking("UPCOMING", ago(2), "50.00");

        coachStartReminderScheduler.remind();

        assertThat(sentAt(booking, "coach_start_reminder_sent_at")).isNotNull();
        assertThat(sentAt(booking, "coach_late_start_reminder_sent_at")).isNull();
        assertThat(emailsTo(EmailTemplate.COACH_START_REMINDER, COACH_EMAIL)).singleElement()
            .satisfies(d -> assertThat(d).containsEntry("reminderType", "START"));
        Awaitility.await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
            assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM main.user_notifications WHERE recipient_user_id = ? AND type = 'COACH_START_REMINDER'",
                Integer.class, COACH_USER_ID)).isEqualTo(1));

        // Nothing more until start + 10 minutes, and never a repeat of the first.
        releaseSchedulerLock("CoachStartReminderScheduler_remind");
        coachStartReminderScheduler.remind();
        assertThat(emailsTo(EmailTemplate.COACH_START_REMINDER, COACH_EMAIL)).hasSize(1);

        transactionTemplate.execute(s -> jdbcTemplate.update(
            "UPDATE booking.bookings SET requested_start_time = ?, requested_end_time = ? WHERE id = ?",
            Timestamp.from(ago(11)), Timestamp.from(ago(11).plus(1, ChronoUnit.HOURS)), booking));
        releaseSchedulerLock("CoachStartReminderScheduler_remind");
        coachStartReminderScheduler.remind();
        assertThat(emailsTo(EmailTemplate.COACH_START_REMINDER, COACH_EMAIL)).hasSize(2)
            .anySatisfy(d -> assertThat(d).containsEntry("reminderType", "LATE"));

        releaseSchedulerLock("CoachStartReminderScheduler_remind");
        coachStartReminderScheduler.remind();
        assertThat(emailsTo(EmailTemplate.COACH_START_REMINDER, COACH_EMAIL)).as("once each").hasSize(2);
    }

    @Test
    void noReminderForAStartedSession_orOneLongPastItsStart() {
        UUID started = seedBooking("UPCOMING", ago(3), "50.00");
        bookingCompletionService.startSession(started, COACH_USER_ID);
        UUID stale = seedBooking("UPCOMING", ago(3 * 60), "50.00");

        coachStartReminderScheduler.remind();

        assertThat(sentAt(started, "coach_start_reminder_sent_at")).isNull();
        assertThat(sentAt(stale, "coach_start_reminder_sent_at")).isNull();
        assertThat(emailsTo(EmailTemplate.COACH_START_REMINDER, COACH_EMAIL)).isEmpty();
    }

    // ── rule 7: repeat-claimant alert ──

    @Test
    void aPayersThirdClaimInTheWindow_raisesOneInformationalAlert_notOnePerClaim() {
        // Four distinct, non-overlapping one-hour slots, all inside the 15min..5h claim window.
        UUID first = seedBooking("UPCOMING", ago(240), "50.00");
        UUID second = seedBooking("UPCOMING", ago(170), "50.00");
        UUID third = seedBooking("UPCOMING", ago(100), "50.00");
        UUID fourth = seedBooking("UPCOMING", ago(30), "50.00");

        bookingService.recordNoShowCoach(first, PARENT_ID);
        bookingService.recordNoShowCoach(second, PARENT_ID);
        assertThat(repeatClaimantAlerts()).as("below the threshold of 3").isZero();

        bookingService.cancelBookingAsParent(third, PARENT_ID); // the other door counts too
        assertThat(repeatClaimantAlerts()).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
            "SELECT reason FROM admin.admin_alerts WHERE type = 'REPEAT_NO_SHOW_CLAIMANT'", String.class))
            .isEqualTo("payer " + PARENT_ID + ": 3 claims in 90d");
        assertThat(jdbcTemplate.queryForObject(
            "SELECT reference_id FROM admin.admin_alerts WHERE type = 'REPEAT_NO_SHOW_CLAIMANT'", String.class))
            .isEqualTo(third.toString());

        bookingService.recordNoShowCoach(fourth, PARENT_ID);
        assertThat(repeatClaimantAlerts()).as("one per payer per window").isEqualTo(1);
        // Informational only: the claims themselves were all honoured.
        assertThat(statusOf(fourth)).isEqualTo("NO_SHOW_COACH");
    }

    private int repeatClaimantAlerts() {
        return jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM admin.admin_alerts WHERE type = 'REPEAT_NO_SHOW_CLAIMANT' AND reason LIKE ?",
            Integer.class, "payer " + PARENT_ID + ":%");
    }
}
