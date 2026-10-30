package com.softropic.skillars.platform.payment.service;

import com.softropic.skillars.infrastructure.persistence.PessimisticLockRetryer;
import com.softropic.skillars.platform.marketplace.contract.CoachProfileStatus;
import com.softropic.skillars.platform.marketplace.repo.CoachProfile;
import com.softropic.skillars.platform.marketplace.repo.CoachProfileRepository;
import com.softropic.skillars.platform.payment.contract.exception.PaymentGatewayException;
import com.softropic.skillars.platform.payment.repo.SessionPackPurchase;
import com.softropic.skillars.platform.payment.repo.SessionPackPurchaseRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** fix/payment-session-pack-integrity: deductSession refuses a paused pack; restores are once per booking. */
@ExtendWith(MockitoExtension.class)
class PackSessionDeductRestoreTest {

    @Mock SessionPackPurchaseRepository sessionPackPurchaseRepository;
    @Mock ApplicationEventPublisher eventPublisher;
    @Mock PessimisticLockRetryer lockRetryer;
    @Mock PackRefundAllocationService packRefundAllocationService;
    @Mock CreditWalletService creditWalletService;
    @Mock com.softropic.skillars.platform.payment.repo.ParentCreditLedgerRepository ledgerRepository;
    @Mock CoachProfileRepository coachProfileRepository;

    @InjectMocks PackSessionService packSessionService;

    private static final UUID PACK_ID = UUID.randomUUID();
    private static final UUID BOOKING_ID = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        lenient().when(lockRetryer.withBoundedRetry(anyString(), any()))
            .thenAnswer(inv -> ((java.util.function.Supplier<?>) inv.getArgument(1)).get());
    }

    @Test
    void deductSession_pausedPack_refused_noDeduction() {
        SessionPackPurchase pack = pack(3);
        pack.setPausedUntil(Instant.now().plus(5, ChronoUnit.DAYS));
        when(sessionPackPurchaseRepository.findByIdForUpdate(PACK_ID)).thenReturn(Optional.of(pack));

        assertThatThrownBy(() -> packSessionService.deductSession(PACK_ID))
            .isInstanceOf(PaymentGatewayException.class)
            .hasMessageContaining("payment.packPaused");
        assertThat(pack.getRemainingSessions()).isEqualTo(3);
        verify(sessionPackPurchaseRepository, never()).save(any());
    }

    @Test
    void deductSession_pauseOver_deducts() {
        SessionPackPurchase pack = pack(3);
        pack.setPausedUntil(Instant.now().minus(1, ChronoUnit.DAYS));
        when(sessionPackPurchaseRepository.findByIdForUpdate(PACK_ID)).thenReturn(Optional.of(pack));

        packSessionService.deductSession(PACK_ID);

        assertThat(pack.getRemainingSessions()).isEqualTo(2);
    }

    /** Decision 8: expiry is a request-time rule — a session booked inside validity may be settled after it. */
    @Test
    void deductSession_expiredSinceTheRequest_stillDeducts() {
        SessionPackPurchase pack = pack(3);
        pack.setExpiresAt(Instant.now().minus(1, ChronoUnit.DAYS));
        when(sessionPackPurchaseRepository.findByIdForUpdate(PACK_ID)).thenReturn(Optional.of(pack));

        packSessionService.deductSession(PACK_ID);

        assertThat(pack.getRemainingSessions()).isEqualTo(2);
    }

    @Test
    void restoreSessionForBooking_firstDelivery_claimsTheMarkerAndRestores() {
        SessionPackPurchase pack = pack(2);
        when(sessionPackPurchaseRepository.findByIdForUpdate(PACK_ID)).thenReturn(Optional.of(pack));
        when(sessionPackPurchaseRepository.insertRestoreMarker(BOOKING_ID, PACK_ID, "COACH_CANCELLATION")).thenReturn(1);

        packSessionService.restoreSessionForBooking(PACK_ID, BOOKING_ID, "COACH_CANCELLATION");

        assertThat(pack.getRemainingSessions()).isEqualTo(3);
        verify(sessionPackPurchaseRepository).save(pack);
    }

    @Test
    void restoreSessionForBooking_redelivery_isANoOp() {
        SessionPackPurchase pack = pack(3);
        when(sessionPackPurchaseRepository.findByIdForUpdate(PACK_ID)).thenReturn(Optional.of(pack));
        when(sessionPackPurchaseRepository.insertRestoreMarker(BOOKING_ID, PACK_ID, "COACH_CANCELLATION")).thenReturn(0);

        packSessionService.restoreSessionForBooking(PACK_ID, BOOKING_ID, "COACH_CANCELLATION");

        assertThat(pack.getRemainingSessions()).isEqualTo(3);
        verify(sessionPackPurchaseRepository, never()).save(any());
    }

    @Test
    void restoreSessionForBooking_packGone_noThrow_noMarker() {
        when(sessionPackPurchaseRepository.findByIdForUpdate(PACK_ID)).thenReturn(Optional.empty());

        packSessionService.restoreSessionForBooking(PACK_ID, BOOKING_ID, "PACK_PAUSE");

        verify(sessionPackPurchaseRepository, never()).insertRestoreMarker(any(), any(), any());
    }

    /** Item 7: refunding every session of a 200.00 / 3 pack must not return 200.01. */
    @Test
    void perSessionShare_roundsDown_soAllSharesNeverExceedTheCharge() {
        SessionPackPurchase pack = pack(3);
        pack.setAmountCharged(new BigDecimal("200.00"));
        pack.setSessionCount(3);
        pack.setPricePerSession(new BigDecimal("66.67"));

        assertThat(pack.perSessionShare()).isEqualByComparingTo("66.66");
        assertThat(pack.perSessionShare().multiply(BigDecimal.valueOf(3))).isLessThanOrEqualTo(pack.getAmountCharged());
    }

    @Test
    void perSessionShare_legacyRowWithoutStamp_fallsBackToPricePerSession() {
        SessionPackPurchase pack = pack(3);
        pack.setPricePerSession(new BigDecimal("30.00"));

        assertThat(pack.perSessionShare()).isEqualByComparingTo("30.00");
    }

    // ---- fix/payment-pack-integrity ----------------------------------------------------------

    /** A pause scheduled for later does not block the pack before it starts (H1). */
    @Test
    void deductSession_pauseScheduledForLater_deductsToday() {
        SessionPackPurchase pack = pack(3);
        pack.setPausedFrom(Instant.now().plus(10, ChronoUnit.DAYS));
        pack.setPausedUntil(Instant.now().plus(24, ChronoUnit.DAYS));
        when(sessionPackPurchaseRepository.findByIdForUpdate(PACK_ID)).thenReturn(Optional.of(pack));

        packSessionService.deductSession(PACK_ID);

        assertThat(pack.getRemainingSessions()).isEqualTo(2);
    }

    @Test
    void deductSession_insideTheStoredPauseWindow_refused() {
        SessionPackPurchase pack = pack(3);
        pack.setPausedFrom(Instant.now().minus(1, ChronoUnit.DAYS));
        pack.setPausedUntil(Instant.now().plus(5, ChronoUnit.DAYS));
        when(sessionPackPurchaseRepository.findByIdForUpdate(PACK_ID)).thenReturn(Optional.of(pack));

        assertThatThrownBy(() -> packSessionService.deductSession(PACK_ID))
            .hasMessageContaining("payment.packPaused");
    }

    @Test
    void deductSession_disputeFrozen_refused() {
        SessionPackPurchase pack = pack(3);
        pack.setDisputeFrozenAt(Instant.now());
        when(sessionPackPurchaseRepository.findByIdForUpdate(PACK_ID)).thenReturn(Optional.of(pack));

        assertThatThrownBy(() -> packSessionService.deductSession(PACK_ID))
            .isInstanceOf(PaymentGatewayException.class)
            .hasMessageContaining("payment.packFrozen");
        assertThat(pack.getRemainingSessions()).isEqualTo(3);
    }

    /** M3/M4: an UPHOLD_COACH clawback re-deducts even from a paused pack. */
    @Test
    void deductSessionForClawback_pausedPack_stillDeducts() {
        SessionPackPurchase pack = pack(2);
        pack.setPausedUntil(Instant.now().plus(30, ChronoUnit.DAYS));

        assertThat(packSessionService.deductSessionForClawback(pack)).isTrue();

        assertThat(pack.getRemainingSessions()).isEqualTo(1);
        verify(sessionPackPurchaseRepository).save(pack);
    }

    @Test
    void deductSessionForClawback_noneLeft_returnsFalse() {
        SessionPackPurchase pack = pack(0);

        assertThat(packSessionService.deductSessionForClawback(pack)).isFalse();
        verify(sessionPackPurchaseRepository, never()).save(any());
    }

    /** L1: a coach-side restore reaching a pack that expired meanwhile is refunded to the wallet. */
    @Test
    void restoreSessionForBooking_packExpiredSinceTheCancel_coachCancellation_refundsToWallet() {
        SessionPackPurchase pack = pack(0);
        pack.setExpiresAt(Instant.now().minus(1, ChronoUnit.HOURS));
        when(sessionPackPurchaseRepository.findByIdForUpdate(PACK_ID)).thenReturn(Optional.of(pack));
        when(packRefundAllocationService.allocateSessionRefund(eq(PACK_ID), eq(BOOKING_ID), any()))
            .thenReturn(new BigDecimal("20.00"));

        packSessionService.restoreSessionForBooking(PACK_ID, BOOKING_ID, "COACH_CANCELLATION");

        assertThat(pack.getRemainingSessions()).isZero();
        verify(sessionPackPurchaseRepository, never()).insertRestoreMarker(any(), any(), any());
        verify(creditWalletService).writeLedgerEntry(eq(1L), eq(new BigDecimal("20.00")), eq("BOOKING_REFUND"), eq(BOOKING_ID), anyString(),
            isNull(), isNull(), eq(BigDecimal.ZERO));
    }

    /**
     * fix/audit3-wallet-cashout-integrity: a parent's own cancellation whose pack expired before the restore ran
     * used to restore the session to the expired pack — worthless. It is refunded as SPEND-ONLY wallet credit.
     */
    @Test
    void restoreSessionForBooking_packExpiredSinceTheCancel_parentSideReasons_refundSpendOnly() {
        for (String reason : List.of("PARENT_CANCELLATION", "PACK_PAUSE",
                PackSessionService.RESTORE_REASON_COACH_EXCUSED_CANCELLATION)) {
            org.mockito.Mockito.clearInvocations(creditWalletService);
            SessionPackPurchase pack = pack(0);
            pack.setExpiresAt(Instant.now().minus(1, ChronoUnit.HOURS));
            when(sessionPackPurchaseRepository.findByIdForUpdate(PACK_ID)).thenReturn(Optional.of(pack));
            when(packRefundAllocationService.allocateSessionRefund(eq(PACK_ID), eq(BOOKING_ID), any()))
                .thenReturn(new BigDecimal("20.00"));

            packSessionService.restoreSessionForBooking(PACK_ID, BOOKING_ID, reason);

            assertThat(pack.getRemainingSessions()).as(reason).isZero();
            verify(creditWalletService).writeLedgerEntry(eq(1L), eq(new BigDecimal("20.00")), eq("BOOKING_REFUND"),
                eq(BOOKING_ID), anyString(), isNull(), isNull(), eq(new BigDecimal("20.00")));
        }
        verify(sessionPackPurchaseRepository, never()).insertRestoreMarker(any(), any(), any());
    }

    /** An unexpired pack still gets the session back for a parent cancellation. */
    @Test
    void restoreSessionForBooking_parentCancellation_unexpiredPack_restores() {
        SessionPackPurchase pack = pack(0);
        when(sessionPackPurchaseRepository.findByIdForUpdate(PACK_ID)).thenReturn(Optional.of(pack));
        when(sessionPackPurchaseRepository.insertRestoreMarker(BOOKING_ID, PACK_ID, "PARENT_CANCELLATION")).thenReturn(1);

        packSessionService.restoreSessionForBooking(PACK_ID, BOOKING_ID, "PARENT_CANCELLATION");

        assertThat(pack.getRemainingSessions()).isEqualTo(1);
        verifyNoInteractions(creditWalletService);
    }

    @Test
    void restoreSessionForBooking_packRevokedForSuspension_refundsToWallet() {
        SessionPackPurchase pack = pack(0);
        pack.setRevokedAt(Instant.now());
        pack.setRevokedReason(SessionPackPurchase.REVOKED_COACH_SUSPENDED);
        when(sessionPackPurchaseRepository.findByIdForUpdate(PACK_ID)).thenReturn(Optional.of(pack));
        when(packRefundAllocationService.allocateSessionRefund(eq(PACK_ID), eq(BOOKING_ID), any()))
            .thenReturn(new BigDecimal("20.00"));

        packSessionService.restoreSessionForBooking(PACK_ID, BOOKING_ID, "ADMIN_CANCELLATION");

        assertThat(pack.getRemainingSessions()).isZero();
        verify(creditWalletService).writeLedgerEntry(eq(1L), eq(new BigDecimal("20.00")), eq("BOOKING_REFUND"), eq(BOOKING_ID), anyString(),
            isNull(), isNull(), eq(BigDecimal.ZERO));
    }

    @Test
    void restoreSessionForBooking_packRevokedByLostChargeback_neitherRestoredNorRefunded() {
        SessionPackPurchase pack = pack(0);
        pack.setRevokedAt(Instant.now());
        pack.setRevokedReason(SessionPackPurchase.REVOKED_CHARGEBACK_LOST);
        when(sessionPackPurchaseRepository.findByIdForUpdate(PACK_ID)).thenReturn(Optional.of(pack));

        packSessionService.restoreSessionForBooking(PACK_ID, BOOKING_ID, "COACH_CANCELLATION");

        assertThat(pack.getRemainingSessions()).isZero();
        verify(sessionPackPurchaseRepository, never()).insertRestoreMarker(any(), any(), any());
        verifyNoInteractions(creditWalletService, packRefundAllocationService);
    }

    @Test
    void freezeLiftAndRevoke_followTheDisputeOnThePacksCharge() {
        SessionPackPurchase pack = pack(4);
        pack.setStripePaymentIntentId("pi_pack");
        when(sessionPackPurchaseRepository.findByStripePaymentIntentId("pi_pack")).thenReturn(Optional.of(pack));
        when(sessionPackPurchaseRepository.findByIdForUpdate(PACK_ID)).thenReturn(Optional.of(pack));

        assertThat(packSessionService.freezeForDispute("pi_pack")).isTrue();
        assertThat(pack.isDisputeFrozen()).isTrue();
        assertThat(packSessionService.freezeForDispute("pi_pack")).isFalse();

        assertThat(packSessionService.liftDisputeFreeze("pi_pack")).isTrue();
        assertThat(pack.isDisputeFrozen()).isFalse();

        packSessionService.freezeForDispute("pi_pack");
        assertThat(packSessionService.revokeForCharge("pi_pack", SessionPackPurchase.REVOKED_CHARGEBACK_LOST)).isEqualTo(4);
        assertThat(pack.getRemainingSessions()).isZero();
        assertThat(pack.isRevoked()).isTrue();
        assertThat(pack.getRevokedReason()).isEqualTo("CHARGEBACK_LOST");
        assertThat(pack.isDisputeFrozen()).isFalse();
        assertThat(packSessionService.revokeForCharge("pi_pack", SessionPackPurchase.REVOKED_CHARGEBACK_LOST)).isEqualTo(-1);
    }

    @Test
    void freezeForDispute_chargeThatBoughtNoPack_isANoOp() {
        when(sessionPackPurchaseRepository.findByStripePaymentIntentId("pi_booking")).thenReturn(Optional.empty());

        assertThat(packSessionService.freezeForDispute("pi_booking")).isFalse();
        verify(sessionPackPurchaseRepository, never()).save(any());
    }

    /** H3: a suspended coach's unused sessions are refunded at the locked price, the last one taking the rest. */
    @Test
    void refundUnusedSessionsForSuspendedCoach_refundsRemainingAtTheLockedShare_andRevokes() {
        UUID coachId = UUID.randomUUID();
        CoachProfile coach = new CoachProfile();
        coach.setStatus(CoachProfileStatus.SUSPENDED);
        when(coachProfileRepository.findById(coachId)).thenReturn(Optional.of(coach));
        SessionPackPurchase pack = pack(2);
        pack.setAmountCharged(new BigDecimal("100.00"));
        pack.setSessionCount(3);
        pack.recordPayoutAllocation(new BigDecimal("33.33")); // one session already played and paid
        pack.setStripePaymentIntentId("pi_pack");
        when(sessionPackPurchaseRepository.findUnrevokedPackIdsWithSessionsByCoachId(coachId)).thenReturn(List.of(PACK_ID));
        when(sessionPackPurchaseRepository.findByIdForUpdate(PACK_ID)).thenReturn(Optional.of(pack));

        assertThat(packSessionService.refundUnusedSessionsForSuspendedCoach(coachId)).isEqualTo(1);

        assertThat(pack.getRemainingSessions()).isZero();
        assertThat(pack.getRevokedReason()).isEqualTo(SessionPackPurchase.REVOKED_COACH_SUSPENDED);
        assertThat(pack.getRefundSessionsIssued()).isEqualTo(2);
        assertThat(pack.settledGross()).isEqualByComparingTo("100.00");
        verify(creditWalletService).writeLedgerEntry(eq(1L), eq(new BigDecimal("66.67")), eq("BOOKING_REFUND"), eq(PACK_ID), anyString(),
            eq("pi_pack"), eq(new BigDecimal("66.67")), eq(BigDecimal.ZERO));
    }

    @Test
    void refundUnusedSessionsForSuspendedCoach_skipsFrozenAndExpiredPacks() {
        UUID coachId = UUID.randomUUID();
        UUID frozenId = UUID.randomUUID();
        UUID expiredId = UUID.randomUUID();
        CoachProfile coach = new CoachProfile();
        coach.setStatus(CoachProfileStatus.SUSPENDED);
        when(coachProfileRepository.findById(coachId)).thenReturn(Optional.of(coach));
        SessionPackPurchase frozen = pack(2);
        frozen.setDisputeFrozenAt(Instant.now());
        SessionPackPurchase expired = pack(2);
        expired.setExpiresAt(Instant.now().minus(1, ChronoUnit.DAYS));
        when(sessionPackPurchaseRepository.findUnrevokedPackIdsWithSessionsByCoachId(coachId))
            .thenReturn(List.of(frozenId, expiredId));
        when(sessionPackPurchaseRepository.findByIdForUpdate(frozenId)).thenReturn(Optional.of(frozen));
        when(sessionPackPurchaseRepository.findByIdForUpdate(expiredId)).thenReturn(Optional.of(expired));

        assertThat(packSessionService.refundUnusedSessionsForSuspendedCoach(coachId)).isZero();

        assertThat(frozen.getRemainingSessions()).isEqualTo(2);
        assertThat(expired.getRemainingSessions()).isEqualTo(2);
        verifyNoInteractions(creditWalletService);
    }

    @Test
    void refundUnusedSessionsForSuspendedCoach_coachReinstatedMeanwhile_isANoOp() {
        UUID coachId = UUID.randomUUID();
        CoachProfile coach = new CoachProfile();
        coach.setStatus(CoachProfileStatus.ACTIVE);
        when(coachProfileRepository.findById(coachId)).thenReturn(Optional.of(coach));

        assertThat(packSessionService.refundUnusedSessionsForSuspendedCoach(coachId)).isZero();
        verify(sessionPackPurchaseRepository, never()).findUnrevokedPackIdsWithSessionsByCoachId(any());
    }

    private static SessionPackPurchase pack(int remaining) {
        SessionPackPurchase p = new SessionPackPurchase();
        p.setPurchaseId(PACK_ID);
        p.setParentId(1L);
        p.setRemainingSessions(remaining);
        p.setExpiresAt(Instant.now().plus(30, ChronoUnit.DAYS));
        return p;
    }
}
