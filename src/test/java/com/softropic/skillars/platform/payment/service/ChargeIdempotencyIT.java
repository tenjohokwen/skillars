package com.softropic.skillars.platform.payment.service;

import com.softropic.skillars.platform.booking.contract.BatchBookingAcceptedEvent;
import com.softropic.skillars.platform.booking.contract.BookingAcceptedEvent;
import com.softropic.skillars.platform.payment.BasePaymentIT;
import com.softropic.skillars.platform.payment.contract.ChargeLookup;
import com.softropic.skillars.platform.payment.contract.exception.ChargeOutcomeUnknownException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;

/**
 * fix/payment-charge-idempotency (H1/H2/M2), against a real database: a booking charge whose Stripe outcome is
 * unknown is neither declined nor confirmed — its reservation keeps the per-attempt idempotency key — and
 * {@link ChargeOutcomeReconciler} later settles it if Stripe has the PaymentIntent, or declines it if not.
 *
 * <p>Uses the shared {@code StubPaymentGateway} spy from AbstractIntegrationTest; no new mock beans.
 */
class ChargeIdempotencyIT extends BasePaymentIT {

    private static final long PARENT_ID = 98101L;
    private static final long PLAYER_ID = 98103L;
    private static final long COACH_USER_ID = 98102L;
    private static final BigDecimal PRICE = new BigDecimal("50.00");
    private static final String RECONCILER_LOCK = "ChargeOutcomeReconciler_reconcile";

    @Autowired PaymentLifecycleService paymentLifecycleService;
    @Autowired ChargeOutcomeReconciler reconciler;

    private final AtomicInteger slot = new AtomicInteger();
    private UUID coachId;

    @BeforeEach
    void setUpFixtures() {
        coachId = insertTestCoach(COACH_USER_ID, "idem_coach@test.com", "Idem Coach");
        insertTestParent(PARENT_ID, "idem_parent@test.com");
        insertTestPlayer(PLAYER_ID, PARENT_ID);
    }

    // ── fixtures ──

    private UUID seedPendingBooking() {
        UUID bookingId = UUID.randomUUID();
        Instant start = Instant.now().plus(3, ChronoUnit.DAYS).plus(slot.getAndIncrement() * 2L, ChronoUnit.HOURS);
        transactionTemplate.execute(status -> {
            jdbcTemplate.update(
                "INSERT INTO booking.bookings (id, parent_id, player_id, coach_id, requested_start_time, "
                + "requested_end_time, status, canonical_timezone, agreed_price, version, created_at, updated_at) "
                + "VALUES (?, ?, ?, ?, ?, ?, 'PAYMENT_PENDING', 'UTC', ?, 0, now(), now())",
                bookingId, PARENT_ID, PLAYER_ID, coachId,
                Timestamp.from(start), Timestamp.from(start.plus(1, ChronoUnit.HOURS)), PRICE);
            return null;
        });
        return bookingId;
    }

    private void giveCredit(String amount) {
        transactionTemplate.execute(status -> {
            jdbcTemplate.update("INSERT INTO payment.parent_credit_ledger (parent_id, amount, type, description) "
                + "VALUES (?, ?, 'CASH_OUT_REVERSAL', 'Test credit')", PARENT_ID, new BigDecimal(amount));
            return null;
        });
    }

    /** Ages the reservations past booking.payment.charge_reconcile_after_minutes (default 10). */
    private void ageReservations() {
        transactionTemplate.execute(status -> {
            jdbcTemplate.update("UPDATE payment.booking_payments SET reserved_at = now() - interval '1 hour' "
                + "WHERE status = 'CAPTURE_PENDING'");
            return null;
        });
    }

    private BookingAcceptedEvent accepted(UUID bookingId) {
        return new BookingAcceptedEvent(this, bookingId, PARENT_ID, coachId, PRICE, null,
            "idem_parent@test.com", "Idem Coach", Instant.now().plus(3, ChronoUnit.DAYS), "UTC");
    }

    private BatchBookingAcceptedEvent batchAccepted(UUID batchId, List<UUID> ids) {
        return new BatchBookingAcceptedEvent(this, batchId, ids, PARENT_ID, coachId,
            PRICE.multiply(BigDecimal.valueOf(ids.size())), "idem_coach@test.com", "idem_parent@test.com",
            "Idem Coach", "Test Parent", ids.size());
    }

    private Map<String, Object> paymentRow(UUID bookingId) {
        return jdbcTemplate.queryForMap("SELECT status, credit_debited, stripe_charged, stripe_payment_intent_id, "
            + "charge_idempotency_key FROM payment.booking_payments WHERE booking_id = ?", bookingId);
    }

    private String bookingStatus(UUID bookingId) {
        return jdbcTemplate.queryForObject("SELECT status FROM booking.bookings WHERE id = ?", String.class, bookingId);
    }

    private List<UUID> deductionRefs() {
        return jdbcTemplate.queryForList("SELECT reference_id FROM payment.parent_credit_ledger "
            + "WHERE parent_id = ? AND type = 'BOOKING_DEDUCTION'", UUID.class, PARENT_ID);
    }

    private void reconcile() {
        releaseSchedulerLock(RECONCILER_LOCK);
        reconciler.reconcile();
    }

    // ── single booking ──

    @Test
    void singleCharge_outcomeUnknown_thenReconcilerFindsTheIntent_bookingConfirmed() {
        UUID bookingId = seedPendingBooking();
        String key = "pi-booking-" + bookingId;
        doThrow(new ChargeOutcomeUnknownException(key, new RuntimeException("read timed out")))
            .when(paymentGateway).chargeAndCapture(eq(bookingId), any(), any(), any(), eq(key));

        paymentLifecycleService.onBookingAccepted(accepted(bookingId));

        Map<String, Object> row = paymentRow(bookingId);
        assertThat(row.get("status")).as("never declined on an unknown outcome").isEqualTo("CAPTURE_PENDING");
        assertThat(row.get("charge_idempotency_key")).as("the key was persisted before the call").isEqualTo(key);
        assertThat(bookingStatus(bookingId)).isEqualTo("PAYMENT_PENDING");

        ageReservations();
        doReturn(Optional.of(new ChargeLookup("pi_found_single", ChargeLookup.SUCCEEDED, PRICE)))
            .when(paymentGateway).findChargeByIdempotencyKey(eq(PARENT_ID), eq(key), any());

        reconcile();

        row = paymentRow(bookingId);
        assertThat(row.get("status")).isEqualTo("CAPTURED");
        assertThat(row.get("stripe_payment_intent_id")).isEqualTo("pi_found_single");
        assertThat(bookingStatus(bookingId)).isEqualTo("CONFIRMED");
    }

    @Test
    void singleCharge_outcomeUnknown_thenNoIntentAtStripe_reconcilerDeclines() {
        UUID bookingId = seedPendingBooking();
        doThrow(new ChargeOutcomeUnknownException("k", new RuntimeException("connection reset")))
            .when(paymentGateway).chargeAndCapture(eq(bookingId), any(), any(), any(), anyString());

        paymentLifecycleService.onBookingAccepted(accepted(bookingId));
        ageReservations();
        // StubPaymentGateway.findChargeByIdempotencyKey: no PaymentIntent — the request never reached Stripe.
        reconcile();

        assertThat(paymentRow(bookingId).get("status")).isEqualTo("CHARGE_FAILED");
        assertThat(bookingStatus(bookingId)).isEqualTo("DECLINED");
    }

    // ── batch ──

    /**
     * H1 + M2: the batch reservations carry ONE per-settlement key and each row's share, and no credit is debited
     * before the outcome is known; the reconciler then settles every row and debits each booking's credit share
     * under its own booking id.
     */
    @Test
    void batchCharge_outcomeUnknown_rowsStampedNoCreditDebited_reconcilerSettlesAllWithPerBookingDeductions() {
        giveCredit("20.00");
        UUID b1 = seedPendingBooking();
        UUID b2 = seedPendingBooking();
        UUID batchId = UUID.randomUUID();
        doThrow(new ChargeOutcomeUnknownException("k", new RuntimeException("502")))
            .when(paymentGateway).chargeAndCaptureForBatch(eq(batchId), any(), any(), any(), anyString());

        paymentLifecycleService.onBatchBookingAccepted(batchAccepted(batchId, List.of(b1, b2)));

        Map<String, Object> r1 = paymentRow(b1);
        Map<String, Object> r2 = paymentRow(b2);
        assertThat(r1.get("status")).isEqualTo("CAPTURE_PENDING");
        assertThat(r2.get("status")).isEqualTo("CAPTURE_PENDING");
        String key = (String) r1.get("charge_idempotency_key");
        assertThat(key).startsWith("pi-batch-" + batchId + "-").isEqualTo(r2.get("charge_idempotency_key"));
        assertThat((BigDecimal) r1.get("credit_debited")).isEqualByComparingTo("20.00");
        assertThat((BigDecimal) r1.get("stripe_charged")).isEqualByComparingTo("30.00");
        assertThat((BigDecimal) r2.get("stripe_charged")).isEqualByComparingTo("50.00");
        assertThat(deductionRefs()).as("no credit leaves the wallet before the charge outcome is known").isEmpty();

        ageReservations();
        doReturn(Optional.of(new ChargeLookup("pi_found_batch", ChargeLookup.SUCCEEDED, new BigDecimal("80.00"))))
            .when(paymentGateway).findChargeByIdempotencyKey(eq(PARENT_ID), eq(key), any());

        reconcile();

        assertThat(paymentRow(b1).get("status")).isEqualTo("CAPTURED");
        assertThat(paymentRow(b2).get("status")).isEqualTo("CAPTURED");
        assertThat(bookingStatus(b1)).isEqualTo("CONFIRMED");
        assertThat(bookingStatus(b2)).isEqualTo("CONFIRMED");
        assertThat(deductionRefs()).containsExactly(b1);
    }

    /** H1: two settlements of one batch (disjoint booking sets) are charged under different keys. */
    @Test
    void twoSettlementsOfOneBatch_useDifferentIdempotencyKeys() {
        UUID b1 = seedPendingBooking();
        UUID b2 = seedPendingBooking();
        UUID batchId = UUID.randomUUID();

        paymentLifecycleService.onBatchBookingAccepted(batchAccepted(batchId, List.of(b1)));
        paymentLifecycleService.onBatchBookingAccepted(batchAccepted(batchId, List.of(b2)));

        assertThat(paymentRow(b1).get("charge_idempotency_key"))
            .isNotNull()
            .isNotEqualTo(paymentRow(b2).get("charge_idempotency_key"));
        assertThat(bookingStatus(b1)).isEqualTo("CONFIRMED");
        assertThat(bookingStatus(b2)).isEqualTo("CONFIRMED");
    }
}
