package com.softropic.skillars.platform.booking.service;

import com.softropic.skillars.config.TestConfig;
import com.softropic.skillars.platform.booking.repo.BookingRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Deferred-12 AC6 / Task 5 — end-to-end coverage of the batch accept → payment settle path.
 *
 * <p>Before this story the batch flow issued only {@code ACCEPT}, leaving its bookings in
 * {@code ACCEPTED} while {@link com.softropic.skillars.platform.payment.service.PaymentLifecycleService#onBatchBookingAccepted}
 * tried to move them with {@code PAYMENT_CAPTURED}/{@code PAYMENT_FAILED} — transitions the state
 * machine does not permit from {@code ACCEPTED}. The gap went unnoticed because
 * {@code BatchPaymentIT} seeds rows directly at {@code PAYMENT_PENDING} and
 * {@code BookingBatchServiceTest} mocks {@code BookingService}; nothing drove
 * {@code acceptAll} through to a settled booking. These tests do, for all three settle branches.
 */
@SpringBootTest
@Testcontainers
@ActiveProfiles({"dev", "test"})
@Import(TestConfig.class)
class BatchAcceptPaymentIT {

    @Autowired private BookingBatchService bookingBatchService;
    @Autowired private BookingRepository bookingRepository;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private TransactionTemplate transactionTemplate;

    private static final long PARENT_ID     = 9611000001L;
    private static final long PLAYER_ID     = 9611000011L;
    private static final long COACH_USER_ID = 9611000021L;
    private static final String TZ = "Europe/Berlin";

    private UUID coachProfileId;
    private UUID packTierId;

    @BeforeEach
    void setUp() {
        coachProfileId = UUID.randomUUID();
        packTierId = UUID.randomUUID();

        transactionTemplate.execute(status -> {
            insertUser(PARENT_ID, "parent.batchaccept@skillars-test.com", "PARENT");
            insertUser(COACH_USER_ID, "coach.batchaccept@skillars-test.com", "COACH");

            jdbcTemplate.update(
                "INSERT INTO main.player_profiles " +
                "(id, name, date_of_birth, position, age_tier, parent_id, independent_account_allowed, created_at, created_by) " +
                "VALUES (?, 'Batch Accept Player', ?, 'MIDFIELDER', 'ADULT', ?, true, ?, 'system')",
                PLAYER_ID, Date.valueOf(LocalDate.now().minusYears(16)), PARENT_ID,
                Timestamp.from(Instant.now())
            );
            jdbcTemplate.update(
                "INSERT INTO marketplace.coach_profiles " +
                "(id, user_id, display_name, bio, city, languages, canonical_timezone, status) " +
                "VALUES (?, ?, 'Batch Accept Coach', 'Bio', 'Berlin', ARRAY['English']::varchar[], ?, 'ACTIVE')",
                coachProfileId, COACH_USER_ID, TZ
            );
            jdbcTemplate.update(
                "INSERT INTO marketplace.coach_pricing (coach_id, per_session_price, currency) VALUES (?, 40.00, 'EUR')",
                coachProfileId
            );
            jdbcTemplate.update(
                "INSERT INTO payment.session_pack_tiers " +
                "(pack_tier_id, coach_id, label, session_count, total_price, price_per_session, is_active, version, created_at) " +
                "VALUES (?, ?, '5-Pack', 5, 200.00, 40.00, true, 0, now())",
                packTierId, coachProfileId
            );
            return null;
        });
    }

    @AfterEach
    void tearDown() {
        transactionTemplate.execute(status -> {
            jdbcTemplate.update("DELETE FROM payment.booking_payments WHERE booking_id IN " +
                "(SELECT id FROM booking.bookings WHERE coach_id = ?)", coachProfileId);
            jdbcTemplate.update("DELETE FROM booking.bookings WHERE coach_id = ?", coachProfileId);
            jdbcTemplate.update("DELETE FROM booking.booking_batches WHERE coach_id = ?", coachProfileId);
            jdbcTemplate.execute("SET SESSION session_replication_role = 'replica'");
            jdbcTemplate.update("DELETE FROM payment.parent_credit_ledger WHERE parent_id = ?", PARENT_ID);
            jdbcTemplate.execute("SET SESSION session_replication_role = 'origin'");
            jdbcTemplate.update("DELETE FROM payment.session_pack_purchases WHERE coach_id = ?", coachProfileId);
            jdbcTemplate.update("DELETE FROM payment.session_pack_tiers WHERE coach_id = ?", coachProfileId);
            jdbcTemplate.update("DELETE FROM marketplace.coach_pricing WHERE coach_id = ?", coachProfileId);
            jdbcTemplate.update("DELETE FROM marketplace.coach_profiles WHERE id = ?", coachProfileId);
            jdbcTemplate.update("DELETE FROM main.player_profiles WHERE id = ?", PLAYER_ID);
            jdbcTemplate.execute("DELETE FROM main.refresh_tokens");
            jdbcTemplate.execute("DELETE FROM main.login_attempts");
            jdbcTemplate.update("DELETE FROM main.\"user\" WHERE id IN (?, ?)", PARENT_ID, COACH_USER_ID);
            jdbcTemplate.execute("DELETE FROM main.sec");
            return null;
        });
    }

    @Test
    void acceptAll_creditBasedBatch_bookingsReachConfirmed() {
        UUID batchId = insertBatch();
        UUID bookingId1 = insertBatchBooking(batchId, 0, null);
        UUID bookingId2 = insertBatchBooking(batchId, 1, null);

        bookingBatchService.acceptAll(batchId, COACH_USER_ID);

        assertThat(statusOf(bookingId1))
            .as("credit/Stripe batch booking must settle to CONFIRMED, not throw on an illegal transition")
            .isEqualTo("CONFIRMED");
        assertThat(statusOf(bookingId2)).isEqualTo("CONFIRMED");
    }

    @Test
    void acceptAll_packBasedBatch_bookingReachesConfirmedAndDeductsPackUnit() {
        UUID packId = insertPackPurchase(5);
        UUID batchId = insertBatch();
        UUID bookingId = insertBatchBooking(batchId, 2, packId);

        bookingBatchService.acceptAll(batchId, COACH_USER_ID);

        assertThat(statusOf(bookingId))
            .as("pack-based batch booking must settle to CONFIRMED")
            .isEqualTo("CONFIRMED");
        Integer remaining = jdbcTemplate.queryForObject(
            "SELECT remaining_sessions FROM payment.session_pack_purchases WHERE purchase_id = ?",
            Integer.class, packId);
        assertThat(remaining).isEqualTo(4);
    }

    @Test
    void acceptAll_packDeductionFails_bookingReachesDeclined() {
        UUID exhaustedPackId = insertPackPurchase(0);
        UUID batchId = insertBatch();
        UUID bookingId = insertBatchBooking(batchId, 3, exhaustedPackId);

        bookingBatchService.acceptAll(batchId, COACH_USER_ID);

        assertThat(statusOf(bookingId))
            .as("a batch booking whose payment fails must settle to DECLINED, not throw")
            .isEqualTo("DECLINED");
    }

    @Test
    void acceptAll_batchStatusStillDerivesFullyAccepted() {
        UUID batchId = insertBatch();
        insertBatchBooking(batchId, 4, null);
        insertBatchBooking(batchId, 5, null);

        bookingBatchService.acceptAll(batchId, COACH_USER_ID);

        // POST_ACCEPTANCE_STATUSES contains both ACCEPTED and PAYMENT_PENDING, so routing the batch
        // flow through PAYMENT_PENDING must not change what updateBatchStatusFromBooking derives.
        String batchStatus = jdbcTemplate.queryForObject(
            "SELECT status FROM booking.booking_batches WHERE id = ?", String.class, batchId);
        assertThat(batchStatus).isEqualTo("FULLY_ACCEPTED");
    }

    /**
     * The assertion above is satisfied by acceptAll's own inline
     * {@code acceptedIds.size() == requestedBookings.size()} branch, so on its own it never
     * exercises POST_ACCEPTANCE_STATUSES — the invariant AC6 actually asked to pin. This drives
     * updateBatchStatusFromBooking directly over bookings resting in PAYMENT_PENDING.
     */
    @Test
    void updateBatchStatusFromBooking_derivesFullyAcceptedForPaymentPendingBookings() {
        UUID batchId = insertBatch();
        UUID b1 = insertBatchBooking(batchId, 6, null);
        UUID b2 = insertBatchBooking(batchId, 7, null);
        setStatus(b1, "PAYMENT_PENDING");
        setStatus(b2, "PAYMENT_PENDING");

        bookingBatchService.updateBatchStatusFromBooking(batchId);

        assertThat(jdbcTemplate.queryForObject(
            "SELECT status FROM booking.booking_batches WHERE id = ?", String.class, batchId))
            .as("PAYMENT_PENDING must count as post-acceptance, or batch status regresses for batch bookings")
            .isEqualTo("FULLY_ACCEPTED");
    }

    /**
     * Deferred-12 code review: a mixed batch settling one healthy and one failing booking must not
     * lose the healthy one. Both settles previously shared onBatchBookingAccepted's transaction, so
     * the failure marked it rollback-only and discarded the sibling's CONFIRMED write — leaving it
     * stranded in PAYMENT_PENDING behind a swallowed UnexpectedRollbackException.
     */
    @Test
    void acceptAll_mixedBatch_healthyBookingSettlesDespiteSiblingFailure() {
        UUID goodPack = insertPackPurchase(5);
        UUID deadPack = insertPackPurchase(0);
        UUID batchId = insertBatch();
        UUID goodBooking = insertBatchBooking(batchId, 8, goodPack);
        UUID badBooking = insertBatchBooking(batchId, 9, deadPack);

        bookingBatchService.acceptAll(batchId, COACH_USER_ID);

        assertThat(statusOf(goodBooking))
            .as("a sibling's payment failure must not roll back an already-settled booking")
            .isEqualTo("CONFIRMED");
        assertThat(statusOf(badBooking)).isEqualTo("DECLINED");
        assertThat(jdbcTemplate.queryForObject(
            "SELECT remaining_sessions FROM payment.session_pack_purchases WHERE purchase_id = ?",
            Integer.class, goodPack))
            .as("the healthy booking's pack unit must stay deducted")
            .isEqualTo(4);
    }

    private void setStatus(UUID bookingId, String status) {
        transactionTemplate.execute(s -> {
            jdbcTemplate.update("UPDATE booking.bookings SET status = ? WHERE id = ?", status, bookingId);
            return null;
        });
    }

    // ---- helpers ----

    private String statusOf(UUID bookingId) {
        return bookingRepository.findById(bookingId).orElseThrow().getStatus();
    }

    private UUID insertBatch() {
        UUID batchId = UUID.randomUUID();
        transactionTemplate.execute(status -> {
            jdbcTemplate.update(
                "INSERT INTO booking.booking_batches " +
                "(id, parent_id, coach_id, requested_count, total_amount, status, created_at) " +
                "VALUES (?, ?, ?, 0, 0.00, 'PENDING', ?)",
                batchId, PARENT_ID, coachProfileId, Timestamp.from(Instant.now())
            );
            return null;
        });
        return batchId;
    }

    /** slotIndex keeps each seeded slot non-overlapping — V87 adds a DB exclusion constraint. */
    private UUID insertBatchBooking(UUID batchId, int slotIndex, UUID packPurchaseId) {
        UUID bookingId = UUID.randomUUID();
        Instant start = Instant.now().plus(3, ChronoUnit.DAYS).plus(slotIndex * 3L, ChronoUnit.HOURS);
        Instant end = start.plus(1, ChronoUnit.HOURS);
        transactionTemplate.execute(status -> {
            jdbcTemplate.update(
                "INSERT INTO booking.bookings " +
                "(id, parent_id, player_id, coach_id, requested_start_time, requested_end_time, " +
                " status, canonical_timezone, batch_id, session_pack_purchase_id, version, created_at, updated_at) " +
                "VALUES (?, ?, ?, ?, ?, ?, 'REQUESTED', ?, ?, ?, 0, ?, ?)",
                bookingId, PARENT_ID, PLAYER_ID, coachProfileId,
                Timestamp.from(start), Timestamp.from(end), TZ, batchId, packPurchaseId,
                Timestamp.from(Instant.now()), Timestamp.from(Instant.now())
            );
            return null;
        });
        return bookingId;
    }

    private UUID insertPackPurchase(int remainingSessions) {
        UUID packId = UUID.randomUUID();
        transactionTemplate.execute(status -> {
            jdbcTemplate.update(
                "INSERT INTO payment.session_pack_purchases " +
                "(purchase_id, parent_id, player_id, coach_id, pack_tier_id, price_per_session, " +
                " remaining_sessions, expires_at, version, created_at) " +
                "VALUES (?, ?, ?, ?, ?, 40.00, ?, now() + interval '60 days', 0, now())",
                packId, PARENT_ID, PLAYER_ID, coachProfileId, packTierId, remainingSessions
            );
            return null;
        });
        return packId;
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
            "76" + (id % 100000000),
            email,
            role
        );
    }
}
