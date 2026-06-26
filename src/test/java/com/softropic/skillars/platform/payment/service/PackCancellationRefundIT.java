package com.softropic.skillars.platform.payment.service;

import com.softropic.skillars.platform.booking.contract.BookingCancelledByCoachEvent;
import com.softropic.skillars.platform.booking.contract.BookingCancelledByParentEvent;
import com.softropic.skillars.platform.payment.BasePaymentIT;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class PackCancellationRefundIT extends BasePaymentIT {

    @Autowired CancellationRefundService cancellationRefundService;

    private static final long PARENT_ID    = 80001L;
    private static final long COACH_USER_ID = 80002L;
    private static final BigDecimal PRICE_PER_SESSION = new BigDecimal("30.00");

    // Populated in setUp() — coachId is the coach_profiles PK, which satisfies the FK chain
    private UUID coachId;
    private UUID packId;

    @BeforeEach
    void setUp() {
        // Inserts user + coach_profile and returns the coach_profiles UUID (not user id)
        coachId = insertTestCoach(COACH_USER_ID, "pack.coach@test.com", "Pack Coach");

        transactionTemplate.execute(status -> {
            jdbcTemplate.update(
                "INSERT INTO main.\"user\" (id, login, login_id_type, password_hash, activated, " +
                "first_name, last_name, gender, dob, email) VALUES (?, ?, 'EMAIL', '{noop}test', true, " +
                "'Pack', 'Parent', 'FEMALE', '1985-05-05', ?) ON CONFLICT (id) DO NOTHING",
                PARENT_ID, "pack.parent@test.com", "pack.parent@test.com"
            );

            // pack_tier_id FK requires a real session_pack_tiers row
            UUID tierId = jdbcTemplate.queryForObject(
                "INSERT INTO payment.session_pack_tiers " +
                "(pack_tier_id, coach_id, label, session_count, total_price, price_per_session) " +
                "VALUES (gen_random_uuid(), ?, 'Test Pack', 5, 150.00, ?) " +
                "RETURNING pack_tier_id",
                UUID.class, coachId, PRICE_PER_SESSION
            );

            packId = jdbcTemplate.queryForObject(
                "INSERT INTO payment.session_pack_purchases " +
                "(purchase_id, parent_id, coach_id, pack_tier_id, remaining_sessions, price_per_session, expires_at) " +
                "VALUES (gen_random_uuid(), ?, ?, ?, 5, ?, ?) " +
                "RETURNING purchase_id",
                UUID.class,
                PARENT_ID, coachId, tierId, PRICE_PER_SESSION,
                Timestamp.from(Instant.now().plus(30, ChronoUnit.DAYS))
            );
            return null;
        });
    }

    @AfterEach
    void cleanPackData() {
        transactionTemplate.execute(status -> {
            jdbcTemplate.execute("DELETE FROM payment.coach_cancellation_history");
            jdbcTemplate.execute("DELETE FROM payment.parent_credit_ledger WHERE parent_id = " + PARENT_ID);
            jdbcTemplate.execute("DELETE FROM payment.session_pack_purchases WHERE parent_id = " + PARENT_ID);
            jdbcTemplate.update("DELETE FROM main.\"user\" WHERE id = ?", PARENT_ID);
            return null;
        });
    }

    @Test
    void coachCancels_packActive_sessionRestored_noLedgerEntry() {
        int remainingBefore = getRemainingSessions();
        BookingCancelledByCoachEvent event = coachEvent(packId, "MUTUAL_AGREEMENT", false);

        cancellationRefundService.onBookingCancelledByCoach(event);

        int remainingAfter = getRemainingSessions();
        assertThat(remainingAfter).isEqualTo(remainingBefore + 1);

        long ledgerCount = countLedgerEntries(event.getBookingId());
        assertThat(ledgerCount).isZero();
    }

    @Test
    void coachCancels_packExpired_creditWritten_sessionNotRestored() {
        UUID bookingId = UUID.randomUUID();
        BookingCancelledByCoachEvent event = coachEvent(packId, "MUTUAL_AGREEMENT", true);

        cancellationRefundService.onBookingCancelledByCoach(event);

        long ledgerCount = countLedgerEntries(event.getBookingId());
        assertThat(ledgerCount).isEqualTo(1);

        BigDecimal amount = jdbcTemplate.queryForObject(
            "SELECT amount FROM payment.parent_credit_ledger WHERE reference_id = ?",
            BigDecimal.class, event.getBookingId()
        );
        assertThat(amount).isEqualByComparingTo(PRICE_PER_SESSION);
    }

    @Test
    void parentCancels_gt24h_sessionRestored_noLedgerEntry() {
        int remainingBefore = getRemainingSessions();
        BookingCancelledByParentEvent event = parentEvent(packId, 25);

        cancellationRefundService.onBookingCancelledByParent(event);

        int remainingAfter = getRemainingSessions();
        assertThat(remainingAfter).isEqualTo(remainingBefore + 1);

        long ledgerCount = countLedgerEntries(event.getBookingId());
        assertThat(ledgerCount).isZero();
    }

    @Test
    void parentCancels_lte24h_sessionNotRestored_noLedgerEntry() {
        int remainingBefore = getRemainingSessions();
        BookingCancelledByParentEvent event = parentEvent(packId, 6);

        cancellationRefundService.onBookingCancelledByParent(event);

        int remainingAfter = getRemainingSessions();
        assertThat(remainingAfter).isEqualTo(remainingBefore);

        long ledgerCount = countLedgerEntries(event.getBookingId());
        assertThat(ledgerCount).isZero();
    }

    private int getRemainingSessions() {
        return jdbcTemplate.queryForObject(
            "SELECT remaining_sessions FROM payment.session_pack_purchases WHERE purchase_id = ?",
            Integer.class, packId
        );
    }

    private long countLedgerEntries(UUID bookingId) {
        return jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM payment.parent_credit_ledger WHERE reference_id = ?",
            Long.class, bookingId
        );
    }

    private BookingCancelledByCoachEvent coachEvent(UUID purchaseId, String reason, boolean packExpired) {
        return new BookingCancelledByCoachEvent(
            this, UUID.randomUUID(), PARENT_ID, coachId,
            reason, purchaseId, PRICE_PER_SESSION, packExpired,
            "parent@test.com", Instant.now(), "UTC"
        );
    }

    private BookingCancelledByParentEvent parentEvent(UUID purchaseId, long hours) {
        return new BookingCancelledByParentEvent(
            this, UUID.randomUUID(), PARENT_ID, coachId,
            purchaseId, hours, hours > 24,
            PRICE_PER_SESSION, "parent@test.com", "coach@test.com",
            Instant.now(), "UTC"
        );
    }
}
