package com.softropic.skillars.platform.payment.service;

import com.softropic.skillars.platform.booking.contract.CoachNoShowEvent;
import com.softropic.skillars.platform.payment.contract.event.PaymentIntegrityAlertEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** S-07: the conflict-zone split's settlement listener. */
@ExtendWith(MockitoExtension.class)
class CoachNoShowSplitListenerTest {

    private static final Long PARENT_ID = 1001L;
    private static final UUID COACH_ID = UUID.randomUUID();
    private static final UUID BOOKING_ID = UUID.randomUUID();

    @Mock CoachPayoutOutboxSupport coachPayoutOutboxSupport;
    @Mock RefundOutboxSupport refundOutboxSupport;
    @Mock ApplicationEventPublisher eventPublisher;
    @InjectMocks CoachNoShowSplitListener listener;

    private CoachNoShowEvent event(UUID packId, boolean split) {
        return new CoachNoShowEvent(this, BOOKING_ID, PARENT_ID, COACH_ID, packId, new BigDecimal("50.00"), false,
            "p@test.com", Instant.now(), "UTC", split);
    }

    @Test
    void aPlainNoShow_isIgnored() {
        listener.onCoachNoShow(event(null, false));

        verifyNoInteractions(coachPayoutOutboxSupport, refundOutboxSupport);
    }

    @Test
    void aCardSplit_paysTheCoachAndRefundsThePayersHalf() {
        when(coachPayoutOutboxSupport.enqueueSplitPayout(BOOKING_ID, COACH_ID, new BigDecimal("50.00")))
            .thenReturn(new BigDecimal("25.00"));

        listener.onCoachNoShow(event(null, true));

        verify(refundOutboxSupport).enqueueBookingRefund(eq(PARENT_ID), eq(new BigDecimal("25.00")), eq(BOOKING_ID), anyString());
        verify(refundOutboxSupport, never()).enqueueSpendOnlyBookingRefund(any(), any(), any(), anyString());
    }

    @Test
    void aPackSplit_refundsSpendOnlyCredit() {
        when(coachPayoutOutboxSupport.enqueueSplitPayout(BOOKING_ID, COACH_ID, new BigDecimal("50.00")))
            .thenReturn(new BigDecimal("25.00"));

        listener.onCoachNoShow(event(UUID.randomUUID(), true));

        verify(refundOutboxSupport).enqueueSpendOnlyBookingRefund(eq(PARENT_ID), eq(new BigDecimal("25.00")), eq(BOOKING_ID), anyString());
    }

    @Test
    void anUnknownRefund_raisesAnAdminAlert_andEnqueuesNothing() {
        when(coachPayoutOutboxSupport.enqueueSplitPayout(BOOKING_ID, COACH_ID, new BigDecimal("50.00")))
            .thenReturn(BigDecimal.ZERO);

        listener.onCoachNoShow(event(null, true));

        verify(eventPublisher).publishEvent(any(PaymentIntegrityAlertEvent.class));
        verify(refundOutboxSupport, never()).enqueueBookingRefund(any(), any(), any(), anyString());
    }
}
