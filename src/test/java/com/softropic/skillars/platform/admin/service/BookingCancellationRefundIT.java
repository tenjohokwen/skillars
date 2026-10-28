package com.softropic.skillars.platform.admin.service;

import com.softropic.skillars.config.AbstractIntegrationTest;
import com.softropic.skillars.platform.booking.contract.AdminBookingCancellationOutcome;
import com.softropic.skillars.platform.booking.service.BookingService;
import com.softropic.skillars.platform.notification.contract.EmailTemplate;
import com.softropic.skillars.platform.notification.contract.Envelope;
import com.softropic.skillars.platform.outbox.service.OutboxService;
import com.softropic.skillars.platform.payment.service.RefundOutboxSupport;
import com.softropic.skillars.utils.TestMailManager;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * C6, end to end against the real database and event wiring:
 * <ul>
 *   <li>a coach cancelling a booking refunds only money actually captured — an ACCEPTED booking (nothing
 *       charged, no pack session deducted) mints no wallet credit and restores no pack session — and
 *       refunds what was paid, not the coach's current price. The strike policy is unchanged;</li>
 *   <li>a coach no-show refunds what was paid;</li>
 *   <li>an admin cancelling a single booking ({@link AdminBookingCancellationService}) gets the C2
 *       suspension-sweep semantics (full refund of captured money, no strike, neutral notice worded as a
 *       Skillars cancellation), refuses live / in-flight-capture bookings, audits every attempt, and
 *       closes the booking's open SAFETY_HOLD_BOOKING alert.</li>
 * </ul>
 *
 * <p>Reuses {@link AbstractIntegrationTest}'s context verbatim — no extra mocks or properties.
 */
class BookingCancellationRefundIT extends AbstractIntegrationTest {

    private static final long COACH_USER_ID = 9189_000_010L;
    private static final long ADMIN_ID = 9189_000_100L;
    private static final long PARENT_ID = 9189_000_001L;
    private static final long PLAYER_ID = 9189_000_002L;
    private static final String PARENT_EMAIL = "parent.c6cancel9189@skillars-test.com";
    private static final String COACH_EMAIL = "coach.c6cancel9189@skillars-test.com";
    private static final String PASSWORD_HASH = "$2a$10$" + "a".repeat(53);

    @Autowired private AdminBookingCancellationService adminBookingCancellationService;
    @Autowired private BookingService bookingService;
    @Autowired private OutboxService outboxService;
    @Autowired private TestMailManager testMailManager;

    private UUID coachId;
    private UUID packTierId;
    private UUID packId;
    private Instant base;
    private int slot;

    @BeforeEach
    void setUp() {
        coachId = UUID.randomUUID();
        packTierId = UUID.randomUUID();
        packId = UUID.randomUUID();
        base = Instant.now().plus(3, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS);
        slot = 0;
        testMailManager.clear();
        transactionTemplate.execute(status -> {
            insertUser(COACH_USER_ID, COACH_EMAIL, "COACH");
            insertUser(ADMIN_ID, "admin.c6cancel9189@skillars-test.com", "ADMIN");
            insertUser(PARENT_ID, PARENT_EMAIL, "PARENT");
            jdbcTemplate.update(
                "INSERT INTO marketplace.coach_profiles (id, user_id, display_name, bio, city, languages, "
                    + "canonical_timezone, status) VALUES (?, ?, 'Cancel Coach', 'Bio', 'Berlin', "
                    + "ARRAY['English']::varchar[], 'Europe/Berlin', 'ACTIVE')",
                coachId, COACH_USER_ID);
            // Deliberately different from what the bookings below were charged (40.00): a refund is what
            // was paid, not the coach's current price.
            jdbcTemplate.update(
                "INSERT INTO marketplace.coach_pricing (coach_id, per_session_price, currency) VALUES (?, 55.00, 'EUR')",
                coachId);
            jdbcTemplate.update(
                "INSERT INTO payment.session_pack_tiers (pack_tier_id, coach_id, label, session_count, total_price, "
                    + "price_per_session, is_active, version, created_at) "
                    + "VALUES (?, ?, '5-Pack', 5, 150.00, 30.00, true, 0, now())", packTierId, coachId);
            jdbcTemplate.update(
                "INSERT INTO payment.session_pack_purchases (purchase_id, parent_id, player_id, coach_id, pack_tier_id, "
                    + "price_per_session, remaining_sessions, expires_at, version, created_at) "
                    + "VALUES (?, ?, ?, ?, ?, 30.00, 3, ?, 0, now())",
                packId, PARENT_ID, PLAYER_ID, coachId, packTierId,
                Timestamp.from(Instant.now().plus(90, ChronoUnit.DAYS)));
            return null;
        });
    }

    @AfterEach
    void tearDown() {
        transactionTemplate.execute(status -> {
            jdbcTemplate.update("DELETE FROM admin.admin_action_log WHERE reference_id IN "
                + "(SELECT id::text FROM booking.bookings WHERE coach_id = ?)", coachId);
            jdbcTemplate.update("DELETE FROM admin.admin_alerts WHERE reference_id IN "
                + "(SELECT id::text FROM booking.bookings WHERE coach_id = ?)", coachId);
            jdbcTemplate.update("DELETE FROM main.outbox_messages WHERE aggregate_type = ? AND payload->>'bookingId' IN "
                + "(SELECT id::text FROM booking.bookings WHERE coach_id = ?)", RefundOutboxSupport.AGGREGATE_TYPE, coachId);
            jdbcTemplate.execute("SET SESSION session_replication_role = 'replica'");
            jdbcTemplate.update("DELETE FROM payment.parent_credit_ledger WHERE parent_id = ?", PARENT_ID);
            jdbcTemplate.execute("SET SESSION session_replication_role = 'origin'");
            jdbcTemplate.update("DELETE FROM payment.booking_payments WHERE booking_id IN "
                + "(SELECT id FROM booking.bookings WHERE coach_id = ?)", coachId);
            jdbcTemplate.update("DELETE FROM marketplace.coach_reliability_strikes WHERE coach_id = ?", coachId);
            jdbcTemplate.update("DELETE FROM payment.coach_cancellation_history WHERE coach_id = ?", coachId);
            jdbcTemplate.update("DELETE FROM booking.bookings WHERE coach_id = ?", coachId);
            jdbcTemplate.update("DELETE FROM payment.session_pack_purchases WHERE purchase_id = ?", packId);
            jdbcTemplate.update("DELETE FROM payment.session_pack_tiers WHERE pack_tier_id = ?", packTierId);
            jdbcTemplate.update("DELETE FROM marketplace.coach_subscriptions WHERE coach_id = ?", coachId);
            jdbcTemplate.update("DELETE FROM marketplace.coach_pricing WHERE coach_id = ?", coachId);
            jdbcTemplate.update("DELETE FROM marketplace.coach_profiles WHERE id = ?", coachId);
            jdbcTemplate.update("DELETE FROM main.\"user\" WHERE id IN (?, ?, ?)", COACH_USER_ID, ADMIN_ID, PARENT_ID);
            return null;
        });
    }

    // ── coach cancellation: refund only captured money ──

    @Test
    void coachCancelsAnAcceptedCardBooking_refundsNothing_butStillStrikes() {
        UUID accepted = booking("ACCEPTED", null, base());

        bookingService.cancelBookingAsCoach(accepted, COACH_USER_ID, "SCHEDULING_PREFERENCE");

        assertThat(statusOf(accepted)).isEqualTo("CANCELLED_COACH");
        assertThat(refundRows(accepted)).as("nothing was charged — no wallet credit").isZero();
        assertThat(count("SELECT COUNT(*) FROM marketplace.coach_reliability_strikes WHERE coach_id = ?"))
            .as("strike policy unchanged: an accepted booking the coach drops still lets the family down")
            .isEqualTo(1);
        Awaitility.await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
            assertThat(mailsToParent(EmailTemplate.BOOKING_CANCELLED_BY_COACH)).singleElement()
                .satisfies(e -> assertThat(e.data()).containsEntry("refundType", "NONE")));
    }

    @Test
    void coachCancelsAnAcceptedPackBooking_restoresNoSession() {
        UUID accepted = booking("ACCEPTED", packId, base());

        bookingService.cancelBookingAsCoach(accepted, COACH_USER_ID, "MUTUAL_AGREEMENT");

        assertThat(statusOf(accepted)).isEqualTo("CANCELLED_COACH");
        assertThat(refundRows(accepted)).isZero();
        // CancellationRefundService runs AFTER_COMMIT: give it the chance to (wrongly) restore.
        Awaitility.await().during(Duration.ofSeconds(1)).atMost(Duration.ofSeconds(3)).untilAsserted(() ->
            assertThat(remainingPackSessions()).as("the session was never deducted (that happens at settle)")
                .isEqualTo(3));
    }

    @Test
    void coachCancelsAConfirmedCardBooking_refundsWhatWasPaid_notTheCurrentPrice() {
        UUID confirmed = booking("CONFIRMED", null, base());
        capturedPayment(confirmed, "10.00", "30.00");

        bookingService.cancelBookingAsCoach(confirmed, COACH_USER_ID, "HEALTH_MEDICAL");

        assertThat(refundRows(confirmed)).isEqualTo(1);
        outboxService.drain();
        Awaitility.await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
            assertThat(refundedAmount(confirmed)).isEqualByComparingTo("40.00"));
        assertThat(mailsToParent(EmailTemplate.BOOKING_CANCELLED_BY_COACH)).singleElement()
            .satisfies(e -> assertThat(e.data()).containsEntry("refundType", "CREDIT").containsEntry("refundAmount", "40.00"));
    }

    @Test
    void coachCancelsAnUpcomingPackBooking_restoresTheSession() {
        UUID upcoming = booking("UPCOMING", packId, base());
        capturedPayment(upcoming, "0.00", "0.00");

        bookingService.cancelBookingAsCoach(upcoming, COACH_USER_ID, "WEATHER");

        assertThat(refundRows(upcoming)).isZero();
        Awaitility.await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
            assertThat(remainingPackSessions()).isEqualTo(4));
    }

    @Test
    void coachNoShow_refundsWhatWasPaid() {
        // Started an hour ago: a no-show cannot be reported before the start time.
        UUID upcoming = booking("UPCOMING", null, Instant.now().minus(1, ChronoUnit.HOURS).truncatedTo(ChronoUnit.SECONDS));
        capturedPayment(upcoming, "0.00", "40.00");

        bookingService.recordNoShowCoach(upcoming, PARENT_ID);

        assertThat(statusOf(upcoming)).isEqualTo("NO_SHOW_COACH");
        outboxService.drain();
        Awaitility.await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
            assertThat(refundedAmount(upcoming)).isEqualByComparingTo("40.00"));
        assertThat(mailsToParent(EmailTemplate.COACH_NO_SHOW)).singleElement()
            .satisfies(e -> assertThat(e.data()).containsEntry("refundType", "CREDIT").containsEntry("refundAmount", "40.00"));
    }

    // ── admin single-booking cancellation ──

    @Test
    void adminCancel_confirmedBooking_fullRefund_noStrike_neutralNotice_auditedAndAlertClosed() {
        UUID confirmed = booking("CONFIRMED", null, base());
        capturedPayment(confirmed, "10.00", "30.00");
        UUID alertId = openSafetyHoldAlert(confirmed, "PAYMENT_IN_FLIGHT");

        AdminBookingCancellationOutcome outcome =
            adminBookingCancellationService.cancel(confirmed, ADMIN_ID, "safety-hold follow-up");

        assertThat(outcome).isEqualTo(AdminBookingCancellationOutcome.CANCELLED);
        assertThat(statusOf(confirmed)).isEqualTo("CANCELLED");
        assertThat(jdbcTemplate.queryForObject(
            "SELECT cancel_reason FROM booking.bookings WHERE id = ?", String.class, confirmed)).isEqualTo("ADMIN_SUPPORT");
        outboxService.drain();
        Awaitility.await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
            assertThat(refundedAmount(confirmed)).isEqualByComparingTo("40.00"));
        assertThat(count("SELECT COUNT(*) FROM marketplace.coach_reliability_strikes WHERE coach_id = ?")).isZero();
        assertThat(count("SELECT COUNT(*) FROM payment.coach_cancellation_history WHERE coach_id = ?")).isZero();
        Awaitility.await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
            assertThat(mailsToParent(EmailTemplate.BOOKING_CANCELLED_COACH_UNAVAILABLE)).singleElement()
                .satisfies(e -> assertThat(e.data())
                    .containsEntry("cancelledBy", "SKILLARS").containsEntry("refundType", "CREDIT")));
        // The coach is told too, so they do not turn up for a session that no longer exists.
        assertThat(testMailManager.getEnvelopes().values().stream()
            .filter(e -> e.emailTemplate() == EmailTemplate.BOOKING_CANCELLED_BY_SKILLARS)
            .filter(e -> e.recipients().stream().anyMatch(r -> COACH_EMAIL.equalsIgnoreCase(r.getEmail()))))
            .hasSize(1);
        // The credit statement line matches the notice: a Skillars cancellation, not "coach unavailable".
        assertThat(jdbcTemplate.queryForObject(
            "SELECT description FROM payment.parent_credit_ledger WHERE reference_id = ? AND type = 'BOOKING_REFUND'",
            String.class, confirmed)).startsWith("Cancelled by Skillars");
        assertThat(auditReasons(confirmed)).containsExactly("safety-hold follow-up [outcome: CANCELLED]");
        assertThat(jdbcTemplate.queryForMap(
            "SELECT status, resolved_by FROM admin.admin_alerts WHERE alert_id = ?", alertId))
            .containsEntry("status", "RESOLVED").containsEntry("resolved_by", ADMIN_ID);
    }

    @Test
    void adminCancel_upcomingPackBooking_restoresTheSession() {
        UUID upcoming = booking("UPCOMING", packId, base());
        capturedPayment(upcoming, "0.00", "0.00");

        assertThat(adminBookingCancellationService.cancel(upcoming, ADMIN_ID, "support"))
            .isEqualTo(AdminBookingCancellationOutcome.CANCELLED);

        assertThat(refundRows(upcoming)).isZero();
        Awaitility.await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
            assertThat(remainingPackSessions()).isEqualTo(4));
    }

    @Test
    void adminCancel_liveSession_isRefused_auditedAndTheAlertStaysOpen() {
        UUID live = booking("IN_PROGRESS", null, base());
        UUID alertId = openSafetyHoldAlert(live, "IN_PROGRESS");

        assertThat(adminBookingCancellationService.cancel(live, ADMIN_ID, "try"))
            .isEqualTo(AdminBookingCancellationOutcome.LIVE_SESSION);

        assertThat(statusOf(live)).isEqualTo("IN_PROGRESS");
        assertThat(auditReasons(live)).containsExactly("try [outcome: LIVE_SESSION]");
        assertThat(jdbcTemplate.queryForObject(
            "SELECT status FROM admin.admin_alerts WHERE alert_id = ?", String.class, alertId)).isEqualTo("OPEN");
        assertThat(mailsToParent(EmailTemplate.BOOKING_CANCELLED_COACH_UNAVAILABLE)).isEmpty();
    }

    @Test
    void adminCancel_captureInFlight_isRefused() {
        UUID inFlight = booking("PAYMENT_PENDING", null, base());
        transactionTemplate.execute(s -> jdbcTemplate.update(
            "INSERT INTO payment.booking_payments (booking_id, credit_debited, stripe_charged, status, reserved_at) "
                + "VALUES (?, 0, 40.00, 'CAPTURE_PENDING', now())", inFlight));

        assertThat(adminBookingCancellationService.cancel(inFlight, ADMIN_ID, "try"))
            .isEqualTo(AdminBookingCancellationOutcome.PAYMENT_IN_PROGRESS);

        assertThat(statusOf(inFlight)).isEqualTo("PAYMENT_PENDING");
        assertThat(refundRows(inFlight)).isZero();
    }

    // ── helpers ──

    private Instant base() {
        return base.plus(3L * slot++, ChronoUnit.HOURS);
    }

    private UUID booking(String status, UUID packPurchaseId, Instant start) {
        UUID id = UUID.randomUUID();
        // One Instant for start and end (chk_bkg_end_after_start).
        transactionTemplate.execute(s -> jdbcTemplate.update(
            "INSERT INTO booking.bookings (id, coach_id, parent_id, player_id, status, requested_start_time, "
                + "requested_end_time, version, created_at, updated_at, canonical_timezone, session_pack_purchase_id) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, 0, now(), now(), 'Europe/Berlin', ?)",
            id, coachId, PARENT_ID, PLAYER_ID, status,
            Timestamp.from(start), Timestamp.from(start.plus(1, ChronoUnit.HOURS)), packPurchaseId));
        return id;
    }

    private void capturedPayment(UUID bookingId, String credit, String card) {
        transactionTemplate.execute(s -> jdbcTemplate.update(
            "INSERT INTO payment.booking_payments (booking_id, credit_debited, stripe_charged, status, captured_at) "
                + "VALUES (?, ?, ?, 'CAPTURED', now())", bookingId, new BigDecimal(credit), new BigDecimal(card)));
    }

    private UUID openSafetyHoldAlert(UUID bookingId, String state) {
        UUID alertId = UUID.randomUUID();
        transactionTemplate.execute(s -> jdbcTemplate.update(
            "INSERT INTO admin.admin_alerts (alert_id, type, reference_id, reference_type, reason, status, created_at) "
                + "VALUES (?, 'SAFETY_HOLD_BOOKING', ?, 'BOOKING', ?, 'OPEN', now())",
            alertId, bookingId.toString(), state));
        return alertId;
    }

    private String statusOf(UUID bookingId) {
        return jdbcTemplate.queryForObject("SELECT status FROM booking.bookings WHERE id = ?", String.class, bookingId);
    }

    private int remainingPackSessions() {
        return jdbcTemplate.queryForObject(
            "SELECT remaining_sessions FROM payment.session_pack_purchases WHERE purchase_id = ?", Integer.class, packId);
    }

    private BigDecimal refundedAmount(UUID bookingId) {
        return jdbcTemplate.queryForObject(
            "SELECT amount FROM payment.parent_credit_ledger WHERE reference_id = ? AND type = 'BOOKING_REFUND'",
            BigDecimal.class, bookingId);
    }

    private int refundRows(UUID bookingId) {
        Integer n = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM main.outbox_messages WHERE aggregate_type = ? AND payload->>'bookingId' = ?",
            Integer.class, RefundOutboxSupport.AGGREGATE_TYPE, bookingId.toString());
        Integer ledger = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM payment.parent_credit_ledger WHERE reference_id = ? AND type = 'BOOKING_REFUND'",
            Integer.class, bookingId);
        return Math.max(n == null ? 0 : n, ledger == null ? 0 : ledger);
    }

    private List<String> auditReasons(UUID bookingId) {
        return jdbcTemplate.queryForList(
            "SELECT reason FROM admin.admin_action_log WHERE reference_id = ? AND action_type = 'BOOKING_ADMIN_CANCEL'",
            String.class, bookingId.toString());
    }

    private long count(String sql) {
        Long n = jdbcTemplate.queryForObject(sql, Long.class, coachId);
        return n == null ? 0 : n;
    }

    private List<Envelope> mailsToParent(EmailTemplate template) {
        return testMailManager.getEnvelopes().values().stream()
            .filter(e -> e.emailTemplate() == template)
            .filter(e -> e.recipients().stream().anyMatch(r -> PARENT_EMAIL.equalsIgnoreCase(r.getEmail())))
            .toList();
    }

    private void insertUser(long id, String email, String role) {
        jdbcTemplate.update(
            "INSERT INTO main.\"user\" "
                + "(id, created_by, created_date, last_modified_by, last_modified_date, request_id, session_id, "
                + "status, dob, email, first_name, gender, lang_key, last_name, iso2_country, phone, "
                + "activated, locked, login, login_id_type, password_hash, otp_enabled, "
                + "skillars_role, verification_status) "
                + "VALUES (?, 'system', ?, 'system', ?, 'test-req', NULL, "
                + "'ACTIVE', '1985-06-01', ?, 'Test', 'OTHER', 'en', 'User', 'DE', ?, "
                + "true, false, ?, 'EMAIL', ?, false, ?, 'BASIC_VERIFIED')",
            id, Timestamp.from(Instant.now()), Timestamp.from(Instant.now()),
            email, "9189" + (id % 1000000), email, PASSWORD_HASH, role);
    }
}
