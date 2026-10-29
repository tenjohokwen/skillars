package com.softropic.skillars.platform.admin.service;

import com.softropic.skillars.config.AbstractIntegrationTest;
import com.softropic.skillars.platform.admin.contract.AdminAlertDto;
import com.softropic.skillars.platform.admin.contract.AdminSuspendCoachResponse;
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
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * C2 (DECISIONS.md item 2), end to end against the real database and event wiring: a coach suspension
 * (admin, by default) and a child-safety hold cancel the coach's not-yet-started bookings with a full
 * refund through the original funding rule (card/credit -> credit wallet, pack -> session restored), no
 * reliability strike, a neutral parent notice; the admin can opt out; an in-flight capture refuses the
 * admin suspension (409, all-or-nothing) and is alerted on a safety hold, as are live sessions; a
 * suspension racing a parent cancellation never refunds twice.
 *
 * <p>Reuses {@link AbstractIntegrationTest}'s context verbatim — no extra mocks or properties.
 */
class CoachSuspensionCancelsUpcomingBookingsIT extends AbstractIntegrationTest {

    private static final long COACH_USER_ID = 9185_000_010L;
    private static final long ADMIN_ID = 9185_000_100L;
    private static final long PARENT_ID = 9185_000_001L;
    private static final long PLAYER_ID = 9185_000_002L;
    private static final String PARENT_EMAIL = "parent.c2suspend9185@skillars-test.com";
    private static final String PASSWORD_HASH = "$2a$10$" + "a".repeat(53);

    @Autowired private AdminCoachEnforcementService enforcementService;
    @Autowired private AdminQueueService adminQueueService;
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
        // >24h out, so a parent cancellation of a captured booking is refund-eligible too.
        base = Instant.now().plus(3, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS);
        slot = 0;
        testMailManager.clear();
        transactionTemplate.execute(status -> {
            insertUser(COACH_USER_ID, "coach.c2suspend9185@skillars-test.com", "COACH");
            insertUser(ADMIN_ID, "admin.c2suspend9185@skillars-test.com", "ADMIN");
            insertUser(PARENT_ID, PARENT_EMAIL, "PARENT");
            jdbcTemplate.update(
                "INSERT INTO marketplace.coach_profiles (id, user_id, display_name, bio, city, languages, "
                    + "canonical_timezone, status) VALUES (?, ?, 'Unavailable Coach', 'Bio', 'Berlin', "
                    + "ARRAY['English']::varchar[], 'Europe/Berlin', 'ACTIVE')",
                coachId, COACH_USER_ID);
            // Deliberately different from what the bookings below were charged (40.00): a full refund is
            // what was paid, not the coach's current price.
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
            jdbcTemplate.update("DELETE FROM admin.admin_action_log WHERE reference_id = ?", coachId.toString());
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
            // user_notifications cascades from main."user".
            jdbcTemplate.update("DELETE FROM main.\"user\" WHERE id IN (?, ?, ?)", COACH_USER_ID, ADMIN_ID, PARENT_ID);
            return null;
        });
    }

    // ── admin suspension: default cancels every pre-session booking, refunded per funding source ──

    @Test
    void adminSuspension_cancelsEveryUpcomingBooking_withFullRefunds_noStrike_andNeutralNotices() {
        UUID requested = booking("REQUESTED", null);
        UUID accepted = booking("ACCEPTED", null);
        UUID paymentPendingNoCapture = booking("PAYMENT_PENDING", null);
        UUID confirmedCard = booking("CONFIRMED", null);
        capturedPayment(confirmedCard, "10.00", "30.00");
        UUID upcomingPack = booking("UPCOMING", packId);
        capturedPayment(upcomingPack, "0.00", "0.00");
        UUID inProgress = booking("IN_PROGRESS", null);

        AdminSuspendCoachResponse result =
            enforcementService.suspendCoach(coachId, "Policy breach", false, ADMIN_ID, true);

        assertThat(result).isEqualTo(new AdminSuspendCoachResponse(true, 5, 0));
        for (UUID id : List.of(requested, accepted, paymentPendingNoCapture, confirmedCard, upcomingPack)) {
            assertThat(statusOf(id)).as("booking %s", id).isEqualTo("CANCELLED");
            assertThat(cancelReasonOf(id)).isEqualTo("COACH_UNAVAILABLE");
        }
        assertThat(statusOf(inProgress)).as("a live session is left to finish").isEqualTo("IN_PROGRESS");

        // Card/credit booking: one wallet refund of what was actually paid (credit 10 + card 30).
        assertThat(refundOutboxRows(confirmedCard)).isEqualTo(1);
        outboxService.drain();
        Awaitility.await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
            assertThat(jdbcTemplate.queryForObject(
                "SELECT amount FROM payment.parent_credit_ledger WHERE reference_id = ? AND type = 'BOOKING_REFUND'",
                BigDecimal.class, confirmedCard)).isEqualByComparingTo("40.00"));
        // Pre-capture bookings took no money: nothing refunded for them.
        for (UUID id : List.of(requested, accepted, paymentPendingNoCapture, upcomingPack)) {
            assertThat(refundOutboxRows(id)).as("no wallet refund for %s", id).isZero();
        }
        // Pack booking: the session goes back to the pack (3 -> 4), not to the wallet.
        assertThat(jdbcTemplate.queryForObject(
            "SELECT remaining_sessions FROM payment.session_pack_purchases WHERE purchase_id = ?", Integer.class, packId))
            .isEqualTo(4);

        // No reliability strike, no coach-cancellation history: the coach did not cancel.
        assertThat(count("SELECT COUNT(*) FROM marketplace.coach_reliability_strikes WHERE coach_id = ?")).isZero();
        assertThat(count("SELECT COUNT(*) FROM payment.coach_cancellation_history WHERE coach_id = ?")).isZero();

        // One neutral notice per cancelled booking (email + inbox row), never naming the reason.
        Awaitility.await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
            assertThat(noticesToParent()).hasSize(5));
        assertThat(noticesToParent()).allSatisfy(e -> {
            assertThat(e.data()).containsEntry("coachDisplayName", "Unavailable Coach");
            assertThat(e.data().values()).noneMatch(v -> String.valueOf(v).contains("SUSPEND"));
        });
        assertThat(noticesToParent()).extracting(e -> e.data().get("refundType"))
            .containsExactlyInAnyOrder("NONE", "NONE", "NONE", "CREDIT", "PACK");
        assertThat(jdbcTemplate.queryForList(
            "SELECT type || '|' || deep_link FROM main.user_notifications WHERE recipient_user_id = ?",
            String.class, PARENT_ID))
            .hasSize(5).containsOnly("BOOKING_CANCELLED_COACH_UNAVAILABLE|/parent/bookings");
    }

    @Test
    void adminSuspension_optOut_cancelsOnlyRequestedBookings() {
        UUID requested = booking("REQUESTED", null);
        UUID confirmed = booking("CONFIRMED", null);
        capturedPayment(confirmed, "0.00", "40.00");

        AdminSuspendCoachResponse result =
            enforcementService.suspendCoach(coachId, "Policy breach", false, ADMIN_ID, false);

        assertThat(result.cancelledBookings()).isEqualTo(1);
        assertThat(statusOf(requested)).isEqualTo("CANCELLED");
        assertThat(statusOf(confirmed)).isEqualTo("CONFIRMED");
        assertThat(refundOutboxRows(confirmed)).isZero();
    }

    /**
     * fix/payment-wallet-dispute-integrity: no longer all-or-nothing. A booking whose capture is in flight is
     * retried once, then skipped with a BOOKING_ACTION_REQUIRED alert; the coach is still suspended and every
     * other booking is cancelled (each in its own transaction) — the suspension no longer 409s as a whole.
     */
    @Test
    void adminSuspension_withACaptureInFlight_suspendsAndCancelsTheRest_andAlertsOnTheBusyBooking() {
        UUID requested = booking("REQUESTED", null);
        UUID inFlight = booking("PAYMENT_PENDING", null);
        capturePending(inFlight);

        AdminSuspendCoachResponse result =
            enforcementService.suspendCoach(coachId, "Policy breach", false, ADMIN_ID, true);

        assertThat(result.newlySuspended()).isTrue();
        assertThat(result.cancelledBookings()).isEqualTo(1);
        assertThat(result.alertedBookings()).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
            "SELECT status FROM marketplace.coach_profiles WHERE id = ?", String.class, coachId)).isEqualTo("SUSPENDED");
        assertThat(statusOf(requested)).isEqualTo("CANCELLED");
        assertThat(statusOf(inFlight)).isEqualTo("PAYMENT_PENDING");
        assertThat(jdbcTemplate.queryForList(
            "SELECT reason FROM admin.admin_alerts WHERE reference_id = ? AND type = 'BOOKING_ACTION_REQUIRED' "
                + "AND status = 'OPEN'", String.class, inFlight.toString()))
            .containsExactly("SUSPENSION_CANCEL_SKIPPED PAYMENT_IN_FLIGHT");
    }

    // ── child-safety hold ──

    @Test
    void safetyHold_cancelsUpcomingBookings_andRaisesUrgentAlertsForWhatItCannotCancel() {
        UUID confirmed = booking("CONFIRMED", null);
        capturedPayment(confirmed, "0.00", "40.00");
        UUID inProgress = booking("IN_PROGRESS", null);
        UUID paused = booking("PAUSED", null);
        UUID inFlight = booking("PAYMENT_PENDING", null);
        capturePending(inFlight);

        AdminSuspendCoachResponse result = enforcementService.suspendCoachForSafetyHold(coachId, UUID.randomUUID());

        assertThat(result).isEqualTo(new AdminSuspendCoachResponse(true, 1, 3));
        assertThat(statusOf(confirmed)).isEqualTo("CANCELLED");
        // Neutral even on the row: the booking reaches the parent in their GDPR export.
        assertThat(cancelReasonOf(confirmed)).isEqualTo("COACH_UNAVAILABLE");
        assertThat(refundOutboxRows(confirmed)).isEqualTo(1);
        assertThat(statusOf(inProgress)).isEqualTo("IN_PROGRESS");
        assertThat(statusOf(paused)).isEqualTo("PAUSED");
        assertThat(statusOf(inFlight)).isEqualTo("PAYMENT_PENDING");

        assertThat(jdbcTemplate.queryForList(
            "SELECT reference_id || '|' || reference_type || '|' || reason FROM admin.admin_alerts "
                + "WHERE type = 'SAFETY_HOLD_BOOKING' AND status = 'OPEN' AND reference_id IN (?, ?, ?)",
            String.class, inProgress.toString(), paused.toString(), inFlight.toString()))
            .containsExactlyInAnyOrder(
                inProgress + "|BOOKING|IN_PROGRESS",
                paused + "|BOOKING|PAUSED",
                inFlight + "|BOOKING|PAYMENT_IN_FLIGHT");

        // The admin queue names the parent to contact.
        List<AdminAlertDto> queue = adminQueueService.getAlerts("SAFETY_HOLD_BOOKING", 0).getContent();
        assertThat(queue).filteredOn(a -> a.referenceId().equals(inProgress.toString())).singleElement()
            .satisfies(a -> assertThat(a.summary()).startsWith("URGENT").contains(PARENT_EMAIL).contains("IN_PROGRESS"));
        assertThat(adminQueueService.getSummary().safetyHoldBookings()).isGreaterThanOrEqualTo(3);

        // A re-delivered hold is a no-op: no second cancellation, no duplicate alert.
        assertThat(enforcementService.suspendCoachForSafetyHold(coachId, UUID.randomUUID()))
            .isEqualTo(AdminSuspendCoachResponse.alreadySuspended());
        assertThat(count("SELECT COUNT(*) FROM admin.admin_alerts WHERE type = 'SAFETY_HOLD_BOOKING' "
            + "AND reference_id IN (SELECT id::text FROM booking.bookings WHERE coach_id = ?)")).isEqualTo(3);
    }

    /**
     * A CSAM hold on a coach an admin had already suspended (here: opting out of the cancellation) must
     * not be a no-op: it becomes the suspension in force (new statusChangedAt + COACH_SAFETY_HOLD row,
     * so it is non-appealable and the payout freeze sees it) and still cancels the upcoming bookings.
     */
    @Test
    void safetyHold_onACoachAlreadySuspendedByAnAdmin_convertsTheSuspension_andCancelsUpcomingBookings() {
        UUID confirmed = booking("CONFIRMED", null);
        capturedPayment(confirmed, "0.00", "40.00");
        enforcementService.suspendCoach(coachId, "Policy breach", false, ADMIN_ID, false);
        assertThat(statusOf(confirmed)).isEqualTo("CONFIRMED");
        Timestamp adminSuspendedAt = jdbcTemplate.queryForObject(
            "SELECT status_changed_at FROM marketplace.coach_profiles WHERE id = ?", Timestamp.class, coachId);

        AdminSuspendCoachResponse result = enforcementService.suspendCoachForSafetyHold(coachId, UUID.randomUUID());

        assertThat(result).isEqualTo(new AdminSuspendCoachResponse(true, 1, 0));
        assertThat(statusOf(confirmed)).isEqualTo("CANCELLED");
        Timestamp heldAt = jdbcTemplate.queryForObject(
            "SELECT status_changed_at FROM marketplace.coach_profiles WHERE id = ?", Timestamp.class, coachId);
        assertThat(heldAt).isAfter(adminSuspendedAt);
        assertThat(count("SELECT COUNT(*) FROM admin.admin_action_log WHERE reference_id = ?::text "
            + "AND action_type = 'COACH_SAFETY_HOLD'")).isEqualTo(1);
    }

    /**
     * The takedown commits before the booking sweep: a booking row held past the lock-retry budget
     * fails the sweep, never the hold itself — the profile is SUSPENDED regardless (and a re-run sweeps).
     */
    @Test
    void safetyHold_bookingLockContention_failsOnlyTheSweep_theProfileStaysSuspended() throws Exception {
        UUID contested = booking("CONFIRMED", null);
        capturedPayment(contested, "0.00", "40.00");
        CountDownLatch rowHeld = new CountDownLatch(1);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<?> holder = executor.submit(() -> transactionTemplate.execute(status -> {
                jdbcTemplate.queryForObject("SELECT status FROM booking.bookings WHERE id = ? FOR UPDATE",
                    String.class, contested);
                rowHeld.countDown();
                try {
                    Thread.sleep(6000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return null;
            }));
            assertThat(rowHeld.await(10, TimeUnit.SECONDS)).isTrue();

            assertThatThrownBy(() -> enforcementService.suspendCoachForSafetyHold(coachId, UUID.randomUUID()))
                .isInstanceOf(org.springframework.dao.PessimisticLockingFailureException.class);
            assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM marketplace.coach_profiles WHERE id = ?", String.class, coachId))
                .isEqualTo("SUSPENDED");
            holder.get(15, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
        }

        // Re-run (as the listener / a re-delivered event does): the hold is not re-applied, the sweep is.
        assertThat(enforcementService.suspendCoachForSafetyHold(coachId, UUID.randomUUID()))
            .isEqualTo(new AdminSuspendCoachResponse(false, 1, 0));
        assertThat(statusOf(contested)).isEqualTo("CANCELLED");
    }

    @Test
    void aSafetyHoldBookingAlert_canBeMarkedHandledOnce() {
        UUID live = booking("IN_PROGRESS", null);
        enforcementService.suspendCoachForSafetyHold(coachId, UUID.randomUUID());
        UUID alertId = jdbcTemplate.queryForObject(
            "SELECT alert_id FROM admin.admin_alerts WHERE reference_id = ? AND type = 'SAFETY_HOLD_BOOKING'",
            UUID.class, live.toString());

        adminQueueService.resolveSafetyHoldBookingAlert(alertId, ADMIN_ID);

        assertThat(jdbcTemplate.queryForMap(
            "SELECT status, resolved_by FROM admin.admin_alerts WHERE alert_id = ?", alertId))
            .containsEntry("status", "RESOLVED").containsEntry("resolved_by", ADMIN_ID);
        assertThatThrownBy(() -> adminQueueService.resolveSafetyHoldBookingAlert(alertId, ADMIN_ID))
            .isInstanceOfSatisfying(ResponseStatusException.class,
                e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));
    }

    // ── concurrency: suspension racing a parent cancellation ──

    /**
     * A parent cancellation holds the booking row (it has written CANCELLED_PARENT and not yet
     * committed) while the suspension sweeps. The sweep must wait for that row lock, then re-read the
     * fresh status and skip it: one cancellation, one refund — never a second, admin, refund.
     */
    @Test
    void suspensionRacingAParentCancellation_waitsForTheRowLock_andNeverRefundsTwice() throws Exception {
        UUID contested = booking("CONFIRMED", null);
        capturedPayment(contested, "0.00", "40.00");
        CountDownLatch parentHoldsTheRow = new CountDownLatch(1);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<?> parent = executor.submit(() -> transactionTemplate.execute(status -> {
                jdbcTemplate.queryForObject("SELECT status FROM booking.bookings WHERE id = ? FOR UPDATE",
                    String.class, contested);
                jdbcTemplate.update("UPDATE booking.bookings SET status = 'CANCELLED_PARENT', version = version + 1 "
                    + "WHERE id = ?", contested);
                parentHoldsTheRow.countDown();
                try {
                    Thread.sleep(1500);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return null;
            }));
            assertThat(parentHoldsTheRow.await(10, TimeUnit.SECONDS)).isTrue();

            Instant started = Instant.now();
            AdminSuspendCoachResponse result =
                enforcementService.suspendCoach(coachId, "Policy breach", false, ADMIN_ID, true);
            Duration waited = Duration.between(started, Instant.now());
            parent.get(10, TimeUnit.SECONDS);

            assertThat(waited).as("the sweep must have contended for the booking row lock")
                .isGreaterThanOrEqualTo(Duration.ofMillis(1000));
            assertThat(result.cancelledBookings()).isZero();
            assertThat(statusOf(contested)).isEqualTo("CANCELLED_PARENT");
            assertThat(refundOutboxRows(contested)).as("no admin refund on top of the parent's").isZero();
        } finally {
            executor.shutdownNow();
        }
    }

    /** The other order: once the suspension has cancelled (and refunded) it, a parent cancel is refused. */
    @Test
    void parentCancellationAfterTheSuspension_isRejected_soTheRefundIsNotDoubled() {
        UUID confirmed = booking("CONFIRMED", null);
        capturedPayment(confirmed, "0.00", "40.00");
        enforcementService.suspendCoach(coachId, "Policy breach", false, ADMIN_ID, true);

        assertThatThrownBy(() -> bookingService.cancelBookingAsParent(confirmed, PARENT_ID))
            .isInstanceOf(com.softropic.skillars.platform.booking.contract.BookingStateTransitionException.class);

        assertThat(statusOf(confirmed)).isEqualTo("CANCELLED");
        assertThat(refundOutboxRows(confirmed)).isEqualTo(1);
    }

    // ── helpers ──

    private UUID booking(String status, UUID packPurchaseId) {
        UUID id = UUID.randomUUID();
        // One Instant for start and end (chk_bkg_end_after_start); distinct slots (no-overlap constraint).
        Instant start = base.plus(3L * slot++, ChronoUnit.HOURS);
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

    private void capturePending(UUID bookingId) {
        transactionTemplate.execute(s -> jdbcTemplate.update(
            "INSERT INTO payment.booking_payments (booking_id, credit_debited, stripe_charged, status, reserved_at) "
                + "VALUES (?, 0, 40.00, 'CAPTURE_PENDING', now())", bookingId));
    }

    private String statusOf(UUID bookingId) {
        return jdbcTemplate.queryForObject("SELECT status FROM booking.bookings WHERE id = ?", String.class, bookingId);
    }

    private String cancelReasonOf(UUID bookingId) {
        return jdbcTemplate.queryForObject("SELECT cancel_reason FROM booking.bookings WHERE id = ?", String.class, bookingId);
    }

    private int refundOutboxRows(UUID bookingId) {
        Integer n = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM main.outbox_messages WHERE aggregate_type = ? AND payload->>'bookingId' = ?",
            Integer.class, RefundOutboxSupport.AGGREGATE_TYPE, bookingId.toString());
        Integer ledger = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM payment.parent_credit_ledger WHERE reference_id = ? AND type = 'BOOKING_REFUND'",
            Integer.class, bookingId);
        // A drained refund leaves the ledger row instead of the outbox row; count whichever holds it.
        return Math.max(n == null ? 0 : n, ledger == null ? 0 : ledger);
    }

    private long count(String sql) {
        Long n = jdbcTemplate.queryForObject(sql, Long.class, coachId);
        return n == null ? 0 : n;
    }

    private List<Envelope> noticesToParent() {
        return testMailManager.getEnvelopes().values().stream()
            .filter(e -> e.emailTemplate() == EmailTemplate.BOOKING_CANCELLED_COACH_UNAVAILABLE)
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
            email, "9185" + (id % 1000000), email, PASSWORD_HASH, role);
    }
}
