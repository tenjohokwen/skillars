package com.softropic.skillars.platform.booking.service;

import com.softropic.skillars.config.AbstractIntegrationTest;
import com.softropic.skillars.infrastructure.exception.ResourceNotFoundException;
import com.softropic.skillars.platform.booking.contract.BookingResponse;
import com.softropic.skillars.platform.booking.contract.CreateBookingRequest;
import com.softropic.skillars.platform.booking.repo.Booking;
import com.softropic.skillars.platform.booking.repo.BookingRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * fix/payment-agreed-price-lock: the price a parent requested at is the price they are charged at
 * accept, whatever the coach does to their pricing in between. Uses the shared test context (no
 * mocks) and the context's StubPaymentGateway; the settle listener runs AFTER_COMMIT of acceptBooking.
 */
class AgreedPriceLockIT extends AbstractIntegrationTest {

    @Autowired private BookingService bookingService;
    @Autowired private BookingRepository bookingRepository;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private TransactionTemplate transactionTemplate;

    private static final long PARENT_ID = 9512000001L;
    private static final long PLAYER_ID = 9512000011L;
    private static final long COACH_USER_ID = 9512000021L;
    private static final String TZ = "Europe/Berlin";

    private UUID coachProfileId;
    private Instant slotStart;
    private Instant slotEnd;

    @BeforeEach
    void setUp() {
        // One base instant for start and end (chk_bkg_end_after_start).
        ZonedDateTime nextDaySlot = ZonedDateTime.now(ZoneId.of(TZ)).plusDays(1)
            .withHour(10).withMinute(0).withSecond(0).withNano(0);
        slotStart = nextDaySlot.toInstant();
        slotEnd = nextDaySlot.plusHours(1).toInstant();
        short windowDow = (short) nextDaySlot.getDayOfWeek().getValue();

        transactionTemplate.execute(status -> {
            insertUser(PARENT_ID, "parent.agreedprice@skillars-test.com", "PARENT");
            insertUser(COACH_USER_ID, "coach.agreedprice@skillars-test.com", "COACH");
            jdbcTemplate.update(
                "INSERT INTO main.player_profiles " +
                "(id, name, date_of_birth, position, age_tier, parent_id, independent_account_allowed, created_at, created_by) " +
                "VALUES (?, 'Agreed Price Player', ?, 'MIDFIELDER', 'ADULT', ?, true, ?, 'system')",
                PLAYER_ID, Date.valueOf(LocalDate.now().minusYears(16)), PARENT_ID, Timestamp.from(Instant.now()));
            coachProfileId = UUID.randomUUID();
            jdbcTemplate.update(
                "INSERT INTO marketplace.coach_profiles " +
                "(id, user_id, display_name, bio, city, languages, canonical_timezone, status) " +
                "VALUES (?, ?, 'Agreed Price Coach', 'Bio', 'Berlin', ARRAY['English']::varchar[], ?, 'ACTIVE')",
                coachProfileId, COACH_USER_ID, TZ);
            jdbcTemplate.update(
                "INSERT INTO marketplace.coach_pricing (coach_id, per_session_price, currency) VALUES (?, 50.00, 'EUR')",
                coachProfileId);
            jdbcTemplate.update(
                "INSERT INTO marketplace.coach_availability_windows " +
                "(id, coach_id, day_of_week, start_time, end_time, canonical_timezone) " +
                "VALUES (?, ?, ?, '08:00', '18:00', ?)",
                UUID.randomUUID(), coachProfileId, windowDow, TZ);
            return null;
        });
    }

    @Test
    void priceRaisedAfterRequest_parentIsChargedTheAgreedPrice() {
        BookingResponse created = bookingService.createBookingRequest(PARENT_ID,
            new CreateBookingRequest(coachProfileId, PLAYER_ID, slotStart, slotEnd, null, null, null));
        UUID bookingId = created.id();

        assertThat(bookingRepository.findById(bookingId).orElseThrow().getAgreedPrice())
            .isEqualByComparingTo("50.00");

        setCoachPrice("80.00");

        bookingService.acceptBooking(bookingId, COACH_USER_ID);

        Map<String, Object> bp = jdbcTemplate.queryForMap(
            "SELECT status, stripe_charged, credit_debited FROM payment.booking_payments WHERE booking_id = ?",
            bookingId);
        assertThat(bp.get("status")).isEqualTo("CAPTURED");
        assertThat((BigDecimal) bp.get("stripe_charged"))
            .as("charged the price agreed at request time, not the raised one")
            .isEqualByComparingTo("50.00");
        assertThat((BigDecimal) bp.get("credit_debited")).isEqualByComparingTo("0");
        assertThat(bookingRepository.findById(bookingId).orElseThrow().getStatus()).isEqualTo("CONFIRMED");
    }

    @Test
    void legacyBookingWithoutAgreedPrice_fallsBackToCurrentPricing() {
        Booking legacy = seedRequestedBooking(null);
        setCoachPrice("65.00");

        bookingService.acceptBooking(legacy.getId(), COACH_USER_ID);

        BigDecimal charged = jdbcTemplate.queryForObject(
            "SELECT stripe_charged FROM payment.booking_payments WHERE booking_id = ?", BigDecimal.class,
            legacy.getId());
        assertThat(charged).isEqualByComparingTo("65.00");
    }

    @Test
    void noAgreedPriceAndNoPricing_acceptFailsLoudly_neverConfirmedForFree() {
        Booking legacy = seedRequestedBooking(null);
        transactionTemplate.execute(status ->
            jdbcTemplate.update("DELETE FROM marketplace.coach_pricing WHERE coach_id = ?", coachProfileId));

        assertThatThrownBy(() -> bookingService.acceptBooking(legacy.getId(), COACH_USER_ID))
            .isInstanceOf(ResourceNotFoundException.class);

        assertThat(bookingRepository.findById(legacy.getId()).orElseThrow().getStatus()).isEqualTo("REQUESTED");
        Integer rows = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM payment.booking_payments WHERE booking_id = ?", Integer.class, legacy.getId());
        assertThat(rows).isZero();
    }

    private void setCoachPrice(String price) {
        transactionTemplate.execute(status -> jdbcTemplate.update(
            "UPDATE marketplace.coach_pricing SET per_session_price = ?::numeric WHERE coach_id = ?",
            price, coachProfileId));
    }

    private Booking seedRequestedBooking(BigDecimal agreedPrice) {
        return transactionTemplate.execute(status -> {
            Booking booking = new Booking();
            booking.setParentId(PARENT_ID);
            booking.setPlayerId(PLAYER_ID);
            booking.setCoachId(coachProfileId);
            booking.setRequestedStartTime(slotStart);
            booking.setRequestedEndTime(slotEnd);
            booking.setCanonicalTimezone(TZ);
            booking.setStatus("REQUESTED");
            booking.setAgreedPrice(agreedPrice);
            return bookingRepository.save(booking);
        });
    }

    private void insertUser(long id, String email, String role) {
        jdbcTemplate.update(
            "INSERT INTO main.\"user\" " +
            "(id, created_by, created_date, last_modified_by, last_modified_date, request_id, session_id, " +
            "status, dob, email, first_name, gender, lang_key, last_name, iso2_country, phone, " +
            "activated, locked, login, login_id_type, password_hash, otp_enabled, " +
            "skillars_role, verification_status) " +
            "VALUES (?, 'system', ?, 'system', ?, 'test-req', NULL, " +
            "'ACTIVE', '1985-06-01', ?, 'Test', 'OTHER', 'en', 'User', 'DE', ?, " +
            "true, false, ?, 'EMAIL', 'hash', false, " +
            "?, 'BASIC_VERIFIED')",
            id,
            Timestamp.from(Instant.now()), Timestamp.from(Instant.now()),
            email,
            "69" + (id % 100000000),
            email,
            role);
    }
}
