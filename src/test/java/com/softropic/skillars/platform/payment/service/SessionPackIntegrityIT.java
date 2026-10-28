package com.softropic.skillars.platform.payment.service;

import com.softropic.skillars.platform.booking.contract.BookingCancelledByCoachEvent;
import com.softropic.skillars.platform.booking.contract.PausePackRequest;
import com.softropic.skillars.platform.booking.contract.PauseConflictResponse;
import com.softropic.skillars.platform.outbox.service.OutboxService;
import com.softropic.skillars.platform.payment.BasePaymentIT;
import com.softropic.skillars.platform.payment.contract.SessionPackPurchaseResponse;
import com.softropic.skillars.platform.payment.contract.exception.PaymentGatewayException;
import com.softropic.skillars.platform.payment.repo.SessionPackPurchase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataAccessResourceFailureException;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * fix/payment-session-pack-integrity, against the real database, unique index and outbox:
 * <ul>
 *   <li>item 1 — a purchase attempt key dedupes a double-submit to one charge and one pack, two attempts
 *       of the same tier are two packs, and the compensating refund never takes back another pack's money;</li>
 *   <li>item 5 — a pause cancelling paid bookings returns what was paid (pack session / amount paid), and
 *       nothing for a not-yet-captured booking;</li>
 *   <li>item 6 — pack restores run off the durable outbox, once per booking, and survive a lock timeout.</li>
 * </ul>
 * Uses only the inherited spies ({@code paymentGateway}, {@code sessionPackPurchaseRepository}): no new
 * context-cache key.
 */
class SessionPackIntegrityIT extends BasePaymentIT {

    private static final long PARENT_ID = 9192_000_001L;
    private static final long PLAYER_ID = 9192_000_002L;
    private static final long COACH_USER_ID = 9192_000_010L;

    @Autowired SessionPackPaymentService sessionPackPaymentService;
    @Autowired PackSessionService packSessionService;
    @Autowired PackSessionRestoreOutboxHandler restoreHandler;
    @Autowired OutboxService outboxService;
    @Autowired ApplicationEventPublisher eventPublisher;

    private UUID coachId;
    private UUID tierId;

    @BeforeEach
    void setUp() {
        coachId = insertTestCoach(COACH_USER_ID, "integrity.coach9192@test.com", "Integrity Coach");
        insertTestParent(PARENT_ID, "integrity.parent9192@test.com");
        insertTestPlayer(PLAYER_ID, PARENT_ID);
        tierId = sessionPackPaymentService.createTier(coachId, "3-Pack", 3, new BigDecimal("90.00")).packTierId();
    }

    @AfterEach
    void tearDown() {
        transactionTemplate.execute(status -> {
            jdbcTemplate.update("DELETE FROM main.outbox_messages WHERE aggregate_type IN (?, ?)",
                PackRestoreOutboxSupport.AGGREGATE_TYPE, RefundOutboxSupport.AGGREGATE_TYPE);
            jdbcTemplate.execute("SET SESSION session_replication_role = 'replica'");
            jdbcTemplate.update("DELETE FROM payment.parent_credit_ledger WHERE parent_id = ?", PARENT_ID);
            jdbcTemplate.execute("SET SESSION session_replication_role = 'origin'");
            jdbcTemplate.update("DELETE FROM payment.booking_payments WHERE booking_id IN "
                + "(SELECT id FROM booking.bookings WHERE coach_id = ?)", coachId);
            jdbcTemplate.update("DELETE FROM payment.coach_cancellation_history WHERE coach_id = ?", coachId);
            jdbcTemplate.update("DELETE FROM marketplace.coach_reliability_strikes WHERE coach_id = ?", coachId);
            jdbcTemplate.update("DELETE FROM booking.bookings WHERE coach_id = ?", coachId);
            jdbcTemplate.update("DELETE FROM payment.stripe_refund_failures WHERE parent_id = ?", PARENT_ID);
            jdbcTemplate.update("DELETE FROM payment.session_pack_purchases WHERE parent_id = ?", PARENT_ID);
            jdbcTemplate.update("DELETE FROM payment.session_pack_tiers WHERE coach_id = ?", coachId);
            jdbcTemplate.update("DELETE FROM payment.stripe_customers WHERE parent_id = ?", PARENT_ID);
            return null;
        });
    }

    // ── item 1: one purchase attempt → one charge → one pack ──

    @Test
    void sameAttemptKeyTwice_replaysTheCharge_returnsTheSamePack() {
        SessionPackPurchaseResponse first = sessionPackPaymentService.purchasePack(PARENT_ID, tierId, PLAYER_ID, "pm_x", "attempt-key-0001");
        SessionPackPurchaseResponse second = sessionPackPaymentService.purchasePack(PARENT_ID, tierId, PLAYER_ID, "pm_x", "attempt-key-0001");

        assertThat(second.purchaseId()).isEqualTo(first.purchaseId());
        assertThat(packCount()).isEqualTo(1);
    }

    /** A sibling's purchase of the same tier inside the same minute is a second pack, never a replay. */
    @Test
    void twoAttemptsOfTheSameTier_twoPacks_twoCharges() {
        SessionPackPurchaseResponse first = sessionPackPaymentService.purchasePack(PARENT_ID, tierId, PLAYER_ID, "pm_x");
        SessionPackPurchaseResponse second = sessionPackPaymentService.purchasePack(PARENT_ID, tierId, PLAYER_ID, "pm_x");

        assertThat(second.purchaseId()).isNotEqualTo(first.purchaseId());
        assertThat(second.stripePaymentIntentId()).isNotEqualTo(first.stripePaymentIntentId());
        assertThat(packCount()).isEqualTo(2);
    }

    /**
     * A concurrent double-submit: the second delivery misses the first pack on its pre-check, loses the insert
     * to the V195 unique index — and must return the winner, NOT refund the charge the winner's pack holds.
     */
    @Test
    void doubleSubmitLosingTheInsert_returnsTheWinner_neverRefundsIt() {
        SessionPackPurchaseResponse winner = sessionPackPaymentService.purchasePack(PARENT_ID, tierId, PLAYER_ID, "pm_x", "attempt-key-0002");
        // The spy wraps a Spring Data proxy (no callable real method): the first lookup (the pre-check) misses,
        // the later one (after the insert lost) reads the table directly.
        AtomicInteger lookups = new AtomicInteger();
        doAnswer(inv -> lookups.getAndIncrement() == 0
            ? Optional.empty()
            : jdbcTemplate.queryForList("SELECT purchase_id FROM payment.session_pack_purchases "
                    + "WHERE stripe_payment_intent_id = ?", UUID.class, (String) inv.getArgument(0))
                .stream().findFirst().flatMap(sessionPackPurchaseRepository::findById))
            .when(sessionPackPurchaseRepository).findByStripePaymentIntentId(anyString());

        SessionPackPurchaseResponse loser = sessionPackPaymentService.purchasePack(PARENT_ID, tierId, PLAYER_ID, "pm_x", "attempt-key-0002");

        assertThat(loser.purchaseId()).isEqualTo(winner.purchaseId());
        assertThat(packCount()).isEqualTo(1);
        verify(paymentGateway, never()).refund(any(), any(), any());
    }

    /** The pack record fails after capture and no pack holds the charge: that charge — only it — is refunded. */
    @Test
    void recordFailsAfterCapture_refundsThisChargeUnderItsOwnKey() {
        // Only the purchase insert runs through this spy's save() in this test.
        doThrow(new DataAccessResourceFailureException("db down"))
            .when(sessionPackPurchaseRepository).save(any(SessionPackPurchase.class));

        assertThatThrownBy(() -> sessionPackPaymentService.purchasePack(PARENT_ID, tierId, PLAYER_ID, "pm_x", "attempt-key-0003"))
            .isInstanceOf(PaymentGatewayException.class)
            .hasMessageContaining("payment.lifecycleFailure");

        String pi = "pi_stub_pack-" + PARENT_ID + "-attempt-key-0003";
        verify(paymentGateway).refund(eq(pi), eq(new BigDecimal("90.00")), eq("refund-" + pi));
        assertThat(packCount()).isZero();
    }

    // ── item 5: a pause returns what the cancelled bookings paid ──

    @Test
    void pauseCancellingPaidBookings_refundsTheAmountPaid_restoresThePackSession_nothingForUncaptured() {
        UUID packId = insertPack(3);
        Instant base = Instant.now().plus(3, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS);
        UUID card = booking("CONFIRMED", null, base);
        capturedPayment(card, "10.00", "30.00");
        UUID packBooking = booking("UPCOMING", packId, base.plus(3, ChronoUnit.HOURS));
        UUID requested = booking("REQUESTED", packId, base.plus(6, ChronoUnit.HOURS));

        PauseConflictResponse response = packSessionService.pausePack(PARENT_ID, packId, new PausePackRequest(
            Instant.now().plus(1, ChronoUnit.DAYS), 14, List.of(card, packBooking, requested)));

        assertThat(response.pauseApplied()).isTrue();
        outboxService.drain();
        assertThat(refundedAmount(card)).isEqualByComparingTo("40.00");
        assertThat(remainingSessions(packId)).isEqualTo(4);
        assertThat(restoreMarkers(packBooking)).isEqualTo(1);
        assertThat(restoreMarkers(requested)).isZero();
        assertThat(ledgerRows(requested)).isZero();
        assertThat(ledgerRows(packBooking)).isZero();
        assertThat(count("SELECT COUNT(*) FROM marketplace.coach_reliability_strikes WHERE coach_id = ?")).isZero();
    }

    // ── item 6: restores are durable and once per booking ──

    @Test
    void restore_viaOutbox_redeliveryIsANoOp() {
        UUID packId = insertPack(2);
        UUID bookingId = UUID.randomUUID();

        publishCoachCancellation(packId, bookingId);
        assertThat(remainingSessions(packId)).isEqualTo(3);

        String payload = "{\"purchaseId\":\"" + packId + "\",\"bookingId\":\"" + bookingId + "\",\"reason\":\"COACH_CANCELLATION\"}";
        restoreHandler.handle(payload);
        restoreHandler.handle(payload);

        assertThat(remainingSessions(packId)).isEqualTo(3);
        assertThat(restoreMarkers(bookingId)).isEqualTo(1);
    }

    /** A lock timeout used to lose the session for good; now the outbox row waits and the next drain applies it. */
    @Test
    void restore_survivesALockTimeout_andIsAppliedOnTheNextDrain() throws Exception {
        UUID packId = insertPack(2);
        UUID bookingId = UUID.randomUUID();
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Thread holder = new Thread(() -> transactionTemplate.execute(status -> {
            jdbcTemplate.queryForObject("SELECT remaining_sessions FROM payment.session_pack_purchases "
                + "WHERE purchase_id = ? FOR UPDATE", Integer.class, packId);
            locked.countDown();
            try {
                release.await(30, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return null;
        }));
        holder.start();
        assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();
        try {
            publishCoachCancellation(packId, bookingId);
            assertThat(remainingSessions(packId)).as("the restore could not take the lock").isEqualTo(2);
            assertThat(pendingRestoreRows(bookingId)).as("...but it was not lost").isEqualTo(1);
        } finally {
            release.countDown();
            holder.join(10_000);
        }

        transactionTemplate.execute(status -> jdbcTemplate.update(
            // Backdated, not now(): the DB container's clock can run ahead of the JVM's Instant.now().
            "UPDATE main.outbox_messages SET next_attempt_at = now() - interval '1 hour' WHERE aggregate_type = ?",
            PackRestoreOutboxSupport.AGGREGATE_TYPE));
        outboxService.drain();

        assertThat(remainingSessions(packId)).isEqualTo(3);
        assertThat(pendingRestoreRows(bookingId)).isZero();
    }

    // ── helpers ──

    private void publishCoachCancellation(UUID packId, UUID bookingId) {
        BookingCancelledByCoachEvent event = new BookingCancelledByCoachEvent(
            this, bookingId, PARENT_ID, coachId, "WEATHER", packId, new BigDecimal("30.00"), false, true,
            "integrity.parent9192@test.com", Instant.now(), "UTC");
        transactionTemplate.execute(status -> {
            eventPublisher.publishEvent(event);
            return null;
        });
    }

    private UUID insertPack(int remaining) {
        UUID packId = UUID.randomUUID();
        transactionTemplate.execute(s -> jdbcTemplate.update(
            "INSERT INTO payment.session_pack_purchases (purchase_id, parent_id, player_id, coach_id, pack_tier_id, "
                + "price_per_session, amount_charged, session_count, remaining_sessions, expires_at, version, created_at) "
                + "VALUES (?, ?, ?, ?, ?, 30.00, 90.00, 3, ?, ?, 0, now())",
            packId, PARENT_ID, PLAYER_ID, coachId, tierId, remaining,
            Timestamp.from(Instant.now().plus(60, ChronoUnit.DAYS))));
        return packId;
    }

    private UUID booking(String status, UUID packId, Instant start) {
        UUID id = UUID.randomUUID();
        // One Instant for start and end (chk_bkg_end_after_start).
        transactionTemplate.execute(s -> jdbcTemplate.update(
            "INSERT INTO booking.bookings (id, coach_id, parent_id, player_id, status, requested_start_time, "
                + "requested_end_time, version, created_at, updated_at, canonical_timezone, session_pack_purchase_id) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, 0, now(), now(), 'UTC', ?)",
            id, coachId, PARENT_ID, PLAYER_ID, status,
            Timestamp.from(start), Timestamp.from(start.plus(1, ChronoUnit.HOURS)), packId));
        return id;
    }

    private void capturedPayment(UUID bookingId, String credit, String card) {
        transactionTemplate.execute(s -> jdbcTemplate.update(
            "INSERT INTO payment.booking_payments (booking_id, credit_debited, stripe_charged, status, captured_at) "
                + "VALUES (?, ?, ?, 'CAPTURED', now())", bookingId, new BigDecimal(credit), new BigDecimal(card)));
    }

    private long packCount() {
        return jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM payment.session_pack_purchases WHERE parent_id = ?", Long.class, PARENT_ID);
    }

    private int remainingSessions(UUID packId) {
        return jdbcTemplate.queryForObject(
            "SELECT remaining_sessions FROM payment.session_pack_purchases WHERE purchase_id = ?", Integer.class, packId);
    }

    private BigDecimal refundedAmount(UUID bookingId) {
        return jdbcTemplate.queryForObject(
            "SELECT amount FROM payment.parent_credit_ledger WHERE reference_id = ? AND type = 'BOOKING_REFUND'",
            BigDecimal.class, bookingId);
    }

    private long ledgerRows(UUID bookingId) {
        return jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM payment.parent_credit_ledger WHERE reference_id = ?", Long.class, bookingId);
    }

    private long restoreMarkers(UUID bookingId) {
        return jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM payment.pack_session_restores WHERE booking_id = ?", Long.class, bookingId);
    }

    private long pendingRestoreRows(UUID bookingId) {
        return jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM main.outbox_messages WHERE aggregate_type = ? AND payload->>'bookingId' = ?",
            Long.class, PackRestoreOutboxSupport.AGGREGATE_TYPE, bookingId.toString());
    }

    private long count(String sql) {
        Long n = jdbcTemplate.queryForObject(sql, Long.class, coachId);
        return n == null ? 0 : n;
    }
}
