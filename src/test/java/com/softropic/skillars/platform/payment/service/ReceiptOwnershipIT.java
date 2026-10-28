package com.softropic.skillars.platform.payment.service;

import com.softropic.skillars.infrastructure.exception.ResourceNotFoundException;
import com.softropic.skillars.platform.payment.BasePaymentIT;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.AccessDeniedException;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Integration tests for receipt ownership checks and admin overview in RevenueReportingService.
 * Uses a real PostgreSQL container (via BasePaymentIT) to verify access-control queries.
 */
class ReceiptOwnershipIT extends BasePaymentIT {

    @Autowired
    private RevenueReportingService revenueReportingService;

    // ── Coach receipt ownership ──────────────────────────────────

    @Test
    void coachReceipt_differentCoachId_throws403() {
        UUID coachA = insertTestCoach(1001L, "coach-a-receipt@test.com", "Coach A");
        UUID coachB = insertTestCoach(1002L, "coach-b-receipt@test.com", "Coach B");
        UUID bookingId = insertTestBooking(coachA, 2001L, 3001L);

        // Coach B tries to access a receipt owned by Coach A
        assertThatThrownBy(() -> revenueReportingService.getCoachReceipt(coachB, bookingId))
            .isInstanceOf(AccessDeniedException.class);
    }

    // ── Parent receipt ownership ─────────────────────────────────

    @Test
    void parentReceipt_differentParentId_throws403() {
        UUID coachA = insertTestCoach(1003L, "coach-c-receipt@test.com", "Coach C");
        UUID bookingId = insertTestBooking(coachA, 2002L, 3002L);

        // Parent 9999 tries to access a receipt owned by parent 2002
        assertThatThrownBy(() -> revenueReportingService.getParentReceipt(9999L, bookingId))
            .isInstanceOf(AccessDeniedException.class);
    }

    // ── Admin endpoints ──────────────────────────────────────────

    @Test
    void adminCoachRevenue_nonExistentCoach_throws404() {
        UUID ghostCoach = UUID.randomUUID();

        assertThatThrownBy(() -> revenueReportingService.getAdminCoachRevenue(ghostCoach, null, null))
            .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void adminOverview_noData_returnsZeroAggregatesWithoutException() {
        assertThatCode(() -> {
            var dto = revenueReportingService.getAdminOverview(
                Instant.parse("2026-06-01T00:00:00Z"),
                Instant.parse("2026-06-30T23:59:59Z")
            );
            assertThat(dto.totalGrossVolume()).isEqualByComparingTo(BigDecimal.ZERO);
            assertThat(dto.totalCommissionCollected()).isEqualByComparingTo(BigDecimal.ZERO);
        }).doesNotThrowAnyException();
    }

    /**
     * fix/payment-money-integrity: the overview's gross volume includes pack purchases, commission
     * uses each non-refunded booking payment's STAMPED rate on its card-charged portion (not gross ×
     * the live rate), and cash-outs whose refund failed (reversed) are not counted.
     */
    @Test
    void adminOverview_realFigures_fromStampedRatesPacksAndNetCashOuts() {
        Instant from = Instant.parse("2031-03-01T00:00:00Z");
        Instant to = Instant.parse("2031-03-31T23:59:59Z");
        Instant inPeriod = Instant.parse("2031-03-10T12:00:00Z");
        UUID coach = insertTestCoach(1010L, "coach-finance-overview@test.com", "Coach Finance");

        transactionTemplate.execute(status -> {
            // Card 100 @ stamped 0.15 -> 15.00 ; card 50 + credit 50 @ stamped 0.20 -> 10.00 ;
            // credit-only 40 -> 0 commission ; legacy NULL stamp 10 @ live rate.
            insertCapturedPayment("pi_fin_1", new BigDecimal("100.00"), BigDecimal.ZERO, new BigDecimal("0.1500"), inPeriod);
            insertCapturedPayment("pi_fin_2", new BigDecimal("50.00"), new BigDecimal("50.00"), new BigDecimal("0.2000"), inPeriod);
            insertCapturedPayment(null, BigDecimal.ZERO, new BigDecimal("40.00"), new BigDecimal("0.1500"), inPeriod);
            insertCapturedPayment("pi_fin_4", new BigDecimal("10.00"), BigDecimal.ZERO, null, inPeriod);
            // Refunded (stays CAPTURED): counts in gross (pre-refund value) but earns no commission.
            UUID refunded = insertCapturedPayment("pi_fin_6", new BigDecimal("200.00"), BigDecimal.ZERO,
                new BigDecimal("0.1500"), inPeriod);
            ledger(2010L, "200.00", "BOOKING_REFUND", refunded, inPeriod);
            // Out of period: ignored.
            insertCapturedPayment("pi_fin_5", new BigDecimal("999.00"), BigDecimal.ZERO, new BigDecimal("0.1500"),
                Instant.parse("2031-04-02T00:00:00Z"));

            UUID tier = UUID.randomUUID();
            jdbcTemplate.update(
                "INSERT INTO payment.session_pack_tiers (pack_tier_id, coach_id, label, session_count, total_price, price_per_session) "
                    + "VALUES (?, ?, 'Pack', 5, 250.00, 50.00)", tier, coach);
            // Charged 200 at purchase; the tier price has since been edited to 250 — the stamp wins.
            jdbcTemplate.update(
                "INSERT INTO payment.session_pack_purchases (purchase_id, parent_id, coach_id, pack_tier_id, player_id, "
                    + "price_per_session, remaining_sessions, stripe_payment_intent_id, amount_charged, created_at) "
                    + "VALUES (?, 2010, ?, ?, 3010, 40.00, 5, 'pi_fin_pack', 200.00, ?)",
                UUID.randomUUID(), coach, tier, java.sql.Timestamp.from(inPeriod));

            UUID okCashOut = UUID.randomUUID();
            UUID failedCashOut = UUID.randomUUID();
            ledger(2010L, "-30.00", "CASH_OUT_DEBIT", okCashOut, inPeriod);
            ledger(2010L, "-1.00", "STRIPE_FEE_DEBIT", okCashOut, inPeriod);
            ledger(2010L, "-20.00", "CASH_OUT_DEBIT", failedCashOut, inPeriod);
            ledger(2010L, "20.00", "CASH_OUT_REVERSAL", failedCashOut, inPeriod);
            return null;
        });

        BigDecimal live = new BigDecimal(jdbcTemplate.queryForObject(
            "SELECT value FROM main.platform_config WHERE key = 'platform.commission.rate'", String.class));
        var dto = revenueReportingService.getAdminOverview(from, to);

        // Bookings 100 + (50+50) + 40 + 10 + 200 (refunded) = 450, plus the 200 pack.
        assertThat(dto.totalGrossVolume()).isEqualByComparingTo("650.00");
        BigDecimal expectedCommission = new BigDecimal("25.00")
            .add(new BigDecimal("10.00").multiply(live).setScale(2, java.math.RoundingMode.HALF_UP));
        assertThat(dto.totalCommissionCollected()).isEqualByComparingTo(expectedCommission);
        assertThat(dto.totalCashOuts()).isEqualByComparingTo("30.00");
    }

    private UUID insertCapturedPayment(String pi, BigDecimal card, BigDecimal credit, BigDecimal rate, Instant capturedAt) {
        UUID bookingId = UUID.randomUUID();
        jdbcTemplate.update(
            "INSERT INTO payment.booking_payments (booking_id, stripe_payment_intent_id, credit_debited, stripe_charged, "
                + "status, captured_at, commission_rate) VALUES (?, ?, ?, ?, 'CAPTURED', ?, ?)",
            bookingId, pi, credit, card, java.sql.Timestamp.from(capturedAt), rate);
        return bookingId;
    }

    private void ledger(long parentId, String amount, String type, UUID ref, Instant createdAt) {
        jdbcTemplate.update(
            "INSERT INTO payment.parent_credit_ledger (parent_id, amount, type, reference_id, description, created_at) "
                + "VALUES (?, ?, ?, ?, 'IT', ?)",
            parentId, new BigDecimal(amount), type, ref, java.sql.Timestamp.from(createdAt));
    }

    // ── Helpers ──────────────────────────────────────────────────

    private UUID insertTestBooking(UUID coachId, long parentId, long playerId) {
        UUID bookingId = UUID.randomUUID();
        transactionTemplate.execute(status -> {
            jdbcTemplate.update(
                "INSERT INTO booking.bookings " +
                "(id, parent_id, player_id, coach_id, requested_start_time, requested_end_time, " +
                " status, canonical_timezone, version, created_at, updated_at) " +
                "VALUES (?, ?, ?, ?, now(), now() + interval '1 hour', 'CONFIRMED', 'UTC', 0, now(), now())",
                bookingId, parentId, playerId, coachId
            );
            return null;
        });
        return bookingId;
    }
}
