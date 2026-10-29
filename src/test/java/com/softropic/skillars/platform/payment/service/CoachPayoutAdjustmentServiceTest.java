package com.softropic.skillars.platform.payment.service;

import com.softropic.skillars.platform.payment.contract.CoachPayoutStatus;
import com.softropic.skillars.platform.payment.repo.CoachPayout;
import com.softropic.skillars.platform.payment.repo.CoachPayoutDebt;
import com.softropic.skillars.platform.payment.repo.CoachPayoutDebtRepository;
import com.softropic.skillars.platform.payment.repo.CoachPayoutFreezeRepository;
import com.softropic.skillars.platform.payment.repo.CoachPayoutRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** fix/payment-payouts-disputes-webhooks items 2 + 3: freeze reasons and pro-rata refunds on a coach payout. */
@ExtendWith(MockitoExtension.class)
class CoachPayoutAdjustmentServiceTest {

    @Mock CoachPayoutRepository coachPayoutRepository;
    @Mock CoachPayoutFreezeRepository coachPayoutFreezeRepository;
    @Mock CoachPayoutOutboxSupport coachPayoutOutboxSupport;
    @Mock CoachPayoutDebtRepository debtRepository;
    @Mock org.springframework.context.ApplicationEventPublisher eventPublisher;

    private CoachPayoutAdjustmentService service;
    private final UUID bookingId = UUID.randomUUID();
    private final UUID coachId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new CoachPayoutAdjustmentService(coachPayoutRepository, coachPayoutFreezeRepository,
            coachPayoutOutboxSupport, new CoachPayoutDebtService(debtRepository,
                new io.micrometer.core.instrument.simple.SimpleMeterRegistry(), eventPublisher));
    }

    private CoachPayout row(String status) {
        CoachPayout r = new CoachPayout();
        r.setBookingId(bookingId);
        r.setCoachId(coachId);
        r.setGrossAmount(new BigDecimal("50.00"));
        r.setCommissionAmount(new BigDecimal("5.00"));
        r.setNetAmount(new BigDecimal("45.00"));
        r.setReleaseAfter(Instant.now().plusSeconds(3600));
        r.setStatus(status);
        return r;
    }

    @Test
    void freezeForDispute_pending_becomesFrozen_hold_staysHold_released_untouched() {
        CoachPayout pending = row(CoachPayoutStatus.PENDING_RELEASE);
        when(coachPayoutRepository.findByIdForUpdate(bookingId)).thenReturn(Optional.of(pending));
        assertThat(service.freezeForDispute(bookingId)).isEqualTo(CoachPayoutStatus.FROZEN);
        assertThat(pending.isDisputeFrozen()).isTrue();

        CoachPayout hold = row(CoachPayoutStatus.HOLD);
        when(coachPayoutRepository.findByIdForUpdate(bookingId)).thenReturn(Optional.of(hold));
        assertThat(service.freezeForDispute(bookingId)).isEqualTo(CoachPayoutStatus.HOLD);
        assertThat(hold.isDisputeFrozen()).isTrue();

        CoachPayout released = row(CoachPayoutStatus.RELEASED);
        when(coachPayoutRepository.findByIdForUpdate(bookingId)).thenReturn(Optional.of(released));
        assertThat(service.freezeForDispute(bookingId)).isEqualTo(CoachPayoutStatus.RELEASED);
        assertThat(released.isDisputeFrozen()).isFalse();
    }

    @Test
    void clearingOneReason_whileAnotherStands_keepsItFrozen() {
        CoachPayout r = row(CoachPayoutStatus.FROZEN);
        r.setDisputeFrozen(true);
        r.setChargebackFrozen(true);
        when(coachPayoutRepository.findByIdForUpdate(bookingId)).thenReturn(Optional.of(r));

        service.clearDisputeFreeze(bookingId);

        assertThat(r.getStatus()).isEqualTo(CoachPayoutStatus.FROZEN);
        assertThat(r.getLastError()).isEqualTo(CoachPayoutAdjustmentService.REASON_CHARGEBACK_OPEN);
        verify(coachPayoutOutboxSupport, never()).enqueueTransfer(any(), any());

        service.clearChargebackFreeze(r);

        assertThat(r.getStatus()).isEqualTo(CoachPayoutStatus.PENDING_RELEASE);
        // Not before the original hold.
        verify(coachPayoutOutboxSupport).enqueueTransfer(bookingId, r.getReleaseAfter());
    }

    @Test
    void lastReasonCleared_butSafetyHoldActive_staysFrozen() {
        CoachPayout r = row(CoachPayoutStatus.FROZEN);
        r.setChargebackFrozen(true);
        when(coachPayoutFreezeRepository.isFrozenForShare(coachId)).thenReturn(true);

        service.clearChargebackFreeze(r);

        assertThat(r.getStatus()).isEqualTo(CoachPayoutStatus.FROZEN);
        assertThat(r.getLastError()).isEqualTo(CoachPayoutFreezeService.REASON_SAFETY_HOLD);
        verify(coachPayoutOutboxSupport, never()).enqueueTransfer(any(), any());
    }

    @Test
    void partialRefund_onPending_cutsNetGrossCommissionProRata_staysPending() {
        CoachPayout r = row(CoachPayoutStatus.PENDING_RELEASE);

        assertThat(service.applyRefund(r, new BigDecimal("20.00"), new BigDecimal("50.00"), "T"))
            .isEqualTo(CoachPayoutAdjustmentService.RefundOutcome.REDUCED);

        assertThat(r.getStatus()).isEqualTo(CoachPayoutStatus.PENDING_RELEASE);
        assertThat(r.getNetAmount()).isEqualByComparingTo("27.00");
        assertThat(r.getGrossAmount()).isEqualByComparingTo("30.00");
        assertThat(r.getCommissionAmount()).isEqualByComparingTo("3.00");
    }

    @Test
    void fullRefund_onPending_cancels() {
        CoachPayout r = row(CoachPayoutStatus.HOLD);

        assertThat(service.applyRefund(r, new BigDecimal("50.00"), new BigDecimal("50.00"), "T"))
            .isEqualTo(CoachPayoutAdjustmentService.RefundOutcome.CANCELLED);
        assertThat(r.getStatus()).isEqualTo(CoachPayoutStatus.CANCELLED);
    }

    @Test
    void partialRefund_onReleased_enqueuesTheSameShare_cappedAtWhatIsLeft() {
        CoachPayout r = row(CoachPayoutStatus.RELEASED);
        assertThat(service.applyRefund(r, new BigDecimal("20.00"), new BigDecimal("50.00"), "T"))
            .isEqualTo(CoachPayoutAdjustmentService.RefundOutcome.REVERSAL_ENQUEUED);
        verify(coachPayoutOutboxSupport).enqueueReversal(bookingId, new BigDecimal("18.00"));

        CoachPayout partlyReversed = row(CoachPayoutStatus.RELEASED);
        partlyReversed.setReversedAmount(new BigDecimal("40.00"));
        service.applyRefund(partlyReversed, new BigDecimal("50.00"), new BigDecimal("50.00"), "T");
        verify(coachPayoutOutboxSupport).enqueueReversal(eq(bookingId), eq(new BigDecimal("5.00")));
    }

    @Test
    void terminalOrZero_isNoOp() {
        assertThat(service.applyRefund(row(CoachPayoutStatus.CANCELLED), BigDecimal.TEN, new BigDecimal("50.00"), "T"))
            .isEqualTo(CoachPayoutAdjustmentService.RefundOutcome.NONE);
        assertThat(service.applyRefund(row(CoachPayoutStatus.PENDING_RELEASE), BigDecimal.ZERO, new BigDecimal("50.00"), "T"))
            .isEqualTo(CoachPayoutAdjustmentService.RefundOutcome.NONE);
    }

    // ── feat/payment-forfeit-payouts-and-clawback: payInFull (UPHOLD_COACH) ──

    @Test
    void payInFull_noRow_computesANewPayout() {
        CoachPayout created = row(CoachPayoutStatus.PENDING_RELEASE);
        when(coachPayoutRepository.findByIdForUpdate(bookingId)).thenReturn(Optional.empty());
        when(coachPayoutRepository.findById(bookingId)).thenReturn(Optional.of(created));

        assertThat(service.payInFull(bookingId, coachId)).isEqualByComparingTo("45.00");
        verify(coachPayoutOutboxSupport).enqueuePayout(bookingId, coachId);
    }

    @Test
    void payInFull_cancelledWithANet_isReinstatedAndReleasedNow() {
        CoachPayout cancelled = row(CoachPayoutStatus.CANCELLED);
        cancelled.setDisputeFrozen(true);
        when(coachPayoutRepository.findByIdForUpdate(bookingId)).thenReturn(Optional.of(cancelled));
        when(coachPayoutFreezeRepository.isFrozenForShare(coachId)).thenReturn(false);

        assertThat(service.payInFull(bookingId, coachId)).isEqualByComparingTo("45.00");
        assertThat(cancelled.getStatus()).isEqualTo(CoachPayoutStatus.PENDING_RELEASE);
        assertThat(cancelled.isDisputeFrozen()).isFalse();
        verify(coachPayoutOutboxSupport).enqueueTransfer(eq(bookingId), any());
        verify(coachPayoutOutboxSupport, never()).enqueuePayout(any(), any());
    }

    @Test
    void payInFull_cancelledUnderASafetyHold_staysFrozen() {
        CoachPayout cancelled = row(CoachPayoutStatus.CANCELLED);
        when(coachPayoutRepository.findByIdForUpdate(bookingId)).thenReturn(Optional.of(cancelled));
        when(coachPayoutFreezeRepository.isFrozenForShare(coachId)).thenReturn(true);

        service.payInFull(bookingId, coachId);

        assertThat(cancelled.getStatus()).isEqualTo(CoachPayoutStatus.FROZEN);
        verify(coachPayoutOutboxSupport, never()).enqueueTransfer(any(), any());
    }

    @Test
    void payInFull_cancelledWithNothingComputed_isReplacedByAFreshPayout() {
        CoachPayout skipped = row(CoachPayoutStatus.CANCELLED);
        skipped.setNetAmount(BigDecimal.ZERO);
        CoachPayout fresh = row(CoachPayoutStatus.PENDING_RELEASE);
        when(coachPayoutRepository.findByIdForUpdate(bookingId)).thenReturn(Optional.of(skipped));
        when(coachPayoutRepository.findById(bookingId)).thenReturn(Optional.of(fresh));

        assertThat(service.payInFull(bookingId, coachId)).isEqualByComparingTo("45.00");
        verify(coachPayoutRepository).delete(skipped);
        verify(coachPayoutOutboxSupport).enqueuePayout(bookingId, coachId);
    }

    // ── fix/payment-pack-clawback-and-expired-cancel: payInFull never settles a pack session twice ──

    @Test
    void payInFull_packSessionAlreadySettledAtZero_isLeftAlone_neverReallocated() {
        CoachPayout settled = row(CoachPayoutStatus.CANCELLED);
        settled.setSessionPackPurchaseId(UUID.randomUUID());
        settled.setGrossAmount(BigDecimal.ZERO);
        settled.setCommissionAmount(BigDecimal.ZERO);
        settled.setNetAmount(BigDecimal.ZERO);
        settled.setLastError(CoachPayoutOutboxSupport.REASON_NOTHING_CHARGED);
        settled.setDisputeFrozen(true);
        when(coachPayoutRepository.findByIdForUpdate(bookingId)).thenReturn(Optional.of(settled));

        assertThat(service.payInFull(bookingId, coachId)).isEqualByComparingTo("0");
        verify(coachPayoutRepository, never()).delete(any());
        verify(coachPayoutOutboxSupport, never()).enqueuePayout(any(), any());
        assertThat(settled.getStatus()).isEqualTo(CoachPayoutStatus.CANCELLED);
        assertThat(settled.isDisputeFrozen()).isFalse();
    }

    @Test
    void payInFull_packSkipThatNeverAllocated_isRecomputed() {
        for (String reason : java.util.List.of(CoachPayoutOutboxSupport.REASON_PACK_FULLY_ALLOCATED,
                CoachPayoutOutboxSupport.REASON_PACK_AMOUNT_UNKNOWN)) {
            org.mockito.Mockito.reset(coachPayoutRepository, coachPayoutOutboxSupport);
            CoachPayout skipped = row(CoachPayoutStatus.CANCELLED);
            skipped.setSessionPackPurchaseId(UUID.randomUUID());
            skipped.setNetAmount(BigDecimal.ZERO);
            skipped.setLastError(reason);
            when(coachPayoutRepository.findByIdForUpdate(bookingId)).thenReturn(Optional.of(skipped));
            when(coachPayoutRepository.findById(bookingId)).thenReturn(Optional.empty());

            service.payInFull(bookingId, coachId);

            verify(coachPayoutRepository).delete(skipped);
            verify(coachPayoutOutboxSupport).enqueuePayout(bookingId, coachId);
        }
    }

    @Test
    void payInFull_cardBookingNothingCharged_isStillRecomputedFromItsPaymentRow() {
        CoachPayout nothing = row(CoachPayoutStatus.CANCELLED);
        nothing.setNetAmount(BigDecimal.ZERO);
        nothing.setLastError(CoachPayoutOutboxSupport.REASON_NOTHING_CHARGED);
        CoachPayout fresh = row(CoachPayoutStatus.PENDING_RELEASE);
        when(coachPayoutRepository.findByIdForUpdate(bookingId)).thenReturn(Optional.of(nothing));
        when(coachPayoutRepository.findById(bookingId)).thenReturn(Optional.of(fresh));

        assertThat(service.payInFull(bookingId, coachId)).isEqualByComparingTo("45.00");
        verify(coachPayoutRepository).delete(nothing);
        verify(coachPayoutOutboxSupport).enqueuePayout(bookingId, coachId);
    }
    // ── fix/payment-payout-recovery ──

    /** A 50.00 / 45.00-net payout as first computed, already cut to {@code net}. */
    private CoachPayout cutRow(String status, String net, String gross) {
        CoachPayout r = row(status);
        r.setOriginalGrossAmount(new BigDecimal("50.00"));
        r.setOriginalCommissionAmount(new BigDecimal("5.00"));
        r.setOriginalNetAmount(new BigDecimal("45.00"));
        r.setNetAmount(new BigDecimal(net));
        r.setGrossAmount(new BigDecimal(gross));
        r.setCommissionAmount(new BigDecimal(gross).subtract(new BigDecimal(net)));
        return r;
    }

    // M6: two 50% refunds on a pending payout cost the coach the whole net, as they would after release.
    @Test
    void stackedPartialRefunds_onPending_takeTheirShareOfTheOriginalNet_notOfTheCutOne() {
        CoachPayout r = row(CoachPayoutStatus.PENDING_RELEASE);
        r.setOriginalNetAmount(new BigDecimal("45.00"));
        r.setOriginalGrossAmount(new BigDecimal("50.00"));
        r.setOriginalCommissionAmount(new BigDecimal("5.00"));

        service.applyRefund(r, new BigDecimal("25.00"), new BigDecimal("50.00"), "T1");
        assertThat(r.getNetAmount()).isEqualByComparingTo("22.50");

        assertThat(service.applyRefund(r, new BigDecimal("25.00"), new BigDecimal("50.00"), "T2"))
            .as("22.50 share of the original 45.00 = all that is left")
            .isEqualTo(CoachPayoutAdjustmentService.RefundOutcome.CANCELLED);
        assertThat(r.getStatus()).isEqualTo(CoachPayoutStatus.CANCELLED);
    }

    // H1: UPHOLD_COACH on a pending payout an earlier refund cut — restored to the full net and released.
    @Test
    void payInFull_cutPendingPayout_isRestoredToTheFullNet() {
        CoachPayout r = cutRow(CoachPayoutStatus.PENDING_RELEASE, "27.00", "30.00");
        when(coachPayoutRepository.findByIdForUpdate(bookingId)).thenReturn(Optional.of(r));

        assertThat(service.payInFull(bookingId, coachId)).isEqualByComparingTo("45.00");
        assertThat(r.getNetAmount()).isEqualByComparingTo("45.00");
        assertThat(r.getGrossAmount()).isEqualByComparingTo("50.00");
        assertThat(r.getCommissionAmount()).isEqualByComparingTo("5.00");
        assertThat(r.getStatus()).isEqualTo(CoachPayoutStatus.PENDING_RELEASE);
        verify(coachPayoutOutboxSupport, never()).enqueueTopUp(any(), any());
    }

    // H1: UPHOLD_COACH after a FULL_CREDIT reversed the released payout — a top-up transfer of the whole net.
    @Test
    void payInFull_reversedPayout_enqueuesATopUpOfTheWholeNet() {
        CoachPayout r = row(CoachPayoutStatus.REVERSED);
        r.setReversedAmount(new BigDecimal("45.00"));
        r.setStripeTransferId("tr_1");
        when(coachPayoutRepository.findByIdForUpdate(bookingId)).thenReturn(Optional.of(r));

        assertThat(service.payInFull(bookingId, coachId)).isEqualByComparingTo("45.00");
        verify(coachPayoutOutboxSupport).enqueueTopUp(bookingId, new BigDecimal("45.00"));
    }

    // H1: released after a pending cut (27.00), then partly reversed (9.00) — tops up to 45.00.
    @Test
    void payInFull_releasedShortOfTheFullNet_topsUpTheDifference() {
        CoachPayout r = cutRow(CoachPayoutStatus.RELEASED, "27.00", "30.00");
        r.setReversedAmount(new BigDecimal("9.00"));
        when(coachPayoutRepository.findByIdForUpdate(bookingId)).thenReturn(Optional.of(r));

        service.payInFull(bookingId, coachId);

        verify(coachPayoutOutboxSupport).enqueueTopUp(bookingId, new BigDecimal("27.00"));
    }

    @Test
    void payInFull_releasedWithTheFullNet_noTopUp() {
        CoachPayout r = row(CoachPayoutStatus.RELEASED);
        when(coachPayoutRepository.findByIdForUpdate(bookingId)).thenReturn(Optional.of(r));

        assertThat(service.payInFull(bookingId, coachId)).isEqualByComparingTo("45.00");
        verify(coachPayoutOutboxSupport, never()).enqueueTopUp(any(), any());
    }

    // M5 + H1: a reversal Stripe refused left the money with the coach as debt — UPHOLD_COACH waives the debt
    // (no top-up needed for it); only what the coach already repaid is topped up.
    @Test
    void payInFull_reversalRecordedAsDebt_waivesTheDebt_topsUpOnlyWhatWasRepaid() {
        CoachPayout r = row(CoachPayoutStatus.REVERSED);
        r.setReversedAmount(new BigDecimal("45.00"));
        r.setStripeTransferId("tr_1");
        CoachPayoutDebt debt = CoachPayoutDebt.open(coachId, bookingId, new BigDecimal("45.00"), "COACH_BALANCE_INSUFFICIENT");
        debt.recover(new BigDecimal("10.00"));
        when(coachPayoutRepository.findByIdForUpdate(bookingId)).thenReturn(Optional.of(r));
        when(debtRepository.findOpenByBookingIdForUpdate(bookingId)).thenReturn(java.util.List.of(debt));

        service.payInFull(bookingId, coachId);

        assertThat(debt.getStatus()).isEqualTo(CoachPayoutDebt.STATUS_WAIVED);
        assertThat(r.getStatus()).isEqualTo(CoachPayoutStatus.RELEASED);
        assertThat(r.getReversedAmount()).isEqualByComparingTo("10.00");
        verify(coachPayoutOutboxSupport).enqueueTopUp(bookingId, new BigDecimal("10.00"));
    }

    @Test
    void payInFull_legacyReversalFailedWithATransfer_backToReleased() {
        CoachPayout r = row(CoachPayoutStatus.REVERSAL_FAILED);
        r.setStripeTransferId("tr_1");
        when(coachPayoutRepository.findByIdForUpdate(bookingId)).thenReturn(Optional.of(r));

        assertThat(service.payInFull(bookingId, coachId)).isEqualByComparingTo("45.00");
        assertThat(r.getStatus()).isEqualTo(CoachPayoutStatus.RELEASED);
        verify(coachPayoutOutboxSupport, never()).enqueueTopUp(any(), any());
    }
}
