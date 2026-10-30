package com.softropic.skillars.platform.payment.service;

import com.softropic.skillars.platform.booking.contract.BookingCancelledByParentEvent;
import com.softropic.skillars.platform.booking.contract.PlayerNoShowEvent;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/** feat/payment-forfeit-payouts-and-clawback: forfeits pay the coach, nothing captured pays nothing. */
@ExtendWith(MockitoExtension.class)
class CoachPayoutEnqueueListenerTest {

    @Mock CoachPayoutOutboxSupport coachPayoutOutboxSupport;
    @InjectMocks CoachPayoutEnqueueListener listener;

    private final UUID bookingId = UUID.randomUUID();
    private final UUID coachId = UUID.randomUUID();

    private BookingCancelledByParentEvent parentCancel(boolean captured, boolean refundEligible) {
        return new BookingCancelledByParentEvent(this, bookingId, 1L, coachId, null, 2L, refundEligible,
            new BigDecimal("40.00"), "p@x.test", "c@x.test", Instant.now(), "Europe/Berlin", captured);
    }

    @Test
    void playerNoShow_enqueuesThePayout() {
        listener.onPlayerNoShow(new PlayerNoShowEvent(this, bookingId, 1L, coachId, "c@x.test", "p@x.test", Instant.now(), "UTC"));
        verify(coachPayoutOutboxSupport).enqueuePayout(bookingId, coachId);
    }

    @Test
    void capturedLateParentCancel_enqueuesThePayout() {
        listener.onBookingCancelledByParent(parentCancel(true, false));
        verify(coachPayoutOutboxSupport).enqueuePayout(bookingId, coachId);
    }

    @Test
    void refundedParentCancel_paysNothing() {
        listener.onBookingCancelledByParent(parentCancel(true, true));
        verify(coachPayoutOutboxSupport, never()).enqueuePayout(any(), any());
    }

    @Test
    void uncapturedParentCancel_paysNothing() {
        listener.onBookingCancelledByParent(parentCancel(false, false));
        verify(coachPayoutOutboxSupport, never()).enqueuePayout(any(), any());
    }

    @Test
    void legacyConstructor_isNeverAForfeit() {
        listener.onBookingCancelledByParent(new BookingCancelledByParentEvent(this, bookingId, 1L, coachId, null, 2L,
            false, BigDecimal.TEN, "p@x.test", "c@x.test", Instant.now(), "UTC"));
        verify(coachPayoutOutboxSupport, never()).enqueuePayout(any(), any());
    }
}
