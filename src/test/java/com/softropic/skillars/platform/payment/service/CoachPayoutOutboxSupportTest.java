package com.softropic.skillars.platform.payment.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.softropic.skillars.infrastructure.persistence.PessimisticLockRetryer;
import com.softropic.skillars.platform.booking.repo.Booking;
import com.softropic.skillars.platform.booking.repo.BookingRepository;
import com.softropic.skillars.platform.config.service.ConfigService;
import com.softropic.skillars.platform.outbox.service.OutboxService;
import com.softropic.skillars.platform.payment.contract.CoachPayoutStatus;
import com.softropic.skillars.platform.payment.repo.BookingPayment;
import com.softropic.skillars.platform.payment.repo.BookingPaymentRepository;
import com.softropic.skillars.platform.payment.repo.CoachPayout;
import com.softropic.skillars.platform.payment.repo.CoachPayoutFreezeRepository;
import com.softropic.skillars.platform.payment.repo.CoachPayoutRepository;
import com.softropic.skillars.platform.payment.repo.CoachStripeAccountRepository;
import com.softropic.skillars.platform.payment.repo.SessionPackPurchase;
import com.softropic.skillars.platform.payment.repo.SessionPackPurchaseRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * skillars-deferred-106 AC4.2 / AC4.4 / AC15.3: net-split and rate-locked-at-capture.
 * feat/coach-payouts-pack-and-credit: credit- and pack-funded sessions, pack share rounding, freeze.
 */
@ExtendWith(MockitoExtension.class)
class CoachPayoutOutboxSupportTest {

    @Mock CoachPayoutRepository coachPayoutRepository;
    @Mock BookingPaymentRepository bookingPaymentRepository;
    @Mock CoachStripeAccountRepository coachStripeAccountRepository;
    @Mock OutboxService outboxService;
    @Mock ConfigService configService;
    @Mock BookingRepository bookingRepository;
    @Mock SessionPackPurchaseRepository sessionPackPurchaseRepository;
    @Mock CoachPayoutFreezeRepository coachPayoutFreezeRepository;
    @Mock PessimisticLockRetryer lockRetryer;
    @Mock com.softropic.skillars.platform.payment.repo.ChargeDisputeRepository chargeDisputeRepository;

    private CoachPayoutOutboxSupport support;
    private final UUID bookingId = UUID.randomUUID();
    private final UUID coachId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        support = new CoachPayoutOutboxSupport(coachPayoutRepository, bookingPaymentRepository, bookingRepository,
            sessionPackPurchaseRepository, coachPayoutFreezeRepository, lockRetryer,
            coachStripeAccountRepository, outboxService, configService, new ObjectMapper(), chargeDisputeRepository);
        lenient().when(lockRetryer.withBoundedRetry(anyString(), any()))
            .thenAnswer(inv -> ((Supplier<?>) inv.getArgument(1)).get());
        lenient().when(bookingRepository.findById(any())).thenReturn(Optional.empty());
        lenient().when(coachPayoutRepository.existsById(bookingId)).thenReturn(false);
        lenient().when(configService.getString("platform.payment.currency")).thenReturn("eur");
        lenient().when(configService.getBoundedLong(eq("payment.payout.hold_hours"), anyLong(), anyLong(), anyLong()))
            .thenReturn(48L);
        lenient().when(coachStripeAccountRepository.findById(coachId)).thenReturn(Optional.empty());
    }

    private BookingPayment bp(String charged, String commissionRate) {
        BookingPayment b = new BookingPayment();
        b.setBookingId(bookingId);
        b.setStripeCharged(new BigDecimal(charged));
        b.setCreditDebited(BigDecimal.ZERO);
        b.setCommissionRate(commissionRate == null ? null : new BigDecimal(commissionRate));
        b.setStatus("CAPTURED");
        return b;
    }

    private CoachPayout captureSavedRow() {
        ArgumentCaptor<CoachPayout> c = ArgumentCaptor.forClass(CoachPayout.class);
        verify(coachPayoutRepository).save(c.capture());
        return c.getValue();
    }

    @Test
    void fullCardBooking_stampedRate_writesPendingReleaseRowAndEnqueuesWithFutureNotBefore() {
        when(bookingPaymentRepository.findById(bookingId)).thenReturn(Optional.of(bp("50.00", "0.10")));

        support.enqueuePayout(bookingId, coachId);

        CoachPayout row = captureSavedRow();
        assertThat(row.getStatus()).isEqualTo(CoachPayoutStatus.PENDING_RELEASE);
        assertThat(row.getGrossAmount()).isEqualByComparingTo("50.00");
        assertThat(row.getCommissionAmount()).isEqualByComparingTo("5.00");
        assertThat(row.getNetAmount()).isEqualByComparingTo("45.00");
        assertThat(row.getReleaseAfter()).isAfter(Instant.now().plusSeconds(47 * 3600));
        verify(outboxService).enqueue(eq(CoachPayoutOutboxSupport.AGGREGATE_TYPE_TRANSFER), any(), any(Instant.class));
        verify(outboxService).requestDrainAfterCommit();
    }

    @Test
    void nullStampedRate_fallsBackToLivePlatformRate() {
        when(bookingPaymentRepository.findById(bookingId)).thenReturn(Optional.of(bp("50.00", null)));
        when(configService.getString("platform.commission.rate")).thenReturn("0.20");

        support.enqueuePayout(bookingId, coachId);

        assertThat(captureSavedRow().getNetAmount()).isEqualByComparingTo("40.00"); // 50 - 50*0.20
    }

    @Test
    void rateChangedBetweenCaptureAndCompletion_usesStampedRate_notLive() {
        when(bookingPaymentRepository.findById(bookingId)).thenReturn(Optional.of(bp("50.00", "0.08")));
        // live rate is now 0.20 — must be ignored
        lenient().when(configService.getString("platform.commission.rate")).thenReturn("0.20");

        support.enqueuePayout(bookingId, coachId);

        assertThat(captureSavedRow().getNetAmount()).isEqualByComparingTo("46.00"); // 50 - 50*0.08
    }

    @Test
    void mixedCreditAndCard_paysBothParts_inOneRow() {
        // price 50: 10 covered by wallet credit, 40 on card; rate 0.10 -> coach net = 50 * 0.90
        BookingPayment b = bp("40.00", "0.10");
        b.setCreditDebited(new BigDecimal("10.00"));
        when(bookingPaymentRepository.findById(bookingId)).thenReturn(Optional.of(b));

        support.enqueuePayout(bookingId, coachId);

        CoachPayout row = captureSavedRow();
        assertThat(row.getStatus()).isEqualTo(CoachPayoutStatus.PENDING_RELEASE);
        assertThat(row.getGrossAmount()).isEqualByComparingTo("50.00");
        assertThat(row.getCommissionAmount()).isEqualByComparingTo("5.00");
        assertThat(row.getNetAmount()).isEqualByComparingTo("45.00");
        verify(outboxService).enqueue(eq(CoachPayoutOutboxSupport.AGGREGATE_TYPE_TRANSFER), any(), any(Instant.class));
    }

    @Test
    void fullyCreditFunded_isPaidLikeCard_atTheStampedRate() {
        BookingPayment b = bp("0.00", "0.15");
        b.setCreditDebited(new BigDecimal("60.00"));
        when(bookingPaymentRepository.findById(bookingId)).thenReturn(Optional.of(b));

        support.enqueuePayout(bookingId, coachId);

        CoachPayout row = captureSavedRow();
        assertThat(row.getStatus()).isEqualTo(CoachPayoutStatus.PENDING_RELEASE);
        assertThat(row.getGrossAmount()).isEqualByComparingTo("60.00");
        assertThat(row.getNetAmount()).isEqualByComparingTo("51.00");
        assertThat(row.getSessionPackPurchaseId()).isNull();
    }

    @Test
    void nothingCharged_writesCancelledRow_noEnqueue() {
        when(bookingPaymentRepository.findById(bookingId)).thenReturn(Optional.of(bp("0.00", "0.10")));

        support.enqueuePayout(bookingId, coachId);

        CoachPayout row = captureSavedRow();
        assertThat(row.getStatus()).isEqualTo(CoachPayoutStatus.CANCELLED);
        assertThat(row.getLastError()).isEqualTo(CoachPayoutOutboxSupport.REASON_NOTHING_CHARGED);
        verify(outboxService, never()).enqueue(any(), any(), any());
        verify(outboxService, never()).enqueue(any(), any());
    }

    // ── pack-funded sessions ──

    private final UUID packId = UUID.randomUUID();

    private SessionPackPurchase pack(String amountCharged, Integer sessions, String rate) {
        SessionPackPurchase p = new SessionPackPurchase();
        p.setPurchaseId(packId);
        p.setAmountCharged(amountCharged == null ? null : new BigDecimal(amountCharged));
        p.setSessionCount(sessions);
        p.setCommissionRate(rate == null ? null : new BigDecimal(rate));
        return p;
    }

    private void packBooking(UUID id) {
        Booking b = new Booking();
        b.setSessionPackPurchaseId(packId);
        lenient().when(bookingRepository.findById(id)).thenReturn(Optional.of(b));
        lenient().when(coachPayoutRepository.existsById(id)).thenReturn(false);
    }

    @Test
    void packSessions_shareIsAmountOverSessionsRoundedDown_lastTakesRemainder_sumIsExact() {
        SessionPackPurchase p = pack("100.00", 3, "0.10");
        when(sessionPackPurchaseRepository.findByIdForUpdate(packId)).thenReturn(Optional.of(p));
        List<UUID> bookings = List.of(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
        bookings.forEach(this::packBooking);

        bookings.forEach(id -> support.enqueuePayout(id, coachId));

        ArgumentCaptor<CoachPayout> c = ArgumentCaptor.forClass(CoachPayout.class);
        verify(coachPayoutRepository, org.mockito.Mockito.times(3)).save(c.capture());
        List<BigDecimal> gross = new ArrayList<>();
        c.getAllValues().forEach(r -> {
            assertThat(r.getStatus()).isEqualTo(CoachPayoutStatus.PENDING_RELEASE);
            assertThat(r.getSessionPackPurchaseId()).isEqualTo(packId);
            gross.add(r.getGrossAmount());
        });
        assertThat(gross).extracting(BigDecimal::toPlainString).containsExactly("33.33", "33.33", "33.34");
        assertThat(gross.stream().reduce(BigDecimal.ZERO, BigDecimal::add)).isEqualByComparingTo("100.00");
        // commission at the rate stamped at PACK PURCHASE: 33.34 * 0.10 = 3.33 (HALF_UP) -> net 30.01
        assertThat(c.getAllValues().get(2).getNetAmount()).isEqualByComparingTo("30.01");
        assertThat(p.getPayoutSessionsAllocated()).isEqualTo(3);
        assertThat(p.getPayoutGrossAllocated()).isEqualByComparingTo("100.00");
    }

    @Test
    void packFullyAllocated_neverPaysBeyondAmountCharged() {
        SessionPackPurchase p = pack("100.00", 2, "0.10");
        p.setPayoutSessionsAllocated(2);
        p.setPayoutGrossAllocated(new BigDecimal("100.00"));
        when(sessionPackPurchaseRepository.findByIdForUpdate(packId)).thenReturn(Optional.of(p));
        packBooking(bookingId);

        support.enqueuePayout(bookingId, coachId);

        CoachPayout row = captureSavedRow();
        assertThat(row.getStatus()).isEqualTo(CoachPayoutStatus.CANCELLED);
        assertThat(row.getLastError()).isEqualTo(CoachPayoutOutboxSupport.REASON_PACK_FULLY_ALLOCATED);
        assertThat(row.getNetAmount()).isEqualByComparingTo("0");
        verify(outboxService, never()).enqueue(any(), any(), any());
    }

    @Test
    void packWithoutStampedAmount_isNotPaidAGuess() {
        when(sessionPackPurchaseRepository.findByIdForUpdate(packId)).thenReturn(Optional.of(pack(null, 5, "0.10")));
        packBooking(bookingId);

        support.enqueuePayout(bookingId, coachId);

        CoachPayout row = captureSavedRow();
        assertThat(row.getStatus()).isEqualTo(CoachPayoutStatus.CANCELLED);
        assertThat(row.getLastError()).isEqualTo(CoachPayoutOutboxSupport.REASON_PACK_AMOUNT_UNKNOWN);
        verify(outboxService, never()).enqueue(any(), any(), any());
    }

    @Test
    void packPayoutCommittedWhileWaitingForThePackLock_isNoOp_andDoesNotAllocate() {
        SessionPackPurchase p = pack("100.00", 3, "0.10");
        when(sessionPackPurchaseRepository.findByIdForUpdate(packId)).thenReturn(Optional.of(p));
        packBooking(bookingId);
        // first check (before the lock) sees nothing, the re-check under the lock sees the committed row
        when(coachPayoutRepository.existsById(bookingId)).thenReturn(false, true);

        support.enqueuePayout(bookingId, coachId);

        verify(coachPayoutRepository, never()).save(any());
        assertThat(p.getPayoutSessionsAllocated()).isZero();
    }

    @Test
    void packWithNullStampedRate_fallsBackToLiveRate() {
        when(sessionPackPurchaseRepository.findByIdForUpdate(packId)).thenReturn(Optional.of(pack("90.00", 3, null)));
        when(configService.getString("platform.commission.rate")).thenReturn("0.20");
        packBooking(bookingId);

        support.enqueuePayout(bookingId, coachId);

        assertThat(captureSavedRow().getNetAmount()).isEqualByComparingTo("24.00"); // 30 - 30*0.20
    }

    @Test
    void coachUnderSafetyHold_payoutRecordedFrozen_notEnqueued() {
        when(bookingPaymentRepository.findById(bookingId)).thenReturn(Optional.of(bp("50.00", "0.10")));
        when(coachPayoutFreezeRepository.isFrozenForShare(coachId)).thenReturn(true);

        support.enqueuePayout(bookingId, coachId);

        CoachPayout row = captureSavedRow();
        assertThat(row.getStatus()).isEqualTo(CoachPayoutStatus.FROZEN);
        assertThat(row.getNetAmount()).isEqualByComparingTo("45.00");
        verify(outboxService, never()).enqueue(any(), any(), any());
        verify(outboxService, never()).enqueue(any(), any());
    }

    // fix/payment-payouts-disputes-webhooks: a dispute opened before completion freezes the payout from the start.
    @Test
    void openInAppDispute_payoutRecordedFrozenWithDisputeFlag_notEnqueued() {
        when(bookingPaymentRepository.findById(bookingId)).thenReturn(Optional.of(bp("50.00", "0.10")));
        when(coachPayoutRepository.existsOpenDisputeForBooking(bookingId)).thenReturn(true);

        support.enqueuePayout(bookingId, coachId);

        CoachPayout row = captureSavedRow();
        assertThat(row.getStatus()).isEqualTo(CoachPayoutStatus.FROZEN);
        assertThat(row.isDisputeFrozen()).isTrue();
        assertThat(row.getLastError()).isEqualTo(CoachPayoutAdjustmentService.REASON_DISPUTE_OPEN);
        verify(outboxService, never()).enqueue(any(), any(), any());
    }

    @Test
    void openCardDisputeOnTheFundingCharge_payoutRecordedFrozenWithChargebackFlag() {
        when(bookingPaymentRepository.findById(bookingId)).thenReturn(Optional.of(bp("50.00", "0.10")));
        when(chargeDisputeRepository.findFundingPaymentIntents(bookingId)).thenReturn(java.util.List.of("pi_1"));
        when(chargeDisputeRepository.findStatusesByPaymentIntentIds(java.util.List.of("pi_1")))
            .thenReturn(java.util.List.of(com.softropic.skillars.platform.payment.repo.ChargeDispute.STATUS_OPEN));

        support.enqueuePayout(bookingId, coachId);

        CoachPayout row = captureSavedRow();
        assertThat(row.getStatus()).isEqualTo(CoachPayoutStatus.FROZEN);
        assertThat(row.isChargebackFrozen()).isTrue();
        verify(outboxService, never()).enqueue(any(), any(), any());
    }

    private static com.softropic.skillars.platform.payment.repo.ChargeDispute lost(String fraction, String amount) {
        var d = new com.softropic.skillars.platform.payment.repo.ChargeDispute();
        d.setStripeDisputeId("dp_1");
        d.setPaymentIntentId("pi_1");
        d.setStatus(com.softropic.skillars.platform.payment.repo.ChargeDispute.STATUS_LOST);
        d.setAmount(new BigDecimal(amount));
        d.setPayoutLossFraction(fraction == null ? null : new BigDecimal(fraction));
        return d;
    }

    private void lostDisputeOnFundingCharge(com.softropic.skillars.platform.payment.repo.ChargeDispute d) {
        when(chargeDisputeRepository.findFundingPaymentIntents(bookingId)).thenReturn(java.util.List.of("pi_1"));
        when(chargeDisputeRepository.findStatusesByPaymentIntentIds(java.util.List.of("pi_1")))
            .thenReturn(java.util.List.of(com.softropic.skillars.platform.payment.repo.ChargeDispute.STATUS_LOST));
        when(chargeDisputeRepository.findByPaymentIntentIdInAndStatus(java.util.List.of("pi_1"),
            com.softropic.skillars.platform.payment.repo.ChargeDispute.STATUS_LOST)).thenReturn(java.util.List.of(d));
    }

    /** fix/payment-dispute-credit-trace-and-lost-dispute: no more HOLD forever — a whole loss cancels the payout. */
    @Test
    void lostCardDisputeForAllOfTheCharge_payoutWrittenCancelled_noTransfer() {
        when(bookingPaymentRepository.findById(bookingId)).thenReturn(Optional.of(bp("50.00", "0.10")));
        lostDisputeOnFundingCharge(lost("1.000000", "50.00"));

        support.enqueuePayout(bookingId, coachId);

        CoachPayout row = captureSavedRow();
        assertThat(row.getStatus()).isEqualTo(CoachPayoutStatus.CANCELLED);
        assertThat(row.getLastError()).isEqualTo(CoachPayoutOutboxSupport.REASON_CHARGEBACK_LOST);
        verify(outboxService, never()).enqueue(any(), any(), any());
    }

    /** A partial loss cuts the payout by the dispute's recorded fraction and it releases normally. */
    @Test
    void lostCardDisputeForPartOfTheCharge_payoutCutProRata_andEnqueued() {
        when(bookingPaymentRepository.findById(bookingId)).thenReturn(Optional.of(bp("50.00", "0.10")));
        lostDisputeOnFundingCharge(lost("0.400000", "20.00"));

        support.enqueuePayout(bookingId, coachId);

        CoachPayout row = captureSavedRow();
        assertThat(row.getStatus()).isEqualTo(CoachPayoutStatus.PENDING_RELEASE);
        // 40% of the 50.00 card portion = 20.00 of 50.00 -> the coach bears 40% of the 45.00 net.
        assertThat(row.getNetAmount()).isEqualByComparingTo("27.00");
        assertThat(row.getGrossAmount()).isEqualByComparingTo("30.00");
        assertThat(row.getCommissionAmount()).isEqualByComparingTo("3.00");
        verify(outboxService).enqueue(eq(CoachPayoutOutboxSupport.AGGREGATE_TYPE_TRANSFER), any(), any(Instant.class));
    }

    /** A dispute lost before V199 recorded its fraction falls back to disputed / charged. */
    @Test
    void lostCardDisputeWithoutRecordedFraction_fallsBackToDisputedOverCharged() {
        when(bookingPaymentRepository.findById(bookingId)).thenReturn(Optional.of(bp("50.00", "0.10")));
        lostDisputeOnFundingCharge(lost(null, "25.00"));
        when(chargeDisputeRepository.sumChargedByPaymentIntent("pi_1")).thenReturn(new BigDecimal("100.00"));

        support.enqueuePayout(bookingId, coachId);

        CoachPayout row = captureSavedRow();
        assertThat(row.getNetAmount()).isEqualByComparingTo("33.75");
        assertThat(row.getStatus()).isEqualTo(CoachPayoutStatus.PENDING_RELEASE);
    }

    @Test
    void rowAlreadyExists_isNoOp() {
        when(coachPayoutRepository.existsById(bookingId)).thenReturn(true);

        support.enqueuePayout(bookingId, coachId);

        verify(coachPayoutRepository, never()).save(any());
        verify(outboxService, never()).enqueue(any(), any(), any());
    }

    // ── fix/audit3-wallet-cashout-integrity: longer payout hold for a new coach ──────────────

    private void newCoachRule(long releasedCount, long newCoachHours) {
        when(configService.getBoundedLong(com.softropic.skillars.platform.config.service.ConfigBounds.PAYOUT_NEW_COACH_RELEASED_COUNT))
            .thenReturn(5L);
        when(configService.getBoundedLong(com.softropic.skillars.platform.config.service.ConfigBounds.PAYOUT_NEW_COACH_HOLD_HOURS))
            .thenReturn(newCoachHours);
        when(coachPayoutRepository.countByCoachIdAndReleasedAtIsNotNull(coachId)).thenReturn(releasedCount);
    }

    @Test
    void newCoach_fewerReleasedPayoutsThanTheThreshold_heldTheLongerHold() {
        newCoachRule(4L, 168L);
        when(bookingPaymentRepository.findById(bookingId)).thenReturn(Optional.of(bp("50.00", "0.10")));

        support.enqueuePayout(bookingId, coachId);

        CoachPayout row = captureSavedRow();
        assertThat(row.getReleaseAfter()).isAfter(Instant.now().plusSeconds(167 * 3600));
        verify(outboxService).enqueue(eq(CoachPayoutOutboxSupport.AGGREGATE_TYPE_TRANSFER), any(),
            eq(row.getReleaseAfter()));
    }

    @Test
    void establishedCoach_atTheThreshold_normalHold() {
        newCoachRule(5L, 168L);
        when(bookingPaymentRepository.findById(bookingId)).thenReturn(Optional.of(bp("50.00", "0.10")));

        support.enqueuePayout(bookingId, coachId);

        CoachPayout row = captureSavedRow();
        assertThat(row.getReleaseAfter()).isBefore(Instant.now().plusSeconds(49 * 3600));
    }

    @Test
    void payoutHold_neverShorterThanTheNormalHold_andCountsDown() {
        newCoachRule(2L, 24L);
        CoachPayoutOutboxSupport.PayoutHold hold =
            CoachPayoutOutboxSupport.resolveHold(configService, coachPayoutRepository, coachId);
        assertThat(hold.newCoach()).isFalse();
        assertThat(hold.appliedHoldHours()).isEqualTo(48L);

        CoachPayoutOutboxSupport.PayoutHold longer = new CoachPayoutOutboxSupport.PayoutHold(48L, 168L, 5L, 2L);
        assertThat(longer.newCoach()).isTrue();
        assertThat(longer.newCoachPayoutsRemaining()).isEqualTo(3L);
        assertThat(longer.appliedHoldHours()).isEqualTo(168L);
        assertThat(new CoachPayoutOutboxSupport.PayoutHold(48L, 168L, 0L, 0L).newCoach()).isFalse();
    }
}
