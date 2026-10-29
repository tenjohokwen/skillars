package com.softropic.skillars.platform.payment.service;

import com.softropic.skillars.platform.payment.contract.CoachPayoutStatus;
import com.softropic.skillars.platform.payment.repo.CoachPayout;
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

    private CoachPayoutAdjustmentService service;
    private final UUID bookingId = UUID.randomUUID();
    private final UUID coachId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new CoachPayoutAdjustmentService(coachPayoutRepository, coachPayoutFreezeRepository,
            coachPayoutOutboxSupport);
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
}
