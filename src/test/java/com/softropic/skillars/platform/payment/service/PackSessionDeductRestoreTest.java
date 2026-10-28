package com.softropic.skillars.platform.payment.service;

import com.softropic.skillars.infrastructure.persistence.PessimisticLockRetryer;
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
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** fix/payment-session-pack-integrity: deductSession refuses a paused pack; restores are once per booking. */
@ExtendWith(MockitoExtension.class)
class PackSessionDeductRestoreTest {

    @Mock SessionPackPurchaseRepository sessionPackPurchaseRepository;
    @Mock ApplicationEventPublisher eventPublisher;
    @Mock PessimisticLockRetryer lockRetryer;

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

    private static SessionPackPurchase pack(int remaining) {
        SessionPackPurchase p = new SessionPackPurchase();
        p.setPurchaseId(PACK_ID);
        p.setParentId(1L);
        p.setRemainingSessions(remaining);
        p.setExpiresAt(Instant.now().plus(30, ChronoUnit.DAYS));
        return p;
    }
}
