package com.softropic.skillars.platform.payment.service;

import com.softropic.skillars.infrastructure.exception.ResourceNotFoundException;
import com.softropic.skillars.platform.payment.BasePaymentIT;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.AccessDeniedException;

import java.time.Instant;
import java.util.UUID;

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
    void adminOverview_noData_returnsZeroSubscriptionRevenue() {
        // No seed data — should return all-zero aggregate values without exception
        assertThatCode(() -> {
            var dto = revenueReportingService.getAdminOverview(
                Instant.parse("2026-06-01T00:00:00Z"),
                Instant.parse("2026-06-30T23:59:59Z")
            );
            // outstandingDisputeCount is embedded in getAdminCoachRevenue, not overview
            // but subscriptionRevenue must always be ZERO (V64 seeds only Stripe priceId strings)
            org.assertj.core.api.Assertions.assertThat(dto.subscriptionRevenue())
                .isEqualByComparingTo(java.math.BigDecimal.ZERO);
        }).doesNotThrowAnyException();
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
