package com.softropic.skillars.platform.payment.service;

import com.softropic.skillars.config.AbstractIntegrationTest;
import com.softropic.skillars.platform.outbox.service.OutboxService;
import com.softropic.skillars.platform.payment.contract.CoachPayoutStatus;
import com.softropic.skillars.platform.payment.repo.CoachPayout;
import com.softropic.skillars.platform.payment.repo.CoachPayoutRepository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * skillars-deferred-106 AC15.4 / AC15.5, against a real database: the {@code coach_payouts} row and
 * the {@code COACH_PAYOUT_TRANSFER} outbox row commit atomically with the enqueue call, the drain
 * releases the payout exactly once, and a re-drive is a no-op. Uses the {@code StubPaymentGateway}
 * ({@code @Primary} in TestConfig) — the end-to-end Stripe test-mode verification is AC15.7 (owner:
 * Mbah), gated on AC15.6.
 */
class CoachPayoutOutboxIT extends AbstractIntegrationTest {

    @Autowired CoachPayoutOutboxSupport coachPayoutOutboxSupport;
    @Autowired CoachPayoutRepository coachPayoutRepository;
    @Autowired OutboxService outboxService;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired TransactionTemplate transactionTemplate;

    private final UUID bookingId = UUID.randomUUID();
    private final UUID coachId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        transactionTemplate.execute(s -> {
            jdbcTemplate.update("DELETE FROM main.outbox_messages WHERE payload->>'bookingId' = ?", bookingId.toString());
            jdbcTemplate.update("DELETE FROM payment.coach_payouts WHERE booking_id = ?", bookingId);
            jdbcTemplate.update("DELETE FROM payment.booking_payments WHERE booking_id = ?", bookingId);
            return null;
        });
    }

    private void seedCapturedBookingPayment(String stripeCharged, String commissionRate) {
        transactionTemplate.execute(s -> {
            jdbcTemplate.update(
                "INSERT INTO payment.booking_payments (booking_id, credit_debited, stripe_charged, status, "
                    + "captured_at, commission_rate) VALUES (?, 0, ?::numeric, 'CAPTURED', ?, ?::numeric)",
                bookingId, stripeCharged, Timestamp.from(Instant.now()), commissionRate);
            return null;
        });
    }

    private long myOutboxRows() {
        return jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM main.outbox_messages WHERE payload->>'bookingId' = ?", Long.class, bookingId.toString());
    }

    @Test
    void enqueuePayout_writesRowAndOutboxAtomically_thenDrainReleasesOnce() {
        seedCapturedBookingPayment("50.00", "0.10");

        transactionTemplate.execute(s -> {
            coachPayoutOutboxSupport.enqueuePayout(bookingId, coachId);
            return null;
        });

        CoachPayout row = coachPayoutRepository.findById(bookingId).orElseThrow();
        assertThat(row.getStatus()).isEqualTo(CoachPayoutStatus.PENDING_RELEASE);
        assertThat(row.getNetAmount()).isEqualByComparingTo("45.00");
        assertThat(myOutboxRows()).isEqualTo(1);

        // Hold window default is 48h — pull release_after into the past so the drain acts now.
        transactionTemplate.execute(s -> jdbcTemplate.update(
            "UPDATE payment.coach_payouts SET release_after = ? WHERE booking_id = ?",
            Timestamp.from(Instant.now().minus(1, ChronoUnit.HOURS)), bookingId));
        transactionTemplate.execute(s -> jdbcTemplate.update(
            "UPDATE main.outbox_messages SET next_attempt_at = now() - interval '1 minute' "
                + "WHERE payload->>'bookingId' = ?", bookingId.toString()));

        outboxService.drain();

        CoachPayout released = coachPayoutRepository.findById(bookingId).orElseThrow();
        assertThat(released.getStatus()).isEqualTo(CoachPayoutStatus.RELEASED);
        assertThat(released.getStripeTransferId()).isEqualTo("tr_stub_" + bookingId);
        assertThat(released.getReleasedAt()).isNotNull();
        assertThat(myOutboxRows()).as("outbox row consumed").isZero();

        // A re-driven message for an already-RELEASED row is a no-op.
        transactionTemplate.execute(s -> {
            outboxService.enqueue(CoachPayoutOutboxSupport.AGGREGATE_TYPE_TRANSFER,
                "{\"bookingId\":\"" + bookingId + "\"}");
            return null;
        });
        outboxService.drain();
        assertThat(coachPayoutRepository.findById(bookingId).orElseThrow().getStatus())
            .isEqualTo(CoachPayoutStatus.RELEASED);
        assertThat(myOutboxRows()).isZero();
    }

    @Test
    void fullyCreditFundedBooking_writesCancelledRow_andNeverEnqueues() {
        seedCapturedBookingPayment("0.00", "0.10");

        transactionTemplate.execute(s -> {
            coachPayoutOutboxSupport.enqueuePayout(bookingId, coachId);
            return null;
        });

        CoachPayout row = coachPayoutRepository.findById(bookingId).orElseThrow();
        assertThat(row.getStatus()).isEqualTo(CoachPayoutStatus.CANCELLED);
        assertThat(row.getLastError()).isEqualTo("NO_STRIPE_CHARGE");
        assertThat(myOutboxRows()).isZero();
    }

    @Test
    void disputeInsideHoldWindow_flipsPendingRowToCancelled_andDrainIsNoOp() {
        seedCapturedBookingPayment("50.00", "0.10");
        transactionTemplate.execute(s -> {
            coachPayoutOutboxSupport.enqueuePayout(bookingId, coachId);
            return null;
        });

        // Simulate DisputeService.reconcileCoachPayout's PENDING_RELEASE -> CANCELLED branch.
        transactionTemplate.execute(s -> {
            CoachPayout row = coachPayoutRepository.findByIdForUpdate(bookingId).orElseThrow();
            row.setStatus(CoachPayoutStatus.CANCELLED);
            row.setLastError("DISPUTE_RESOLVED_FULL_CREDIT (was PENDING_RELEASE)");
            coachPayoutRepository.save(row);
            return null;
        });

        transactionTemplate.execute(s -> jdbcTemplate.update(
            "UPDATE main.outbox_messages SET next_attempt_at = now() - interval '1 minute' "
                + "WHERE payload->>'bookingId' = ?", bookingId.toString()));
        outboxService.drain();

        assertThat(coachPayoutRepository.findById(bookingId).orElseThrow().getStatus())
            .isEqualTo(CoachPayoutStatus.CANCELLED);
        assertThat(coachPayoutRepository.findById(bookingId).orElseThrow().getStripeTransferId())
            .as("no transfer ever created").isNull();
        assertThat(myOutboxRows()).as("the outbox row drained to a no-op and was removed").isZero();
    }
}
