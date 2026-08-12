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

import java.math.BigDecimal;
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
    @Autowired BookingPaymentPersistenceService persistenceService;

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
        CreateBookingRequest req = new CreateBookingRequest(coachId, PLAYER_ID, slotStart, slotEnd, null, null);

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
     * UAT.3 AC5, INVERTING what Deferred-15 asserted here. This booking used to survive the sweep
     * because nothing distinguished "never charged" from "charged and lost the record" on the
     * credit path. reserveCapture now writes a CAPTURE_PENDING row before either Stripe call, so
     * the absence of a row proves no charge was attempted — and the coach's slot can be handed back
     * safely, whatever the booking's funding type.
     */
    @Test
    void creditFundedStrandedBookingWithNoPaymentRow_isNowSweptToDeclined() {
        UUID bookingId = seedStrandedBooking(null, 24 * 60);

        sweep();

        assertThat(statusOf(bookingId)).isEqualTo("DECLINED");
        assertThat(jdbcTemplate.queryForObject(
            "SELECT status FROM payment.booking_payments WHERE booking_id = ?", String.class, bookingId))
            .isEqualTo("CHARGE_FAILED");
    }

    /**
     * The case that now protects real money, and the reason the funding-type precondition could be
     * dropped. A standing CAPTURE_PENDING row means an attempt reserved and never finished, so the
     * charge may already have landed at Stripe with nothing recording it. Declining here would hand
     * the slot back at the cost of charging a parent for nothing — exactly the harm Deferred-15
     * refused to risk. It must survive the sweep untouched and escalate to an operator instead.
     */
    @Test
    void bookingWithOutstandingCaptureReservation_isReportedNotDeclined() {
        UUID bookingId = seedStrandedBooking(null, 24 * 60);
        transactionTemplate.execute(status -> {
            jdbcTemplate.update(
                "INSERT INTO payment.booking_payments (booking_id, credit_debited, stripe_charged, status) "
                + "VALUES (?, 0, 50.00, 'CAPTURE_PENDING')", bookingId);
            return null;
        });

        sweep();

        assertThat(statusOf(bookingId))
            .as("money may already be at Stripe — this is an operator's reconciliation, not a decline")
            .isEqualTo("PAYMENT_PENDING");
        assertThat(jdbcTemplate.queryForObject(
            "SELECT status FROM payment.booking_payments WHERE booking_id = ?", String.class, bookingId))
            .as("the reservation must not be overwritten with CHARGE_FAILED")
            .isEqualTo("CAPTURE_PENDING");
        assertThat(paymentRowCount(bookingId)).isEqualTo(1);
    }

    /**
     * UAT.3 review patch. Once the sweeper has declined a stranded booking, a settlement arriving
     * afterwards must refuse to reserve rather than charge a booking that no longer exists as a
     * live session — the sweeper hands the coach's slot back, so a later charge would take money
     * for a session nobody holds.
     *
     * <p>Deterministic on purpose. An earlier version of this test raced the sweeper against a
     * concurrent reservation to prove {@code sweepOne}'s row lock; it was <strong>deleted because
     * it passed unchanged with the lock removed</strong> — both threads start together but the
     * sweeper first reads config and queries for stranded bookings, so the reservation always
     * committed first by a wide margin and the correct outcome came from the payment-row check,
     * not the lock. It cost 280s and proved nothing. The lock's own read-then-write window is
     * microseconds wide and is not reachable from a test at this level; see the deferred item.
     */
    @Test
    void afterTheSweepDeclinesABooking_aLateSettlementRefusesToReserve() {
        UUID bookingId = seedStrandedBooking(null, 24 * 60);

        sweep();
        assertThat(statusOf(bookingId)).isEqualTo("DECLINED");

        assertThat(persistenceService.reserveCapture(bookingId, BigDecimal.ZERO, new BigDecimal("50.00"), null))
            .as("a declined booking must never be reserved — the coach's slot has already been released")
            .isEqualTo(CaptureReservation.BOOKING_NOT_PENDING);

        assertThat(jdbcTemplate.queryForObject(
            "SELECT status FROM payment.booking_payments WHERE booking_id = ?", String.class, bookingId))
            .as("the refused reservation must not have touched the sweeper's CHARGE_FAILED record")
            .isEqualTo("CHARGE_FAILED");
        assertThat(paymentRowCount(bookingId)).isEqualTo(1);
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
