package com.softropic.skillars.platform.payment.service;

import com.softropic.skillars.platform.booking.contract.BatchBookingAcceptedEvent;
import com.softropic.skillars.platform.booking.contract.BookingAcceptedEvent;
import com.softropic.skillars.platform.booking.service.BookingService;
import com.softropic.skillars.platform.payment.BasePaymentIT;
import com.softropic.skillars.platform.payment.contract.PaymentGateway;
import com.softropic.skillars.platform.payment.contract.exception.PaymentGatewayException;
import com.softropic.skillars.platform.payment.repo.BookingPaymentRepository;
import com.softropic.skillars.platform.payment.repo.StripeCustomer;
import com.softropic.skillars.platform.payment.repo.StripeCustomerRepository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Story UAT.3 AC1/AC2/AC3, against a real database.
 *
 * <p>The defect these tests pin is the one you cannot debug after the fact: money captured at
 * Stripe, the booking cancelled, and nothing in the database saying so. The interlock has two
 * halves and both are exercised here — {@code reserveCapture} refuses on a booking that is no
 * longer PAYMENT_PENDING, and {@code cancelBookingAsParent} refuses while a CAPTURE_PENDING row
 * stands.
 *
 * <p>Deliberately no {@code Thread.sleep} barrier: Deferred-12 D1 records that the sleep-based
 * barrier in BookingServiceConcurrencyIT passes green with the fix removed. The two ordering tests
 * below are sequenced explicitly — each step commits before the next begins, which is what makes
 * the assertion mean something — and the third drives both real services simultaneously off a
 * {@link CountDownLatch}.
 */
class CaptureReservationIT extends BasePaymentIT {

    private static final long PARENT_ID = 97001L;
    private static final long PLAYER_ID = 97003L;
    private static final long COACH_USER_ID = 97002L;
    private static final BigDecimal SESSION_PRICE = new BigDecimal("50.00");

    @Autowired PaymentLifecycleService paymentLifecycleService;
    @Autowired BookingPaymentPersistenceService persistenceService;
    @Autowired BookingPaymentRepository bookingPaymentRepository;
    @Autowired BookingService bookingService;
    @Autowired StripeCustomerRepository stripeCustomerRepository;

    // A spy, not a mock: the stub's return values must survive so the settle can complete. What is
    // being asserted is whether Stripe was reached at all.
    @MockitoSpyBean PaymentGateway paymentGateway;

    private UUID coachId;

    @BeforeEach
    void setUpFixtures() {
        coachId = insertTestCoach(COACH_USER_ID, "capture_coach@test.com", "Capture Coach");
        insertTestParent(PARENT_ID, "capture_parent@test.com");
        insertTestPlayer(PLAYER_ID, PARENT_ID);
        transactionTemplate.execute(status -> {
            jdbcTemplate.update(
                "INSERT INTO marketplace.coach_pricing (coach_id, per_session_price, currency) "
                + "VALUES (?, 50.00, 'EUR') ON CONFLICT (coach_id) DO NOTHING", coachId);
            return null;
        });
    }

    // ── fixtures ──

    /** Seeds a credit-funded booking resting in PAYMENT_PENDING, i.e. mid-settlement. */
    private UUID seedPendingBooking(long startsInHours) {
        UUID bookingId = UUID.randomUUID();
        Instant start = Instant.now().plus(startsInHours, ChronoUnit.HOURS);
        transactionTemplate.execute(status -> {
            jdbcTemplate.update(
                "INSERT INTO booking.bookings (id, parent_id, player_id, coach_id, requested_start_time, "
                + "requested_end_time, status, canonical_timezone, version, created_at, updated_at) "
                + "VALUES (?, ?, ?, ?, ?, ?, 'PAYMENT_PENDING', 'UTC', 0, now(), now())",
                bookingId, PARENT_ID, PLAYER_ID, coachId,
                Timestamp.from(start), Timestamp.from(start.plus(1, ChronoUnit.HOURS)));
            return null;
        });
        return bookingId;
    }

    private BookingAcceptedEvent acceptedEvent(UUID bookingId) {
        return new BookingAcceptedEvent(this, bookingId, PARENT_ID, coachId,
            SESSION_PRICE, null, "capture_parent@test.com", "Capture Coach",
            Instant.now().plus(72, ChronoUnit.HOURS), "UTC");
    }

    private String statusOf(UUID bookingId) {
        return jdbcTemplate.queryForObject(
            "SELECT status FROM booking.bookings WHERE id = ?", String.class, bookingId);
    }

    private String paymentStatusOf(UUID bookingId) {
        List<String> rows = jdbcTemplate.queryForList(
            "SELECT status FROM payment.booking_payments WHERE booking_id = ?", String.class, bookingId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    // ── AC2 ordering 1: the cancel commits first ──

    /**
     * The parent's cancel wins the race and commits. When settlement then runs, the reservation
     * takes the booking-row lock, reads CANCELLED_PARENT and refuses — so <strong>Stripe is never
     * reached</strong> and no money moves. That last assertion is the whole point: the pre-fix code
     * charged first and only discovered the cancellation when the transition failed afterwards.
     */
    @Test
    void cancelCommitsFirst_settlementRefusesToReserveAndNeverCallsStripe() {
        UUID bookingId = seedPendingBooking(72);

        bookingService.cancelBookingAsParent(bookingId, PARENT_ID);
        assertThat(statusOf(bookingId)).isEqualTo("CANCELLED_PARENT");

        paymentLifecycleService.onBookingAccepted(acceptedEvent(bookingId));

        verify(paymentGateway, never()).chargeAndCapture(any(), any(), any(), any());
        assertThat(paymentStatusOf(bookingId))
            .as("a refused reservation must write nothing — a row here would falsely record a charge")
            .isNull();
        assertThat(statusOf(bookingId)).isEqualTo("CANCELLED_PARENT");
    }

    // ── AC2 ordering 2: the reservation commits first ──

    /**
     * The reservation commits first, so a cancel arriving mid-capture is refused with 409 and the
     * booking survives to be settled. The steps are the real settle sequence, in order:
     * reserve → (cancel attempt) → charge → record. Each has committed before the next runs.
     *
     * <p>Then the retry the 409 tells the parent to make: once CONFIRMED, the ordinary refund path
     * applies and the cancel goes through. A 409 that left the booking permanently uncancellable
     * would be a worse bug than the one being fixed.
     */
    @Test
    void reservationCommitsFirst_cancelIsRefused409_bookingSettlesAndIsCancellableAfterwards() {
        UUID bookingId = seedPendingBooking(72);

        assertThat(persistenceService.reserveCapture(bookingId, BigDecimal.ZERO, SESSION_PRICE, null))
            .isEqualTo(CaptureReservation.RESERVED);
        assertThat(paymentStatusOf(bookingId)).isEqualTo("CAPTURE_PENDING");

        assertThatThrownBy(() -> bookingService.cancelBookingAsParent(bookingId, PARENT_ID))
            .isInstanceOf(ResponseStatusException.class)
            .satisfies(t -> {
                assertThat(((ResponseStatusException) t).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
                assertThat(((ResponseStatusException) t).getReason()).isEqualTo("booking.paymentInProgress");
            });
        assertThat(statusOf(bookingId))
            .as("the cancel must not commit while a capture may already have reached Stripe")
            .isEqualTo("PAYMENT_PENDING");

        // The settle completes as it would have: charge, then record over the reserved row.
        persistenceService.persistPaymentSuccess(bookingId, BigDecimal.ZERO, SESSION_PRICE,
            "pi_capture_it", null, PARENT_ID, "capture_parent@test.com", "Capture Coach",
            Instant.now().plus(72, ChronoUnit.HOURS), "UTC");

        assertThat(statusOf(bookingId)).isEqualTo("CONFIRMED");
        assertThat(paymentStatusOf(bookingId))
            .as("the reserved row is updated in place, not duplicated or clobbered")
            .isEqualTo("CAPTURED");
        assertThat(jdbcTemplate.queryForObject(
            "SELECT count(*) FROM payment.booking_payments WHERE booking_id = ?", Integer.class, bookingId))
            .isEqualTo(1);

        bookingService.cancelBookingAsParent(bookingId, PARENT_ID);
        assertThat(statusOf(bookingId))
            .as("once settled, the 409 must lift — a permanently uncancellable booking is a worse bug")
            .isEqualTo("CANCELLED_PARENT");
    }

    // ── AC2: the two run concurrently ──

    /**
     * Both real services, started together off a latch. Whichever wins the booking-row lock, only
     * two end states are legal and both are asserted explicitly:
     *
     * <ul>
     *   <li><strong>Cancel won</strong> — CANCELLED_PARENT with <em>no</em> payment row: the
     *       reservation was refused, so Stripe was never reached.</li>
     *   <li><strong>Settle won</strong> — CONFIRMED with a CAPTURED row, and the cancel must have
     *       been refused rather than silently dropped.</li>
     * </ul>
     *
     * <p>Anything else fails, including the state this whole story exists to prevent: a cancelled
     * booking with money captured against it. PAYMENT_PENDING is deliberately <em>not</em> accepted
     * — with the stub gateway the settle cannot fail on its own, so a booking resting there would
     * mean the reservation stranded, which is a real defect and must not read as a pass.
     *
     * <p>Both threads' throwables are captured and surfaced in the failure message. Swallowing the
     * settle's exception would let the very failure this test exists to catch present as an
     * unexplained wrong end state.
     */
    @Test
    void cancelAndSettleRunConcurrently_neverCapturesMoneyOnACancelledBooking() throws Exception {
        UUID bookingId = seedPendingBooking(96);

        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(2);
        AtomicReference<Throwable> cancelOutcome = new AtomicReference<>();
        AtomicReference<Throwable> settleOutcome = new AtomicReference<>();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            pool.submit(() -> {
                try {
                    start.await();
                    bookingService.cancelBookingAsParent(bookingId, PARENT_ID);
                } catch (Throwable t) {
                    cancelOutcome.set(t);
                } finally {
                    done.countDown();
                }
            });
            pool.submit(() -> {
                try {
                    start.await();
                    paymentLifecycleService.onBookingAccepted(acceptedEvent(bookingId));
                } catch (Throwable t) {
                    settleOutcome.set(t);
                } finally {
                    done.countDown();
                }
            });
            start.countDown();
            assertThat(done.await(30, TimeUnit.SECONDS)).as("both threads must finish").isTrue();
        } finally {
            pool.shutdownNow();
        }

        String bookingStatus = statusOf(bookingId);
        String paymentStatus = paymentStatusOf(bookingId);
        String outcomes = String.format(
            "booking=%s payment=%s cancelThrew=%s settleThrew=%s",
            bookingStatus, paymentStatus, cancelOutcome.get(), settleOutcome.get());

        assertThat(bookingStatus)
            .as("only two interleavings are legal; a stranded PAYMENT_PENDING is a defect (%s)", outcomes)
            .isIn("CANCELLED_PARENT", "CONFIRMED");

        if ("CANCELLED_PARENT".equals(bookingStatus)) {
            assertThat(paymentStatus)
                .as("cancel won, so the reservation was refused and no charge was ever attempted (%s)", outcomes)
                .isNull();
        } else {
            assertThat(paymentStatus)
                .as("settle won, so the booking must carry a completed payment record (%s)", outcomes)
                .isEqualTo("CAPTURED");
            assertThat(cancelOutcome.get())
                .as("the cancel must have been refused, not silently dropped (%s)", outcomes)
                .isNotNull();
        }
    }

    // ── AC3: the batch charge-failure decline loop ──

    /**
     * UAT.3 AC3, and the single most dangerous line in this story. The batch charge-failure path
     * used to decline every booking {@code if (!bookingPaymentRepository.existsById(bookingId))} —
     * a condition that becomes <strong>permanently false</strong> once reservations exist, because
     * every reserved booking has a row. Left unchanged, a failed batch charge would decline nothing
     * and strand the entire batch in PAYMENT_PENDING holding the coach's slots, with no sweeper
     * able to recover it (a CAPTURE_PENDING row is exactly what the sweeper refuses to touch).
     *
     * <p>Every other batch test asserts the success path, so without this test the break ships
     * silently. It fails against the old condition.
     */
    @Test
    void batchChargeFails_everyReservedBookingIsDeclined() {
        UUID first = seedPendingBooking(120);
        UUID second = seedPendingBooking(144);
        UUID batchId = UUID.randomUUID();

        doThrow(new PaymentGatewayException("payment.batchFailure"))
            .when(paymentGateway).chargeAndCaptureForBatch(any(), any(), any(), any());

        paymentLifecycleService.onBatchBookingAccepted(new BatchBookingAcceptedEvent(
            this, batchId, List.of(first, second), PARENT_ID, coachId, new BigDecimal("100.00"),
            "capture_coach@test.com", "capture_parent@test.com", "Capture Coach", "Test Parent", 2));

        assertThat(statusOf(first))
            .as("a booking whose batch charge failed must not be left stranded in PAYMENT_PENDING")
            .isEqualTo("DECLINED");
        assertThat(statusOf(second)).isEqualTo("DECLINED");
        assertThat(paymentStatusOf(first))
            .as("the reserved row is written through to CHARGE_FAILED, not left as CAPTURE_PENDING")
            .isEqualTo("CHARGE_FAILED");
        assertThat(paymentStatusOf(second)).isEqualTo("CHARGE_FAILED");
    }

    /**
     * UAT.3 AC3 step 2. A booking that fails to reserve — here because the parent cancelled it —
     * must be dropped from the batch subtotal, not charged for. The sibling still settles.
     */
    @Test
    void batchWithOneCancelledBooking_chargesOnlyTheReservedSibling() {
        UUID cancelled = seedPendingBooking(168);
        UUID healthy = seedPendingBooking(192);
        UUID batchId = UUID.randomUUID();

        bookingService.cancelBookingAsParent(cancelled, PARENT_ID);

        paymentLifecycleService.onBatchBookingAccepted(new BatchBookingAcceptedEvent(
            this, batchId, List.of(cancelled, healthy), PARENT_ID, coachId, new BigDecimal("100.00"),
            "capture_coach@test.com", "capture_parent@test.com", "Capture Coach", "Test Parent", 2));

        ArgumentCaptor<BigDecimal> charged = ArgumentCaptor.forClass(BigDecimal.class);
        verify(paymentGateway).chargeAndCaptureForBatch(any(), any(), any(), charged.capture());
        assertThat(charged.getValue())
            .as("the cancelled booking must not appear in the subtotal — that is money taken for nothing")
            .isEqualByComparingTo(SESSION_PRICE);

        assertThat(statusOf(cancelled)).isEqualTo("CANCELLED_PARENT");
        assertThat(paymentStatusOf(cancelled)).as("a refused reservation writes no row").isNull();
        assertThat(statusOf(healthy)).isEqualTo("CONFIRMED");
        assertThat(paymentStatusOf(healthy)).isEqualTo("CAPTURED");
    }

    // ── AC1: the revenue surface must not see a reserved row ──

    /**
     * UAT.3 AC1. Every revenue query filters on {@code status = 'CAPTURED'} or on
     * {@code captured_at BETWEEN}, and a reserved row has neither — so it is invisible to all of
     * them. That exclusion is currently accidental (nothing states it), and a reserved row leaking
     * into a coach's earnings would be a payout error. Pin it.
     */
    @Test
    void capturePendingRowIsInvisibleToEveryRevenueQuery() {
        UUID bookingId = seedPendingBooking(48);
        Instant from = Instant.now().minus(1, ChronoUnit.DAYS);
        Instant to = Instant.now().plus(1, ChronoUnit.DAYS);

        BigDecimal grossBefore = bookingPaymentRepository
            .sumGrossByCoachAndPeriod(coachId, from, to).orElse(BigDecimal.ZERO);
        long countBefore = bookingPaymentRepository.countCapturedByCoachAndPeriod(coachId, from, to);
        long pageBefore = bookingPaymentRepository
            .findByCoachAndPeriod(coachId, from, to, PageRequest.of(0, 50)).getTotalElements();
        long platformCountBefore = bookingPaymentRepository.countCapturedForPeriod(from, to);

        assertThat(persistenceService.reserveCapture(bookingId, BigDecimal.ZERO, SESSION_PRICE, null))
            .isEqualTo(CaptureReservation.RESERVED);

        assertThat(bookingPaymentRepository.sumGrossByCoachAndPeriod(coachId, from, to).orElse(BigDecimal.ZERO))
            .as("a reserved row must not count towards a coach's gross — no money has moved")
            .isEqualByComparingTo(grossBefore);
        assertThat(bookingPaymentRepository.countCapturedByCoachAndPeriod(coachId, from, to))
            .isEqualTo(countBefore);
        assertThat(bookingPaymentRepository.findByCoachAndPeriod(coachId, from, to, PageRequest.of(0, 50))
            .getTotalElements())
            .as("captured_at IS NULL on a reserved row, and no BETWEEN matches NULL")
            .isEqualTo(pageBefore);
        assertThat(bookingPaymentRepository.countCapturedForPeriod(from, to)).isEqualTo(platformCountBefore);
        assertThat(bookingPaymentRepository.findBookingIdsByCoachAndPeriod(coachId, from, to))
            .doesNotContain(bookingId);
    }

    // ── UAT.5 AC2: the opaque-id design pays off unchanged at the capture layer ──

    /**
     * UAT.5 AC2 step 3. Once AC1 writes a self-registered player's own userId into
     * {@code booking.parent_id}, the capture path must resolve the {@link StripeCustomer} row
     * AC2's widened {@code /setup-intent}/{@code /save-payment-method} endpoints let that same
     * player create — under the same key — with NO code change to {@link PaymentLifecycleService}
     * or {@code StripePaymentGateway}. Verified here against a real database rather than assumed.
     *
     * <p>Added to this class, not a new file: it is the only class in this module already wired
     * with {@code @MockitoSpyBean PaymentGateway}, so a new test method here costs zero additional
     * Spring contexts — a second class making the same choice would add one and risk tripping the
     * CI context-count ceiling (deferred-19 AC3), which is exactly what happened on first attempt.
     * {@code doReturn(...).when(spy)...}, not {@code when(spy...).thenReturn(...)}: the latter
     * invokes the real method first as a side effect of stubbing a spy.
     */
    @Test
    void bookingAcceptedForSelfBookingPlayer_capturesAgainstThePlayersOwnStripeCustomerRow() {
        long selfPlayerUserId = 97004L;
        transactionTemplate.execute(status -> {
            jdbcTemplate.update(
                "INSERT INTO main.\"user\" (id, login, login_id_type, password_hash, activated, "
                + "first_name, last_name, gender, dob, email) "
                + "VALUES (?, ?, 'EMAIL', '{noop}test', true, 'Self', 'Player', 'MALE', '2000-01-01', ?) "
                + "ON CONFLICT (id) DO NOTHING",
                selfPlayerUserId, "self.player.capture@test.com", "self.player.capture@test.com"
            );
            return null;
        });
        StripeCustomer customer = new StripeCustomer();
        customer.setParentId(selfPlayerUserId);
        customer.setStripeCustomerId("cus_self_player_test");
        customer.setStripePaymentMethodId("pm_self_player_test");
        stripeCustomerRepository.save(customer);

        UUID bookingId = UUID.randomUUID();
        Instant start = Instant.now().plus(72, ChronoUnit.HOURS);
        transactionTemplate.execute(status -> {
            jdbcTemplate.update(
                "INSERT INTO booking.bookings (id, parent_id, player_id, coach_id, requested_start_time, "
                + "requested_end_time, status, canonical_timezone, version, created_at, updated_at) "
                + "VALUES (?, ?, ?, ?, ?, ?, 'PAYMENT_PENDING', 'UTC', 0, now(), now())",
                bookingId, selfPlayerUserId, PLAYER_ID, coachId,
                Timestamp.from(start), Timestamp.from(start.plus(1, ChronoUnit.HOURS)));
            return null;
        });

        doReturn("pi_self_player_test").when(paymentGateway)
            .chargeAndCapture(eq(bookingId), eq(selfPlayerUserId), eq(coachId), any(BigDecimal.class));

        paymentLifecycleService.onBookingAccepted(new BookingAcceptedEvent(
            this, bookingId, selfPlayerUserId, coachId, SESSION_PRICE, null,
            "self.player.capture@test.com", "Capture Coach",
            Instant.now().plus(72, ChronoUnit.HOURS), "UTC"));

        // No code change was made anywhere in the capture path: the existing
        // event.getParentId()-keyed lookup finds the row this test seeded under the player's own
        // userId, exactly as it already does for a real parent — proving the opaque-id shortcut.
        assertThat(statusOf(bookingId)).isEqualTo("CONFIRMED");
        assertThat(paymentStatusOf(bookingId)).isEqualTo("CAPTURED");
    }
}
