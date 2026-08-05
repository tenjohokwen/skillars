package com.softropic.skillars.platform.payment.service;

import com.softropic.skillars.platform.booking.contract.BookingError;
import com.softropic.skillars.platform.booking.contract.CreateBookingRequest;
import com.softropic.skillars.platform.booking.service.BookingExpiryScheduler;
import com.softropic.skillars.platform.booking.service.BookingService;
import com.softropic.skillars.platform.payment.BasePaymentIT;
import com.softropic.skillars.platform.security.contract.exception.OperationNotAllowedException;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Story Deferred-15 AC2. Drives all four sweeper cases against a real database.
 *
 * <p>Cases (c) and (d) are the reason this class exists: they assert the ABSENCE of a transition and
 * of any booking_payments write, not the presence of a log line. A sweeper that logs ERROR and then
 * declines anyway would pass a log-only assertion while charging a parent for nothing.
 */
class PaymentPendingSweeperIT extends BasePaymentIT {

    private static final long PARENT_ID = 96001L;
    private static final long PLAYER_ID = 96003L;
    private static final long COACH_USER_ID = 96002L;
    private static final String TZ = "Europe/Berlin";

    @Autowired PaymentPendingSweeper sweeper;
    @Autowired BookingExpiryScheduler bookingExpiryScheduler;
    @Autowired BookingService bookingService;

    private UUID coachId;
    private Instant slotStart;
    private Instant slotEnd;

    @BeforeEach
    void setUpFixtures() {
        coachId = insertTestCoach(COACH_USER_ID, "sweeper_coach@test.com", "Sweeper Coach");
        insertTestParent(PARENT_ID, "sweeper_parent@test.com");
        insertTestPlayer(PLAYER_ID, PARENT_ID);

        ZonedDateTime nextDaySlot = ZonedDateTime.now(ZoneId.of(TZ)).plusDays(1)
            .withHour(10).withMinute(0).withSecond(0).withNano(0);
        slotStart = nextDaySlot.toInstant();
        slotEnd = nextDaySlot.plusHours(1).toInstant();
        short windowDow = (short) nextDaySlot.getDayOfWeek().getValue();

        transactionTemplate.execute(status -> {
            jdbcTemplate.update(
                "INSERT INTO marketplace.coach_pricing (coach_id, per_session_price, currency) " +
                "VALUES (?, 50.00, 'EUR') ON CONFLICT (coach_id) DO NOTHING", coachId);
            jdbcTemplate.update(
                "INSERT INTO marketplace.coach_availability_windows " +
                "(id, coach_id, day_of_week, start_time, end_time, canonical_timezone) " +
                "VALUES (?, ?, ?, '08:00', '18:00', ?)",
                UUID.randomUUID(), coachId, windowDow, TZ);
            return null;
        });
    }

    // Runs before BasePaymentIT.cleanPaymentData (JUnit 5 orders subclass @AfterEach first), which
    // deletes coach_profiles — the availability windows seeded above reference that row.
    @AfterEach
    void cleanWindows() {
        transactionTemplate.execute(status -> {
            jdbcTemplate.update("DELETE FROM marketplace.coach_availability_windows WHERE coach_id = ?", coachId);
            return null;
        });
    }

    // ── fixtures ──

    private UUID seedPackPurchase() {
        UUID tierId = UUID.randomUUID();
        UUID purchaseId = UUID.randomUUID();
        transactionTemplate.execute(status -> {
            jdbcTemplate.update(
                "INSERT INTO payment.session_pack_tiers (pack_tier_id, coach_id, label, session_count, " +
                "total_price, price_per_session, is_active) VALUES (?, ?, 'Sweep Tier', 10, 300.00, 30.00, false)",
                tierId, coachId);
            jdbcTemplate.update(
                "INSERT INTO payment.session_pack_purchases (purchase_id, parent_id, player_id, coach_id, " +
                "pack_tier_id, price_per_session, remaining_sessions, expires_at) " +
                "VALUES (?, ?, ?, ?, ?, 30.00, 9, ?)",
                purchaseId, PARENT_ID, PLAYER_ID, coachId, tierId,
                Timestamp.from(Instant.now().plus(60, ChronoUnit.DAYS)));
            return null;
        });
        return purchaseId;
    }

    /**
     * Seeds a booking resting in PAYMENT_PENDING with updated_at pushed into the past.
     *
     * <p>The timestamp is written directly rather than through JPA: a repository save would
     * re-stamp updated_at via Booking's @PreUpdate, which would silently make the past-grace cases
     * pass for the wrong reason. The assertion below pins that it actually landed.
     */
    private UUID seedStrandedBooking(UUID packPurchaseId, long minutesAgo) {
        UUID bookingId = UUID.randomUUID();
        Instant stamp = Instant.now().minus(minutesAgo, ChronoUnit.MINUTES);
        transactionTemplate.execute(status -> {
            jdbcTemplate.update(
                "INSERT INTO booking.bookings (id, parent_id, player_id, coach_id, requested_start_time, " +
                "requested_end_time, status, canonical_timezone, session_pack_purchase_id, version, " +
                "created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, 'PAYMENT_PENDING', ?, ?, 0, ?, ?)",
                bookingId, PARENT_ID, PLAYER_ID, coachId,
                Timestamp.from(slotStart), Timestamp.from(slotEnd), TZ, packPurchaseId,
                Timestamp.from(stamp), Timestamp.from(stamp));
            return null;
        });
        assertThat(jdbcTemplate.queryForObject(
            "SELECT updated_at FROM booking.bookings WHERE id = ?", Timestamp.class, bookingId).toInstant())
            .as("updated_at must actually be in the past, or the grace-period cases prove nothing")
            .isBefore(Instant.now().minus(minutesAgo - 1, ChronoUnit.MINUTES));
        return bookingId;
    }

    /** Always via this helper — see BasePaymentIT.releaseSchedulerLock for why. */
    private void sweep() {
        releaseSchedulerLock("PaymentPendingSweeper_sweep");
        sweeper.sweepStrandedPayments();
    }

    private String statusOf(UUID bookingId) {
        return jdbcTemplate.queryForObject("SELECT status FROM booking.bookings WHERE id = ?", String.class, bookingId);
    }

    private Integer paymentRowCount(UUID bookingId) {
        return jdbcTemplate.queryForObject(
            "SELECT count(*) FROM payment.booking_payments WHERE booking_id = ?", Integer.class, bookingId);
    }

    // ── AC2 (a) ──

    @Test
    void packFundedStrandedBooking_pastGrace_isSweptToDeclined() {
        UUID bookingId = seedStrandedBooking(seedPackPurchase(), 24 * 60);

        sweep();

        assertThat(statusOf(bookingId)).isEqualTo("DECLINED");
        assertThat(jdbcTemplate.queryForObject(
            "SELECT status FROM payment.booking_payments WHERE booking_id = ?", String.class, bookingId))
            .isEqualTo("CHARGE_FAILED");
        assertThat(jdbcTemplate.queryForObject(
            "SELECT refund_eligibility FROM booking.bookings WHERE id = ?", String.class, bookingId))
            .as("PAYMENT_FAILED has no applyRefundLogic branch — a swept booking must not imply a refund")
            .isNull();
    }

    /**
     * The user-visible harm the sweeper exists to end: while stranded, the booking holds the coach's
     * slot (PAYMENT_PENDING is in ACTIVE_SLOT_STATUSES and in V87's exclusion constraint), so nobody
     * else can book that time. Once swept, the slot is bookable again.
     */
    @Test
    void strandedBookingHoldsTheSlot_untilItIsSwept() {
        seedStrandedBooking(seedPackPurchase(), 24 * 60);
        CreateBookingRequest req = new CreateBookingRequest(coachId, PLAYER_ID, slotStart, slotEnd, TZ, null, null);

        assertThatThrownBy(() -> bookingService.createBookingRequest(PARENT_ID, req))
            .isInstanceOf(OperationNotAllowedException.class)
            .satisfies(t -> assertThat(((OperationNotAllowedException) t).getErrorCode())
                .isEqualTo(BookingError.SLOT_UNAVAILABLE));

        sweep();

        assertThat(bookingService.createBookingRequest(PARENT_ID, req)).isNotNull();
    }

    // ── AC2 (b) ──

    @Test
    void packFundedBooking_insideGracePeriod_isLeftAlone() {
        UUID bookingId = seedStrandedBooking(seedPackPurchase(), 30);

        sweep();

        assertThat(statusOf(bookingId))
            .as("30 minutes is inside the 120-minute default grace — settlement may still be in flight")
            .isEqualTo("PAYMENT_PENDING");
        assertThat(paymentRowCount(bookingId)).isZero();
    }

    // ── AC2 (c) ──

    @Test
    void packFundedBookingWithCapturedPaymentRow_isReportedNotDeclined() {
        UUID bookingId = seedStrandedBooking(seedPackPurchase(), 24 * 60);
        transactionTemplate.execute(status -> {
            jdbcTemplate.update(
                "INSERT INTO payment.booking_payments (booking_id, credit_debited, stripe_charged, status, captured_at) " +
                "VALUES (?, 0, 30.00, 'CAPTURED', ?)", bookingId, Timestamp.from(Instant.now()));
            return null;
        });

        sweep();

        assertThat(statusOf(bookingId))
            .as("PAYMENT_PENDING plus a captured payment row is a data-integrity failure for an operator, "
                + "not a booking to decline")
            .isEqualTo("PAYMENT_PENDING");
        assertThat(jdbcTemplate.queryForObject(
            "SELECT status FROM payment.booking_payments WHERE booking_id = ?", String.class, bookingId))
            .as("the existing CAPTURED row must not be overwritten")
            .isEqualTo("CAPTURED");
    }

    // ── AC2 (d) ──

    /**
     * The case that protects real money. On the credit path chargeAndCapture runs BEFORE the writes
     * recording it, so this booking may already have been charged at Stripe with the record lost —
     * and nothing durable tells that apart from "never charged". It must survive the sweep.
     * This is not a redundant variant of the pack-funded case; do not delete it.
     */
    @Test
    void creditFundedStrandedBooking_isReportedNotDeclined() {
        UUID bookingId = seedStrandedBooking(null, 24 * 60);

        sweep();

        assertThat(statusOf(bookingId)).isEqualTo("PAYMENT_PENDING");
        assertThat(paymentRowCount(bookingId))
            .as("no booking_payments write at all — a CHARGE_FAILED row here would falsely record "
                + "that no money moved")
            .isZero();
    }

    // ── Task 1 reproduction, kept as a regression pin ──

    @Test
    void noOtherSchedulerRecoversAStrandedBooking() {
        UUID bookingId = seedStrandedBooking(seedPackPurchase(), 24 * 60);

        // The only scheduler that terminates stale bookings; its query is REQUESTED-only.
        releaseSchedulerLock("BookingExpiryScheduler_expire");
        bookingExpiryScheduler.expireStaleRequests();

        assertThat(statusOf(bookingId)).isEqualTo("PAYMENT_PENDING");
    }
}
