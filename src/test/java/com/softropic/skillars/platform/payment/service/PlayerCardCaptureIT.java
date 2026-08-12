package com.softropic.skillars.platform.payment.service;

import com.softropic.skillars.platform.booking.contract.BookingAcceptedEvent;
import com.softropic.skillars.platform.payment.BasePaymentIT;
import com.softropic.skillars.platform.payment.contract.PaymentGateway;
import com.softropic.skillars.platform.payment.repo.BookingPaymentRepository;
import com.softropic.skillars.platform.payment.repo.StripeCustomer;
import com.softropic.skillars.platform.payment.repo.StripeCustomerRepository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.math.BigDecimal;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * UAT.5 AC2 step 3: once AC1 writes a self-registered player's own userId into
 * {@code booking.parent_id}, the capture path must resolve the {@link StripeCustomer} row AC2's
 * widened {@code /setup-intent}/{@code /save-payment-method} endpoints let that same player create
 * — under the same key — with no code change to {@link PaymentLifecycleService} or
 * {@link com.softropic.skillars.platform.payment.service.StripePaymentGateway}. This is the payoff
 * of the opaque-id design; verified here against a real database rather than assumed.
 */
class PlayerCardCaptureIT extends BasePaymentIT {

    private static final long SELF_PLAYER_USER_ID = 97101L;
    private static final long SELF_PLAYER_PROFILE_ID = 97103L;
    private static final long COACH_USER_ID = 97102L;
    private static final BigDecimal SESSION_PRICE = new BigDecimal("50.00");

    @Autowired PaymentLifecycleService paymentLifecycleService;
    @Autowired BookingPaymentRepository bookingPaymentRepository;
    @Autowired StripeCustomerRepository stripeCustomerRepository;

    // Mocked, not spied: this test proves the LOOKUP KEY is correct (parentId == the player's
    // own userId), not the real Stripe wire protocol — that is CaptureReservationIT's job.
    @MockitoBean PaymentGateway paymentGateway;

    private UUID coachId;

    @BeforeEach
    void setUpFixtures() {
        coachId = insertTestCoach(COACH_USER_ID, "player_capture_coach@test.com", "Capture Coach");

        // Self-registered player: a bare user row, no parent, exactly what AC1 writes into
        // booking.parent_id and what AC2's endpoints key a StripeCustomer row against.
        transactionTemplate.execute(status -> {
            jdbcTemplate.update(
                "INSERT INTO main.\"user\" (id, login, login_id_type, password_hash, activated, " +
                "first_name, last_name, gender, dob, email) " +
                "VALUES (?, ?, 'EMAIL', '{noop}test', true, 'Self', 'Player', 'MALE', '2000-01-01', ?) " +
                "ON CONFLICT (id) DO NOTHING",
                SELF_PLAYER_USER_ID, "self.player.capture@skillars-test.com", "self.player.capture@skillars-test.com"
            );
            jdbcTemplate.update(
                "INSERT INTO main.player_profiles " +
                "(id, name, date_of_birth, position, age_tier, user_id, independent_account_allowed, created_at, created_by) " +
                "VALUES (?, 'Self Player', ?, 'MIDFIELDER', 'ADULT', ?, true, ?, 'system') " +
                "ON CONFLICT (id) DO NOTHING",
                SELF_PLAYER_PROFILE_ID, Date.valueOf(LocalDate.now().minusYears(20)),
                SELF_PLAYER_USER_ID, Timestamp.from(Instant.now())
            );
            jdbcTemplate.update(
                "INSERT INTO marketplace.coach_pricing (coach_id, per_session_price, currency) " +
                "VALUES (?, 50.00, 'EUR') ON CONFLICT (coach_id) DO NOTHING", coachId);
            return null;
        });

        // AC2: the player has already saved a card via /setup-intent + /save-payment-method,
        // keyed by their own userId — exactly what those endpoints, once widened, persist.
        StripeCustomer customer = new StripeCustomer();
        customer.setParentId(SELF_PLAYER_USER_ID);
        customer.setStripeCustomerId("cus_self_player_test");
        customer.setStripePaymentMethodId("pm_self_player_test");
        stripeCustomerRepository.save(customer);
    }

    @Test
    void bookingAcceptedForSelfBookingPlayer_capturesAgainstThePlayersOwnStripeCustomerRow() {
        UUID bookingId = seedPendingBooking();

        when(paymentGateway.chargeAndCapture(eq(bookingId), eq(SELF_PLAYER_USER_ID), eq(coachId), any(BigDecimal.class)))
            .thenReturn("pi_self_player_test");

        paymentLifecycleService.onBookingAccepted(new BookingAcceptedEvent(
            this, bookingId, SELF_PLAYER_USER_ID, coachId, SESSION_PRICE, null,
            "self.player.capture@skillars-test.com", "Capture Coach",
            Instant.now().plus(72, ChronoUnit.HOURS), "UTC"));

        // No code change was made anywhere in the capture path: the existing
        // event.getParentId()-keyed lookup finds the row this test seeded under the player's own
        // userId, exactly as it already does for a real parent — proving the opaque-id shortcut.
        assertThat(jdbcTemplate.queryForObject(
            "SELECT status FROM booking.bookings WHERE id = ?", String.class, bookingId))
            .isEqualTo("CONFIRMED");
        assertThat(jdbcTemplate.queryForObject(
            "SELECT status FROM payment.booking_payments WHERE booking_id = ?", String.class, bookingId))
            .isEqualTo("CAPTURED");
    }

    private UUID seedPendingBooking() {
        UUID bookingId = UUID.randomUUID();
        Instant start = Instant.now().plus(72, ChronoUnit.HOURS);
        transactionTemplate.execute(status -> {
            jdbcTemplate.update(
                "INSERT INTO booking.bookings (id, parent_id, player_id, coach_id, requested_start_time, "
                + "requested_end_time, status, canonical_timezone, version, created_at, updated_at) "
                + "VALUES (?, ?, ?, ?, ?, ?, 'PAYMENT_PENDING', 'UTC', 0, now(), now())",
                bookingId, SELF_PLAYER_USER_ID, SELF_PLAYER_PROFILE_ID, coachId,
                Timestamp.from(start), Timestamp.from(start.plus(1, ChronoUnit.HOURS)));
            return null;
        });
        return bookingId;
    }
}
