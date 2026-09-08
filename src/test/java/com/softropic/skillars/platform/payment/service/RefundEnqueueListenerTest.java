package com.softropic.skillars.platform.payment.service;

import com.softropic.skillars.platform.booking.contract.BookingCancelledByAdminEvent;
import com.softropic.skillars.platform.booking.contract.BookingCancelledByCoachEvent;
import com.softropic.skillars.platform.booking.contract.BookingCancelledByParentEvent;
import com.softropic.skillars.platform.booking.contract.CoachNoShowEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * skillars-deferred-101 AC4: unit tests for RefundEnqueueListener enqueue guard conditions.
 *
 * <p>Tests the exact 6 guard conditions that gate when refunds are enqueued:
 * 1. Parent cancel: credit-based (null pack) && refund-eligible (>24h)
 * 2. Coach cancel pack-expired: expired pack OR credit-based
 * 3. Coach no-show pack-expired: expired pack OR credit-based
 * 4. Admin cancel: credit-based (null pack) only
 *
 * <p>Mutation test: removing any guard condition should fail at least one test.
 */
@ExtendWith(MockitoExtension.class)
class RefundEnqueueListenerTest {

    @Mock RefundOutboxSupport refundOutboxSupport;

    @InjectMocks RefundEnqueueListener listener;

    private static final Long PARENT_ID = 1001L;
    private static final UUID COACH_ID = UUID.randomUUID();
    private static final UUID BOOKING_ID = UUID.randomUUID();
    private static final BigDecimal SESSION_PRICE = new BigDecimal("50.00");

    // ========== Parent events ==========

    @Test
    void onBookingCancelledByParent_creditBasedRefundEligible_enqueues() {
        BookingCancelledByParentEvent event = parentEvent(null, true);

        listener.onBookingCancelledByParent(event);

        verify(refundOutboxSupport).enqueueBookingRefund(
            eq(PARENT_ID), eq(SESSION_PRICE), eq(BOOKING_ID), anyString());
    }

    @Test
    void onBookingCancelledByParent_creditBasedNotRefundEligible_neverEnqueues() {
        BookingCancelledByParentEvent event = parentEvent(null, false);

        listener.onBookingCancelledByParent(event);

        verify(refundOutboxSupport, never()).enqueueBookingRefund(any(), any(), any(), any());
    }

    @Test
    void onBookingCancelledByParent_packBasedRefundEligible_neverEnqueues() {
        UUID packId = UUID.randomUUID();
        BookingCancelledByParentEvent event = parentEvent(packId, true);

        listener.onBookingCancelledByParent(event);

        verify(refundOutboxSupport, never()).enqueueBookingRefund(any(), any(), any(), any());
    }

    // ========== Coach cancel events ==========

    @Test
    void onBookingCancelledByCoach_creditBasedNullPack_enqueues() {
        BookingCancelledByCoachEvent event = coachCancelEvent(null, false);

        listener.onBookingCancelledByCoach(event);

        verify(refundOutboxSupport).enqueueBookingRefund(
            eq(PARENT_ID), eq(SESSION_PRICE), eq(BOOKING_ID), anyString());
    }

    @Test
    void onBookingCancelledByCoach_packExpired_enqueues() {
        UUID packId = UUID.randomUUID();
        BookingCancelledByCoachEvent event = coachCancelEvent(packId, true);

        listener.onBookingCancelledByCoach(event);

        verify(refundOutboxSupport).enqueueBookingRefund(
            eq(PARENT_ID), eq(SESSION_PRICE), eq(BOOKING_ID), anyString());
    }

    @Test
    void onBookingCancelledByCoach_packActiveNotExpired_neverEnqueues() {
        UUID packId = UUID.randomUUID();
        BookingCancelledByCoachEvent event = coachCancelEvent(packId, false);

        listener.onBookingCancelledByCoach(event);

        verify(refundOutboxSupport, never()).enqueueBookingRefund(any(), any(), any(), any());
    }

    // ========== Coach no-show events ==========

    @Test
    void onCoachNoShow_creditBasedNullPack_enqueues() {
        CoachNoShowEvent event = noShowEvent(null, false);

        listener.onCoachNoShow(event);

        verify(refundOutboxSupport).enqueueBookingRefund(
            eq(PARENT_ID), eq(SESSION_PRICE), eq(BOOKING_ID), anyString());
    }

    @Test
    void onCoachNoShow_packExpired_enqueues() {
        UUID packId = UUID.randomUUID();
        CoachNoShowEvent event = noShowEvent(packId, true);

        listener.onCoachNoShow(event);

        verify(refundOutboxSupport).enqueueBookingRefund(
            eq(PARENT_ID), eq(SESSION_PRICE), eq(BOOKING_ID), anyString());
    }

    @Test
    void onCoachNoShow_packActiveNotExpired_neverEnqueues() {
        UUID packId = UUID.randomUUID();
        CoachNoShowEvent event = noShowEvent(packId, false);

        listener.onCoachNoShow(event);

        verify(refundOutboxSupport, never()).enqueueBookingRefund(any(), any(), any(), any());
    }

    // ========== Admin cancel events ==========

    @Test
    void onBookingCancelledByAdmin_creditBased_enqueues() {
        BookingCancelledByAdminEvent event = adminCancelEvent(null);

        listener.onBookingCancelledByAdmin(event);

        verify(refundOutboxSupport).enqueueBookingRefund(
            eq(PARENT_ID), eq(SESSION_PRICE), eq(BOOKING_ID), anyString());
    }

    @Test
    void onBookingCancelledByAdmin_packBased_neverEnqueues() {
        UUID packId = UUID.randomUUID();
        BookingCancelledByAdminEvent event = adminCancelEvent(packId);

        listener.onBookingCancelledByAdmin(event);

        verify(refundOutboxSupport, never()).enqueueBookingRefund(any(), any(), any(), any());
    }

    private BookingCancelledByParentEvent parentEvent(UUID packId, boolean refundEligible) {
        return new BookingCancelledByParentEvent(
            this, BOOKING_ID, PARENT_ID, COACH_ID,
            packId, refundEligible ? 25 : 0, refundEligible,
            SESSION_PRICE, "parent@test.com", "coach@test.com",
            Instant.now(), "UTC"
        );
    }

    private BookingCancelledByCoachEvent coachCancelEvent(UUID packId, boolean packExpired) {
        return new BookingCancelledByCoachEvent(
            this, BOOKING_ID, PARENT_ID, COACH_ID,
            "MUTUAL_AGREEMENT", packId, SESSION_PRICE, packExpired,
            "parent@test.com", Instant.now(), "UTC"
        );
    }

    private CoachNoShowEvent noShowEvent(UUID packId, boolean packExpired) {
        return new CoachNoShowEvent(
            this, BOOKING_ID, PARENT_ID, COACH_ID,
            packId, SESSION_PRICE, packExpired,
            "parent@test.com", Instant.now(), "UTC"
        );
    }

    private BookingCancelledByAdminEvent adminCancelEvent(UUID packId) {
        return new BookingCancelledByAdminEvent(
            this, BOOKING_ID, PARENT_ID, COACH_ID, packId, SESSION_PRICE
        );
    }
}
