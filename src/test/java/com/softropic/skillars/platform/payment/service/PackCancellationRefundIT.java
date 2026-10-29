package com.softropic.skillars.platform.payment.service;

import com.softropic.skillars.platform.booking.contract.BookingCancelledByCoachEvent;
import com.softropic.skillars.platform.booking.contract.BookingCancelledByParentEvent;
import com.softropic.skillars.platform.payment.BasePaymentIT;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class PackCancellationRefundIT extends BasePaymentIT {

    @Autowired ApplicationEventPublisher eventPublisher;

    private static final long PARENT_ID    = 80001L;
    private static final long COACH_USER_ID = 80002L;
    private static final long PLAYER_ID    = 80003L;
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
                "(purchase_id, parent_id, player_id, coach_id, pack_tier_id, remaining_sessions, price_per_session, expires_at) " +
                "VALUES (gen_random_uuid(), ?, ?, ?, ?, 5, ?, ?) " +
                "RETURNING purchase_id",
                UUID.class,
                PARENT_ID, PLAYER_ID, coachId, tierId, PRICE_PER_SESSION,
                Timestamp.from(Instant.now().plus(30, ChronoUnit.DAYS))
            );
            return null;
        });
    }

    @AfterEach
    void cleanPackData() {
        transactionTemplate.execute(status -> {
            jdbcTemplate.execute("DELETE FROM payment.coach_cancellation_history");
            jdbcTemplate.execute("DELETE FROM main.outbox_messages WHERE aggregate_type = 'PACK_SESSION_RESTORE'");
            // parent_credit_ledger is append-only (V79 triggers); bypass for test cleanup only
            jdbcTemplate.execute("SET SESSION session_replication_role = 'replica'");
            jdbcTemplate.execute("DELETE FROM payment.parent_credit_ledger WHERE parent_id = " + PARENT_ID);
            jdbcTemplate.execute("SET SESSION session_replication_role = 'origin'");
            jdbcTemplate.execute("DELETE FROM payment.session_pack_purchases WHERE parent_id = " + PARENT_ID);
            jdbcTemplate.update("DELETE FROM main.\"user\" WHERE id = ?", PARENT_ID);
            return null;
        });
    }

    @Test
    void coachCancels_packActive_sessionRestored_noLedgerEntry() {
        int remainingBefore = getRemainingSessions();
        BookingCancelledByCoachEvent event = coachEvent(packId, "MUTUAL_AGREEMENT", false);

        // fix/payment-session-pack-integrity: the restore is enqueued BEFORE_COMMIT, so it needs a real
        // transaction (the test profile drains the outbox synchronously after commit).
        transactionTemplate.execute(status -> {
            eventPublisher.publishEvent(event);
            return null;
        });

        int remainingAfter = getRemainingSessions();
        assertThat(remainingAfter).isEqualTo(remainingBefore + 1);

        long ledgerCount = countLedgerEntries(event.getBookingId());
        assertThat(ledgerCount).isZero();
    }

    @Test
    void coachCancels_packExpired_creditWritten_sessionNotRestored() {
        BookingCancelledByCoachEvent event = coachEvent(packId, "MUTUAL_AGREEMENT", true);

        transactionTemplate.execute(status -> {
            eventPublisher.publishEvent(event);
            return null;
        });

        long ledgerCount = countLedgerEntries(event.getBookingId());
        assertThat(ledgerCount).isEqualTo(1);

        BigDecimal amount = jdbcTemplate.queryForObject(
            "SELECT amount FROM payment.parent_credit_ledger WHERE reference_id = ?",
            BigDecimal.class, event.getBookingId()
        );
        assertThat(amount).isEqualByComparingTo(PRICE_PER_SESSION);
    }

    /**
     * fix/payment-pack-refund-rounding: 1000.00 for 3 sessions, one already paid to the coach (333.33). The two
     * remaining sessions are refunded on an expired pack: 333.33, then the last one takes the remainder 333.34,
     * so coach + refunds reconcile to exactly 1000.00 (it used to be 999.99). A re-delivered event for a
     * booking already refunded settles nothing again.
     */
    @Test
    void expiredPackRefunds_lastSessionTakesTheRoundingRemainder_reconcilingToTheCharge() {
        UUID stamped = transactionTemplate.execute(status -> {
            UUID tierId = jdbcTemplate.queryForObject(
                "SELECT pack_tier_id FROM payment.session_pack_purchases WHERE purchase_id = ?", UUID.class, packId);
            return jdbcTemplate.queryForObject(
                "INSERT INTO payment.session_pack_purchases " +
                "(purchase_id, parent_id, player_id, coach_id, pack_tier_id, remaining_sessions, price_per_session, " +
                " amount_charged, session_count, payout_sessions_allocated, payout_gross_allocated, expires_at) " +
                "VALUES (gen_random_uuid(), ?, ?, ?, ?, 0, 333.33, 1000.00, 3, 1, 333.33, ?) RETURNING purchase_id",
                UUID.class, PARENT_ID, PLAYER_ID, coachId, tierId,
                Timestamp.from(Instant.now().minus(1, ChronoUnit.DAYS)));
        });

        BookingCancelledByCoachEvent first = coachEvent(stamped, "MUTUAL_AGREEMENT", true, new BigDecimal("333.33"));
        BookingCancelledByCoachEvent last = coachEvent(stamped, "MUTUAL_AGREEMENT", true, new BigDecimal("333.33"));
        for (BookingCancelledByCoachEvent e : List.of(first, last)) {
            transactionTemplate.execute(status -> {
                eventPublisher.publishEvent(e);
                return null;
            });
        }

        assertThat(ledgerAmount(first.getBookingId())).isEqualByComparingTo("333.33");
        assertThat(ledgerAmount(last.getBookingId())).isEqualByComparingTo("333.34");
        Map<String, Object> ledger = jdbcTemplate.queryForMap(
            "SELECT refund_sessions_issued, refund_gross_issued, payout_gross_allocated, amount_charged " +
            "FROM payment.session_pack_purchases WHERE purchase_id = ?", stamped);
        assertThat(ledger.get("refund_sessions_issued")).isEqualTo(2);
        assertThat((BigDecimal) ledger.get("refund_gross_issued")).isEqualByComparingTo("666.67");
        assertThat(((BigDecimal) ledger.get("payout_gross_allocated")).add((BigDecimal) ledger.get("refund_gross_issued")))
            .isEqualByComparingTo((BigDecimal) ledger.get("amount_charged"));
        assertThat(jdbcTemplate.queryForObject(
            "SELECT COALESCE(SUM(amount), 0) FROM payment.pack_session_refunds WHERE purchase_id = ?",
            BigDecimal.class, stamped)).isEqualByComparingTo("666.67");

        // The pack is fully settled: a further expired-pack refund (should not happen) credits nothing.
        BookingCancelledByCoachEvent extra = coachEvent(stamped, "MUTUAL_AGREEMENT", true, new BigDecimal("333.33"));
        transactionTemplate.execute(status -> {
            eventPublisher.publishEvent(extra);
            return null;
        });
        assertThat(countLedgerEntries(extra.getBookingId())).isZero();
    }

    private BigDecimal ledgerAmount(UUID bookingId) {
        return jdbcTemplate.queryForObject(
            "SELECT amount FROM payment.parent_credit_ledger WHERE reference_id = ? AND type = 'BOOKING_REFUND'",
            BigDecimal.class, bookingId);
    }

    @Test
    void parentCancels_gt24h_sessionRestored_noLedgerEntry() {
        int remainingBefore = getRemainingSessions();
        BookingCancelledByParentEvent event = parentEvent(packId, 25);

        transactionTemplate.execute(status -> {
            eventPublisher.publishEvent(event);
            return null;
        });

        int remainingAfter = getRemainingSessions();
        assertThat(remainingAfter).isEqualTo(remainingBefore + 1);

        long ledgerCount = countLedgerEntries(event.getBookingId());
        assertThat(ledgerCount).isZero();
    }

    @Test
    void parentCancels_lte24h_sessionNotRestored_noLedgerEntry() {
        int remainingBefore = getRemainingSessions();
        BookingCancelledByParentEvent event = parentEvent(packId, 6);

        transactionTemplate.execute(status -> {
            eventPublisher.publishEvent(event);
            return null;
        });

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
        return coachEvent(purchaseId, reason, packExpired, PRICE_PER_SESSION);
    }

    private BookingCancelledByCoachEvent coachEvent(UUID purchaseId, String reason, boolean packExpired,
                                                    BigDecimal price) {
        return new BookingCancelledByCoachEvent(
            this, UUID.randomUUID(), PARENT_ID, coachId,
            reason, purchaseId, price, packExpired, true,
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
