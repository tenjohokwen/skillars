package com.softropic.skillars.platform.payment.service;

import com.softropic.skillars.platform.admin.contract.CoachReinstatedEvent;
import com.softropic.skillars.platform.outbox.service.OutboxService;
import com.softropic.skillars.platform.payment.BasePaymentIT;
import com.softropic.skillars.platform.payment.contract.CoachPayoutStatus;
import com.softropic.skillars.platform.payment.repo.CoachPayout;
import com.softropic.skillars.platform.payment.repo.CoachPayoutRepository;
import com.softropic.skillars.platform.security.contract.event.AccountSuspensionRequestedEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * feat/coach-payouts-pack-and-credit (DECISIONS.md items 2 + 3), against a real database: pack- and
 * credit-funded sessions pay the coach through the existing completion-gated payout; concurrent
 * completions of one pack allocate exactly what the pack was charged; a CSAM safety hold freezes payouts
 * until an admin reinstates the coach. Uses the shared {@code StubPaymentGateway} spy.
 */
class CoachPayoutPackCreditIT extends BasePaymentIT {

    private static final long PARENT_ID = 98101L;
    private static final long PLAYER_ID = 98102L;
    private static final long COACH_USER_ID = 98103L;

    @Autowired CoachPayoutOutboxSupport coachPayoutOutboxSupport;
    @Autowired CoachPayoutFreezeService coachPayoutFreezeService;
    @Autowired CoachPayoutAdjustmentService coachPayoutAdjustmentService;
    @Autowired CoachPayoutRepository coachPayoutRepository;
    @Autowired OutboxService outboxService;
    @Autowired ApplicationEventPublisher eventPublisher;
    @Autowired com.softropic.skillars.platform.admin.service.AdminCoachEnforcementService enforcementService;

    private UUID coachId;

    @BeforeEach
    void seed() {
        coachId = insertTestCoach(COACH_USER_ID, "payout_pack_coach@test.com", "Pack Coach");
        insertTestParent(PARENT_ID, "payout_pack_parent@test.com");
        insertTestPlayer(PLAYER_ID, PARENT_ID);
    }

    // ── fixtures ──

    private UUID seedPack(String amountCharged, int sessions, String commissionRate) {
        UUID tierId = UUID.randomUUID();
        UUID purchaseId = UUID.randomUUID();
        transactionTemplate.execute(s -> {
            jdbcTemplate.update("INSERT INTO payment.session_pack_tiers (pack_tier_id, coach_id, label, session_count, "
                + "total_price, price_per_session, is_active, version, created_at) "
                + "VALUES (?, ?, 'IT pack', ?, ?::numeric, 1.00, true, 0, now())",
                tierId, coachId, sessions, amountCharged);
            jdbcTemplate.update("INSERT INTO payment.session_pack_purchases (purchase_id, parent_id, player_id, coach_id, "
                + "pack_tier_id, price_per_session, amount_charged, session_count, commission_rate, remaining_sessions, "
                + "expires_at, version, created_at) VALUES (?, ?, ?, ?, ?, 1.00, ?::numeric, ?, ?::numeric, 0, ?, 0, now())",
                purchaseId, PARENT_ID, PLAYER_ID, coachId, tierId, amountCharged, sessions, commissionRate,
                Timestamp.from(Instant.now().plus(30, ChronoUnit.DAYS)));
            return null;
        });
        return purchaseId;
    }

    /** Each booking gets its own past slot (excl_bkg_coach_slot_overlap); start/end from ONE instant. */
    private int slot;

    private UUID seedBooking(UUID packId) {
        UUID bookingId = UUID.randomUUID();
        Instant start = Instant.now().minus(2 * (++slot), ChronoUnit.HOURS);
        transactionTemplate.execute(s -> jdbcTemplate.update(
            "INSERT INTO booking.bookings (id, parent_id, player_id, coach_id, requested_start_time, requested_end_time, "
                + "status, canonical_timezone, session_pack_purchase_id, version, created_at, updated_at) "
                + "VALUES (?, ?, ?, ?, ?, ?, 'COMPLETED', 'UTC', ?, 0, now(), now())",
            bookingId, PARENT_ID, PLAYER_ID, coachId, Timestamp.from(start),
            Timestamp.from(start.plus(1, ChronoUnit.HOURS)), packId));
        return bookingId;
    }

    private void seedCapturedPayment(UUID bookingId, String card, String credit, String rate) {
        transactionTemplate.execute(s -> jdbcTemplate.update(
            "INSERT INTO payment.booking_payments (booking_id, credit_debited, stripe_charged, status, captured_at, "
                + "commission_rate) VALUES (?, ?::numeric, ?::numeric, 'CAPTURED', now(), ?::numeric)",
            bookingId, credit, card, rate));
    }

    private void enqueue(UUID bookingId) {
        transactionTemplate.execute(s -> {
            coachPayoutOutboxSupport.enqueuePayout(bookingId, coachId);
            return null;
        });
    }

    /** Pull the hold window into the past and make the booking's outbox rows due now, then drain. */
    private void releaseHoldAndDrain(UUID bookingId) {
        transactionTemplate.execute(s -> {
            jdbcTemplate.update("UPDATE payment.coach_payouts SET release_after = ? WHERE booking_id = ?",
                Timestamp.from(Instant.now().minus(1, ChronoUnit.HOURS)), bookingId);
            jdbcTemplate.update("UPDATE main.outbox_messages SET next_attempt_at = now() - interval '1 minute' "
                + "WHERE payload->>'bookingId' = ?", bookingId.toString());
            return null;
        });
        outboxService.drain();
    }

    private long outboxRows(UUID bookingId) {
        return jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM main.outbox_messages WHERE payload->>'bookingId' = ?", Long.class, bookingId.toString());
    }

    private CoachPayout payout(UUID bookingId) {
        return coachPayoutRepository.findById(bookingId).orElseThrow();
    }

    // ── pack ──

    @Test
    @Timeout(60)
    void concurrentCompletionsOfOnePack_allocateExactlyWhatThePackWasCharged() throws Exception {
        UUID packId = seedPack("100.00", 3, "0.10");
        List<UUID> bookings = List.of(seedBooking(packId), seedBooking(packId), seedBooking(packId));
        bookings.forEach(b -> seedCapturedPayment(b, "0.00", "0.00", "0.10"));

        CyclicBarrier start = new CyclicBarrier(bookings.size());
        ExecutorService pool = Executors.newFixedThreadPool(bookings.size());
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (UUID b : bookings) {
                futures.add(pool.submit(() -> {
                    start.await(10, TimeUnit.SECONDS);
                    enqueue(b);
                    return null;
                }));
            }
            for (Future<?> f : futures) {
                f.get(30, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        List<BigDecimal> gross = bookings.stream().map(b -> payout(b).getGrossAmount()).sorted().toList();
        assertThat(gross).extracting(BigDecimal::toPlainString).containsExactly("33.33", "33.33", "33.34");
        assertThat(bookings).allSatisfy(b -> {
            CoachPayout p = payout(b);
            assertThat(p.getStatus()).isEqualTo(CoachPayoutStatus.PENDING_RELEASE);
            assertThat(p.getSessionPackPurchaseId()).isEqualTo(packId);
            assertThat(p.getNetAmount()).isEqualByComparingTo(p.getGrossAmount()
                .subtract(p.getGrossAmount().multiply(new BigDecimal("0.10")).setScale(2, java.math.RoundingMode.HALF_UP)));
        });
        Map<String, Object> pack = jdbcTemplate.queryForMap(
            "SELECT payout_sessions_allocated, payout_gross_allocated FROM payment.session_pack_purchases "
                + "WHERE purchase_id = ?", packId);
        assertThat(pack.get("payout_sessions_allocated")).isEqualTo(3);
        assertThat((BigDecimal) pack.get("payout_gross_allocated")).isEqualByComparingTo("100.00");

        // A fourth completion against the same pack (impossible through deductSession, simulated here)
        // never pays beyond what the pack was charged.
        UUID extra = seedBooking(packId);
        enqueue(extra);
        assertThat(payout(extra).getStatus()).isEqualTo(CoachPayoutStatus.CANCELLED);
        assertThat(payout(extra).getLastError()).isEqualTo(CoachPayoutOutboxSupport.REASON_PACK_FULLY_ALLOCATED);
        assertThat(outboxRows(extra)).isZero();
    }

    @Test
    void packSession_isReleasedThroughTheOutboxAfterTheHold() {
        UUID packId = seedPack("90.00", 3, "0.20");
        UUID bookingId = seedBooking(packId);
        seedCapturedPayment(bookingId, "0.00", "0.00", "0.10");

        enqueue(bookingId);
        assertThat(payout(bookingId).getNetAmount()).isEqualByComparingTo("24.00"); // 30 - 30 * 0.20 (pack rate)
        assertThat(outboxRows(bookingId)).isEqualTo(1);

        releaseHoldAndDrain(bookingId);

        assertThat(payout(bookingId).getStatus()).isEqualTo(CoachPayoutStatus.RELEASED);
        assertThat(payout(bookingId).getStripeTransferId()).isEqualTo("tr_stub_" + bookingId);
    }

    /**
     * fix/payment-pack-clawback-and-expired-cancel: 0.02 for 3 sessions, so each share is 0.00. The first
     * session completes NOTHING_CHARGED, with its 0.00 share already allocated on the pack. An UPHOLD_COACH
     * payInFull on it used to delete that row and enqueue again, allocating the session a second time: the
     * second session then became the pack's "last" and took the 0.02, and the real last session got nothing.
     * Now the session stays settled once and the third session takes the remainder.
     */
    @Test
    void upholdCoachOnAPackSessionSettledAtZero_neverSettlesItTwice() {
        UUID packId = seedPack("0.02", 3, "0.10");
        UUID first = seedBooking(packId);
        UUID second = seedBooking(packId);
        UUID third = seedBooking(packId);

        enqueue(first);
        assertThat(payout(first).getStatus()).isEqualTo(CoachPayoutStatus.CANCELLED);
        BigDecimal owed = transactionTemplate.execute(s -> coachPayoutAdjustmentService.payInFull(first, coachId));
        assertThat(owed).isEqualByComparingTo("0");
        assertThat(jdbcTemplate.queryForObject(
            "SELECT payout_sessions_allocated FROM payment.session_pack_purchases WHERE purchase_id = ?",
            Integer.class, packId)).as("the session is settled once").isEqualTo(1);

        enqueue(second);
        enqueue(third);
        assertThat(payout(second).getGrossAmount()).isEqualByComparingTo("0.00");
        assertThat(payout(third).getGrossAmount()).as("the real last session takes the remainder")
            .isEqualByComparingTo("0.02");
        Map<String, Object> ledger = jdbcTemplate.queryForMap(
            "SELECT payout_sessions_allocated, payout_gross_allocated FROM payment.session_pack_purchases "
                + "WHERE purchase_id = ?", packId);
        assertThat(ledger.get("payout_sessions_allocated")).isEqualTo(3);
        assertThat((BigDecimal) ledger.get("payout_gross_allocated")).isEqualByComparingTo("0.02");
    }

    /** The card / credit path still replaces a nothing-charged payout with one computed from its payment row. */
    @Test
    void upholdCoachOnACardSessionWithNothingComputed_isStillRecomputed() {
        UUID booking = seedBooking(null);
        seedCapturedPayment(booking, "0.00", "0.00", "0.10");
        enqueue(booking);
        assertThat(payout(booking).getStatus()).isEqualTo(CoachPayoutStatus.CANCELLED);

        transactionTemplate.execute(s -> jdbcTemplate.update(
            "UPDATE payment.booking_payments SET stripe_charged = 50.00 WHERE booking_id = ?", booking));
        BigDecimal owed = transactionTemplate.execute(s -> coachPayoutAdjustmentService.payInFull(booking, coachId));

        assertThat(owed).isEqualByComparingTo("45.00");
        assertThat(payout(booking).getGrossAmount()).isEqualByComparingTo("50.00");
    }

    // ── credit ──

    @Test
    void creditFundedAndMixedSessions_payTheWholePriceOnceAtTheCaptureRate() {
        UUID creditOnly = seedBooking(null);
        UUID mixed = seedBooking(null);
        seedCapturedPayment(creditOnly, "0.00", "50.00", "0.10");
        seedCapturedPayment(mixed, "30.00", "20.00", "0.10");

        enqueue(creditOnly);
        enqueue(mixed);
        enqueue(mixed); // a duplicate completion event is a no-op

        assertThat(payout(creditOnly).getNetAmount()).isEqualByComparingTo("45.00");
        assertThat(payout(mixed).getGrossAmount()).isEqualByComparingTo("50.00");
        assertThat(payout(mixed).getNetAmount()).isEqualByComparingTo("45.00");
        assertThat(outboxRows(mixed)).as("one transfer for card + credit together").isEqualTo(1);

        releaseHoldAndDrain(creditOnly);
        assertThat(payout(creditOnly).getStatus()).isEqualTo(CoachPayoutStatus.RELEASED);
    }

    // ── safety-hold freeze ──

    @Test
    void safetyHold_freezesPendingPayouts_andReinstatementReleasesThem() {
        UUID bookingId = seedBooking(null);
        seedCapturedPayment(bookingId, "50.00", "0.00", "0.10");
        enqueue(bookingId);

        coachPayoutFreezeService.onAccountSuspensionRequested(
            new AccountSuspensionRequestedEvent(coachId.toString(), UUID.randomUUID()));
        assertThat(payout(bookingId).getStatus()).isEqualTo(CoachPayoutStatus.FROZEN);

        releaseHoldAndDrain(bookingId);
        assertThat(payout(bookingId).getStatus()).as("the hold stands: no transfer").isEqualTo(CoachPayoutStatus.FROZEN);
        verify(paymentGateway, never()).transferToCoach(eq(bookingId), any(), any(), any());

        // A session completing while the hold stands is recorded FROZEN directly.
        UUID duringHold = seedBooking(null);
        seedCapturedPayment(duringHold, "40.00", "0.00", "0.10");
        enqueue(duringHold);
        assertThat(payout(duringHold).getStatus()).isEqualTo(CoachPayoutStatus.FROZEN);
        assertThat(outboxRows(duringHold)).isZero();

        // Admin reinstatement, published in a transaction exactly like AdminCoachEnforcementService does.
        transactionTemplate.execute(s -> {
            eventPublisher.publishEvent(new CoachReinstatedEvent(this, coachId, 1L));
            return null;
        });
        // Unfrozen and re-enqueued; its hold was already pulled into the past above, so the after-commit
        // drain the re-enqueue requests may already have released it.
        assertThat(payout(bookingId).getStatus())
            .isIn(CoachPayoutStatus.PENDING_RELEASE, CoachPayoutStatus.RELEASED);
        assertThat(jdbcTemplate.queryForObject(
            "SELECT lifted_at IS NOT NULL FROM payment.coach_payout_freezes WHERE coach_id = ?", Boolean.class, coachId))
            .isTrue();

        releaseHoldAndDrain(bookingId);
        releaseHoldAndDrain(duringHold);
        assertThat(payout(bookingId).getStatus()).isEqualTo(CoachPayoutStatus.RELEASED);
        assertThat(payout(duringHold).getStatus()).isEqualTo(CoachPayoutStatus.RELEASED);
    }

    /**
     * The real CSAM chain, end to end: the published event runs the admin profile hold
     * (CoachSafetySuspensionListener) AND the payout freeze. Both safety-hold paths are covered — a coach
     * who was ACTIVE (new hold) and one an admin had already suspended (C2 converts that suspension into
     * a safety hold) — and in both the real admin reinstatement releases the payout.
     */
    @Test
    void realSafetyHold_newHold_freezesPayouts_andRealReinstatementReleasesThem() {
        assertRealSafetyHoldFreezesAndReinstatementReleases(false);
    }

    @Test
    void realSafetyHold_convertedAdminSuspension_freezesPayouts_andRealReinstatementReleasesThem() {
        assertRealSafetyHoldFreezesAndReinstatementReleases(true);
    }

    private void assertRealSafetyHoldFreezesAndReinstatementReleases(boolean alreadyAdminSuspended) {
        UUID bookingId = seedBooking(null);
        seedCapturedPayment(bookingId, "50.00", "0.00", "0.10");
        enqueue(bookingId);
        // AccountSuspensionEventListener updates the coach's user row, and User validates a 60-char hash.
        transactionTemplate.execute(s -> jdbcTemplate.update(
            "UPDATE main.\"user\" SET password_hash = ? WHERE id = ?", "$2a$10$" + "x".repeat(53), COACH_USER_ID));
        if (alreadyAdminSuspended) {
            transactionTemplate.execute(s -> jdbcTemplate.update(
                "UPDATE marketplace.coach_profiles SET status = 'SUSPENDED', status_changed_at = now() WHERE id = ?",
                coachId));
        }

        // Published outside a transaction, exactly as ModerationOrchestrationService does.
        eventPublisher.publishEvent(new AccountSuspensionRequestedEvent(coachId.toString(), UUID.randomUUID()));

        assertThat(jdbcTemplate.queryForObject(
            "SELECT status FROM marketplace.coach_profiles WHERE id = ?", String.class, coachId)).isEqualTo("SUSPENDED");
        assertThat(jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM admin.admin_action_log WHERE reference_id = ? AND action_type = 'COACH_SAFETY_HOLD'",
            Long.class, coachId.toString())).as("the admin safety-hold path ran").isEqualTo(1L);
        assertThat(payout(bookingId).getStatus()).isEqualTo(CoachPayoutStatus.FROZEN);
        releaseHoldAndDrain(bookingId);
        assertThat(payout(bookingId).getStatus()).isEqualTo(CoachPayoutStatus.FROZEN);
        verify(paymentGateway, never()).transferToCoach(eq(bookingId), any(), any(), any());

        enforcementService.reinstateCoach(coachId, "false positive", PARENT_ID);
        releaseHoldAndDrain(bookingId);
        assertThat(payout(bookingId).getStatus()).isEqualTo(CoachPayoutStatus.RELEASED);
    }

    @Test
    void holdThatTheFreezeUpdateMissed_isStillCaughtAtReleaseTime() {
        UUID bookingId = seedBooking(null);
        seedCapturedPayment(bookingId, "50.00", "0.00", "0.10");
        enqueue(bookingId);
        // The freeze row exists but this payout was never flipped (it was not yet visible to the UPDATE).
        transactionTemplate.execute(s -> jdbcTemplate.update(
            "INSERT INTO payment.coach_payout_freezes (coach_id, reason, frozen_at) VALUES (?, 'SAFETY_HOLD it', now())",
            coachId));

        releaseHoldAndDrain(bookingId);

        assertThat(payout(bookingId).getStatus()).isEqualTo(CoachPayoutStatus.FROZEN);
        verify(paymentGateway, never()).transferToCoach(eq(bookingId), any(), any(), any());
        assertThat(outboxRows(bookingId)).as("the message is consumed; reinstatement re-enqueues").isZero();
    }
}
