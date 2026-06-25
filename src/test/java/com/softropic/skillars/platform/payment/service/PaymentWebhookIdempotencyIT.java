package com.softropic.skillars.platform.payment.service;

import com.softropic.skillars.platform.booking.contract.BookingAcceptedEvent;
import com.softropic.skillars.platform.booking.service.BookingService;
import com.softropic.skillars.platform.payment.BasePaymentIT;
import com.softropic.skillars.platform.payment.repo.BookingPaymentRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for payment event idempotency.
 * AC 9: A duplicate BookingAcceptedEvent for the same bookingId is a no-op — exactly one
 * BookingPayment record exists and the credit ledger is not debited twice.
 */
class PaymentWebhookIdempotencyIT extends BasePaymentIT {

    @Autowired PaymentLifecycleService paymentLifecycleService;
    @Autowired BookingPaymentRepository bookingPaymentRepository;

    // Mock BookingService so transition() doesn't need a real booking row in DB
    @MockitoBean BookingService bookingService;

    private static final long PARENT_ID = 66001L;
    private static final UUID COACH_ID = UUID.randomUUID();

    @Test
    void duplicateBookingAcceptedEvent_secondEventIsNoOp() {
        UUID bookingId = UUID.randomUUID();
        BookingAcceptedEvent event = bookingAcceptedEvent(bookingId, new BigDecimal("50.00"), null);

        // First event: Case C (zero credit, full Stripe charge via StubPaymentGateway)
        paymentLifecycleService.onBookingAccepted(event);

        long ledgerCountAfterFirst = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM payment.parent_credit_ledger WHERE parent_id = ?",
            Long.class, PARENT_ID
        );
        assertThat(bookingPaymentRepository.existsById(bookingId)).isTrue();

        // Second event — must be a no-op
        paymentLifecycleService.onBookingAccepted(event);

        long ledgerCountAfterSecond = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM payment.parent_credit_ledger WHERE parent_id = ?",
            Long.class, PARENT_ID
        );
        long paymentCount = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM payment.booking_payments WHERE booking_id = ?",
            Long.class, bookingId
        );

        assertThat(paymentCount)
            .as("Exactly one BookingPayment must exist after duplicate event")
            .isEqualTo(1L);
        assertThat(ledgerCountAfterSecond)
            .as("Ledger must not be written twice for the same booking")
            .isEqualTo(ledgerCountAfterFirst);
    }

    @Test
    void duplicateBookingAcceptedEvent_withCredit_creditNotDoubleDebited() {
        UUID bookingId = UUID.randomUUID();

        // Pre-load credit for the parent (Case A: full credit covers booking)
        transactionTemplate.execute(status -> {
            // Insert a credit ledger entry to give parent a balance
            // Use BOOKING_DEDUCTION_REVERSAL which requires amount > 0
            jdbcTemplate.update(
                "INSERT INTO payment.parent_credit_ledger (parent_id, amount, type, description) " +
                "VALUES (?, 100.00, 'CASH_OUT_REVERSAL', 'Test credit seed')",
                PARENT_ID
            );
            return null;
        });

        BookingAcceptedEvent event = bookingAcceptedEvent(bookingId, new BigDecimal("50.00"), null);

        paymentLifecycleService.onBookingAccepted(event);
        paymentLifecycleService.onBookingAccepted(event); // duplicate

        // Only one BOOKING_DEDUCTION should exist for this booking
        long deductionCount = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM payment.parent_credit_ledger " +
            "WHERE parent_id = ? AND type = 'BOOKING_DEDUCTION'",
            Long.class, PARENT_ID
        );
        assertThat(deductionCount)
            .as("Credit must not be deducted twice for the same booking")
            .isEqualTo(1L);
    }

    @Test
    void packBasedBooking_idempotent_sessionNotDeductedTwice() {
        UUID coachId = insertTestCoach(66010L, "idem_coach@test.com", "Idempotency Coach");
        UUID tierId = UUID.randomUUID();
        UUID purchaseId = UUID.randomUUID();
        UUID bookingId = UUID.randomUUID();

        transactionTemplate.execute(status -> {
            jdbcTemplate.update(
                "INSERT INTO payment.session_pack_tiers " +
                "(pack_tier_id, coach_id, label, session_count, total_price, price_per_session, is_active, version, created_at) " +
                "VALUES (?, ?, '5-Pack', 5, 150.00, 30.00, true, 0, now())",
                tierId, coachId
            );
            jdbcTemplate.update(
                "INSERT INTO payment.session_pack_purchases " +
                "(purchase_id, parent_id, coach_id, pack_tier_id, price_per_session, remaining_sessions, " +
                "expires_at, version, created_at) " +
                "VALUES (?, ?, ?, ?, 30.00, 5, now() + interval '60 days', 0, now())",
                purchaseId, PARENT_ID, coachId, tierId
            );
            return null;
        });

        BookingAcceptedEvent event = bookingAcceptedEvent(bookingId, new BigDecimal("30.00"), purchaseId);

        paymentLifecycleService.onBookingAccepted(event);
        paymentLifecycleService.onBookingAccepted(event); // duplicate

        long paymentCount = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM payment.booking_payments WHERE booking_id = ?",
            Long.class, bookingId
        );
        assertThat(paymentCount).isEqualTo(1L);

        // Sessions should have been deducted only once
        Integer remaining = jdbcTemplate.queryForObject(
            "SELECT remaining_sessions FROM payment.session_pack_purchases WHERE purchase_id = ?",
            Integer.class, purchaseId
        );
        assertThat(remaining)
            .as("Session must be deducted only once for duplicate event")
            .isEqualTo(4);
    }

    private BookingAcceptedEvent bookingAcceptedEvent(UUID bookingId, BigDecimal price, UUID packPurchaseId) {
        return new BookingAcceptedEvent(this, bookingId, PARENT_ID, COACH_ID,
            price, packPurchaseId, "parent@test.com", "Test Coach",
            Instant.now().plusSeconds(3600), "UTC");
    }
}
