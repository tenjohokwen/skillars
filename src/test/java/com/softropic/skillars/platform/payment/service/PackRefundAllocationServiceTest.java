package com.softropic.skillars.platform.payment.service;

import com.softropic.skillars.infrastructure.persistence.PessimisticLockRetryer;
import com.softropic.skillars.platform.payment.repo.SessionPackPurchase;
import com.softropic.skillars.platform.payment.repo.SessionPackPurchaseRepository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** fix/payment-pack-refund-rounding: pack session refunds settle on the pack's ledger, once per booking. */
@ExtendWith(MockitoExtension.class)
class PackRefundAllocationServiceTest {

    @Mock SessionPackPurchaseRepository repository;
    @Mock PessimisticLockRetryer lockRetryer;

    private PackRefundAllocationService service;
    private final UUID packId = UUID.randomUUID();
    private final UUID bookingId = UUID.randomUUID();

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        service = new PackRefundAllocationService(repository, lockRetryer);
        lenient().when(lockRetryer.withBoundedRetry(anyString(), any(Supplier.class)))
            .thenAnswer(inv -> ((Supplier<?>) inv.getArgument(1)).get());
        lenient().when(repository.findRefundMarkerAmount(bookingId)).thenReturn(Optional.empty());
    }

    private SessionPackPurchase pack(String amount, int sessions, int paid, String paidGross) {
        SessionPackPurchase p = new SessionPackPurchase();
        p.setPurchaseId(packId);
        p.setAmountCharged(new BigDecimal(amount));
        p.setSessionCount(sessions);
        p.setPayoutSessionsAllocated(paid);
        p.setPayoutGrossAllocated(new BigDecimal(paidGross));
        when(repository.findByIdForUpdate(packId)).thenReturn(Optional.of(p));
        return p;
    }

    @Test
    void lastSessionToSettle_isRefundedTheRemainder_notTheRoundedDownShare() {
        SessionPackPurchase p = pack("1000.00", 3, 2, "666.66");

        BigDecimal refunded = service.allocateSessionRefund(packId, bookingId, new BigDecimal("333.33"));

        assertThat(refunded).isEqualByComparingTo("333.34");
        assertThat(p.getRefundSessionsIssued()).isEqualTo(1);
        assertThat(p.getRefundGrossIssued()).isEqualByComparingTo("333.34");
        assertThat(p.settledGross()).isEqualByComparingTo("1000.00");
        verify(repository).insertRefundMarker(bookingId, packId, new BigDecimal("333.34"));
    }

    @Test
    void notTheLastSession_isRefundedTheShare() {
        SessionPackPurchase p = pack("1000.00", 3, 0, "0.00");

        assertThat(service.allocateSessionRefund(packId, bookingId, null)).isEqualByComparingTo("333.33");
        assertThat(p.getRefundSessionsIssued()).isEqualTo(1);
    }

    @Test
    void alreadyRefundedBooking_returnsTheRecordedAmount_andSettlesNothingAgain() {
        SessionPackPurchase p = pack("1000.00", 3, 0, "0.00");
        when(repository.findRefundMarkerAmount(bookingId)).thenReturn(Optional.of(new BigDecimal("333.33")));

        assertThat(service.allocateSessionRefund(packId, bookingId, null)).isEqualByComparingTo("333.33");
        assertThat(p.getRefundSessionsIssued()).isZero();
        verify(repository, never()).insertRefundMarker(any(), any(), any());
    }

    @Test
    void fullySettledPack_refundsNothing() {
        SessionPackPurchase p = pack("1000.00", 3, 3, "1000.00");

        assertThat(service.allocateSessionRefund(packId, bookingId, new BigDecimal("333.33"))).isEqualByComparingTo("0");
        assertThat(p.getRefundSessionsIssued()).isZero();
        verify(repository, never()).insertRefundMarker(any(), any(), any());
    }

    @Test
    void missingPack_refundsTheComputedAmountUntracked() {
        when(repository.findByIdForUpdate(packId)).thenReturn(Optional.empty());

        assertThat(service.allocateSessionRefund(packId, bookingId, new BigDecimal("12.34"))).isEqualByComparingTo("12.34");
        verify(repository, never()).insertRefundMarker(any(), any(), any());
    }

    @Test
    void release_unsettlesExactlyTheRecordedRefund() {
        SessionPackPurchase p = pack("1000.00", 3, 2, "666.66");
        p.recordRefund(new BigDecimal("333.34"));
        when(repository.findRefundMarkerAmount(bookingId)).thenReturn(Optional.of(new BigDecimal("333.34")));

        assertThat(service.releaseSessionRefund(packId, bookingId)).contains(new BigDecimal("333.34"));

        assertThat(p.getRefundSessionsIssued()).isZero();
        assertThat(p.getRefundGrossIssued()).isEqualByComparingTo("0");
        // The coach payout that follows now settles that session at the remainder.
        assertThat(p.nextSettlementValue()).isEqualByComparingTo("333.34");
        verify(repository).deleteRefundMarker(bookingId);
    }

    @Test
    void release_withoutARefundMarker_isANoOp() {
        SessionPackPurchase p = pack("1000.00", 3, 0, "0.00");

        assertThat(service.releaseSessionRefund(packId, bookingId)).isEmpty();
        assertThat(p.getRefundSessionsIssued()).isZero();
        verify(repository, never()).deleteRefundMarker(any());
    }
}
