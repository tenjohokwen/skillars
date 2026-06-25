package com.softropic.skillars.platform.payment.service;

import com.softropic.skillars.platform.booking.contract.BatchBookingAcceptedEvent;
import com.softropic.skillars.platform.booking.service.BookingService;
import com.softropic.skillars.platform.payment.BasePaymentIT;
import com.softropic.skillars.platform.payment.repo.BookingPaymentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for batch booking payment handling.
 * AC 10: Mixed pack+credit batch results in a single Stripe charge for the credit-based deficit.
 */
class BatchPaymentIT extends BasePaymentIT {

    @Autowired PaymentLifecycleService paymentLifecycleService;
    @Autowired BookingPaymentRepository bookingPaymentRepository;

    // Mock BookingService so transitionToConfirmed() doesn't need real booking rows
    @MockitoBean BookingService bookingService;

    private UUID coachId;
    private static final long PARENT_ID = 55001L;
    private static final long PLAYER_ID = 55099L;

    @BeforeEach
    void setUpCoach() {
        coachId = insertTestCoach(55002L, "batch_coach@test.com", "Batch Coach");
        transactionTemplate.execute(status -> {
            jdbcTemplate.update(
                "INSERT INTO marketplace.coach_pricing (coach_id, per_session_price, currency) " +
                "VALUES (?, 40.00, 'EUR') ON CONFLICT DO NOTHING",
                coachId
            );
            return null;
        });
    }

    @Test
    void batchBooking_allCreditBased_bookingPaymentsCreatedForAllBookings() {
        UUID bookingId1 = UUID.randomUUID();
        UUID bookingId2 = UUID.randomUUID();

        insertBooking(bookingId1, coachId, null);
        insertBooking(bookingId2, coachId, null);

        BatchBookingAcceptedEvent event = batchEvent(List.of(bookingId1, bookingId2));

        paymentLifecycleService.onBatchBookingAccepted(event);

        assertThat(bookingPaymentRepository.existsById(bookingId1)).isTrue();
        assertThat(bookingPaymentRepository.existsById(bookingId2)).isTrue();
    }

    @Test
    void batchBooking_mixedPackAndCredit_packBookingHasZeroStripeCharge() {
        UUID tierId = UUID.randomUUID();
        UUID packId = UUID.randomUUID();
        UUID packBookingId = UUID.randomUUID();
        UUID creditBookingId = UUID.randomUUID();

        transactionTemplate.execute(status -> {
            jdbcTemplate.update(
                "INSERT INTO payment.session_pack_tiers " +
                "(pack_tier_id, coach_id, label, session_count, total_price, price_per_session, is_active, version, created_at) " +
                "VALUES (?, ?, '5-Pack', 5, 200.00, 40.00, true, 0, now())",
                tierId, coachId
            );
            jdbcTemplate.update(
                "INSERT INTO payment.session_pack_purchases " +
                "(purchase_id, parent_id, coach_id, pack_tier_id, price_per_session, remaining_sessions, " +
                "expires_at, version, created_at) " +
                "VALUES (?, ?, ?, ?, 40.00, 5, now() + interval '60 days', 0, now())",
                packId, PARENT_ID, coachId, tierId
            );
            return null;
        });

        insertBooking(packBookingId, coachId, packId);
        insertBooking(creditBookingId, coachId, null);

        BatchBookingAcceptedEvent event = batchEvent(List.of(packBookingId, creditBookingId));

        paymentLifecycleService.onBatchBookingAccepted(event);

        assertThat(bookingPaymentRepository.existsById(packBookingId)).isTrue();
        assertThat(bookingPaymentRepository.existsById(creditBookingId)).isTrue();

        // Pack-based booking: zero Stripe charge (session deducted from pack)
        BigDecimal packStripeCharged = jdbcTemplate.queryForObject(
            "SELECT stripe_charged FROM payment.booking_payments WHERE booking_id = ?",
            BigDecimal.class, packBookingId
        );
        assertThat(packStripeCharged)
            .as("Pack-based booking must not have a Stripe charge")
            .isEqualByComparingTo("0.00");

        // P9: verify the pack session was actually deducted
        Integer remainingSessions = jdbcTemplate.queryForObject(
            "SELECT remaining_sessions FROM payment.session_pack_purchases WHERE purchase_id = ?",
            Integer.class, packId
        );
        assertThat(remainingSessions)
            .as("Pack session must be deducted from remaining_sessions after batch booking")
            .isEqualTo(4);
    }

    @Test
    void batchBooking_withCreditBalance_creditAppliedBeforeStripeCharge() {
        // Pre-load some credit for the parent
        transactionTemplate.execute(status -> {
            jdbcTemplate.update(
                "INSERT INTO payment.parent_credit_ledger (parent_id, amount, type, description) " +
                "VALUES (?, 100.00, 'CASH_OUT_REVERSAL', 'Test credit')",
                PARENT_ID
            );
            return null;
        });

        UUID bookingId1 = UUID.randomUUID();
        UUID bookingId2 = UUID.randomUUID();
        insertBooking(bookingId1, coachId, null);
        insertBooking(bookingId2, coachId, null);

        BatchBookingAcceptedEvent event = batchEvent(List.of(bookingId1, bookingId2));

        paymentLifecycleService.onBatchBookingAccepted(event);

        // P9: one BOOKING_DEDUCTION is written for the entire batch (not one per booking)
        long deductionCount = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM payment.parent_credit_ledger " +
            "WHERE parent_id = ? AND type = 'BOOKING_DEDUCTION'",
            Long.class, PARENT_ID
        );
        assertThat(deductionCount)
            .as("One BOOKING_DEDUCTION row for the whole batch credit deduction")
            .isEqualTo(1L);

        // Total deducted must equal 2 × €40 = €80 (fully covered by the €100 balance)
        BigDecimal totalDeducted = jdbcTemplate.queryForObject(
            "SELECT ABS(SUM(amount)) FROM payment.parent_credit_ledger " +
            "WHERE parent_id = ? AND type = 'BOOKING_DEDUCTION'",
            BigDecimal.class, PARENT_ID
        );
        assertThat(totalDeducted)
            .as("Batch credit deduction total must equal 2 × €40 = €80")
            .isEqualByComparingTo("80.00");
    }

    private void insertBooking(UUID bookingId, UUID forCoachId, UUID packPurchaseId) {
        transactionTemplate.execute(status -> {
            if (packPurchaseId != null) {
                jdbcTemplate.update(
                    "INSERT INTO booking.bookings " +
                    "(id, parent_id, player_id, coach_id, status, requested_start_time, requested_end_time, " +
                    "canonical_timezone, session_pack_purchase_id, version, created_at, updated_at) " +
                    "VALUES (?, ?, ?, ?, 'PAYMENT_PENDING', " +
                    "now() + interval '2 days', now() + interval '2 days' + interval '1 hour', " +
                    "'UTC', ?, 0, now(), now())",
                    bookingId, PARENT_ID, PLAYER_ID, forCoachId, packPurchaseId
                );
            } else {
                jdbcTemplate.update(
                    "INSERT INTO booking.bookings " +
                    "(id, parent_id, player_id, coach_id, status, requested_start_time, requested_end_time, " +
                    "canonical_timezone, version, created_at, updated_at) " +
                    "VALUES (?, ?, ?, ?, 'PAYMENT_PENDING', " +
                    "now() + interval '2 days', now() + interval '2 days' + interval '1 hour', " +
                    "'UTC', 0, now(), now())",
                    bookingId, PARENT_ID, PLAYER_ID, forCoachId
                );
            }
            return null;
        });
    }

    private BatchBookingAcceptedEvent batchEvent(List<UUID> bookingIds) {
        return new BatchBookingAcceptedEvent(
            this, UUID.randomUUID(), bookingIds,
            PARENT_ID, coachId, new BigDecimal("80.00"),
            "coach@test.com", "parent@test.com",
            "Batch Coach", "Test Parent", bookingIds.size()
        );
    }
}
