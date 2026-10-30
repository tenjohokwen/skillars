package com.softropic.skillars.platform.payment.service;

import com.softropic.skillars.platform.booking.contract.BookingCancelledByAdminEvent;
import com.softropic.skillars.platform.booking.contract.BookingCancelledByCoachEvent;
import com.softropic.skillars.platform.booking.contract.BookingCancelledByParentEvent;
import com.softropic.skillars.platform.booking.contract.BookingCancelledDueToPauseEvent;
import com.softropic.skillars.platform.booking.contract.CoachNoShowEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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
    @Mock PackRestoreOutboxSupport packRestoreOutboxSupport;
    @Mock PackRefundAllocationService packRefundAllocationService;
    @Mock org.springframework.context.ApplicationEventPublisher eventPublisher;

    @InjectMocks RefundEnqueueListener listener;

    @BeforeEach
    void allocatorReturnsTheComputedAmountByDefault() {
        lenient().when(packRefundAllocationService.allocateSessionRefund(any(), any(), any()))
            .thenAnswer(inv -> inv.getArgument(2));
    }

    // ========== fix/payment-pack-refund-rounding: pack wallet refunds settle on the pack ledger ==========

    @Test
    void packWalletRefund_enqueuesTheAmountSettledOnThePack_notTheComputedShare() {
        UUID packId = UUID.randomUUID();
        // 1000.00 / 3: the cancellation computed the rounded-down 333.33; the pack's last session settles 333.34.
        when(packRefundAllocationService.allocateSessionRefund(packId, BOOKING_ID, SESSION_PRICE))
            .thenReturn(new BigDecimal("333.34"));

        listener.onBookingCancelledByCoach(coachCancelEvent(packId, true));

        verify(refundOutboxSupport).enqueueBookingRefund(
            eq(PARENT_ID), eq(new BigDecimal("333.34")), eq(BOOKING_ID), anyString());
    }

    @Test
    void packWalletRefund_nothingLeftOnThePack_enqueuesNothing() {
        UUID packId = UUID.randomUUID();
        when(packRefundAllocationService.allocateSessionRefund(packId, BOOKING_ID, SESSION_PRICE))
            .thenReturn(BigDecimal.ZERO);

        listener.onCoachNoShow(noShowEvent(packId, true));

        verify(refundOutboxSupport, never()).enqueueBookingRefund(any(), any(), any(), any());
    }

    @Test
    void cardBookingWalletRefund_neverTouchesThePackLedger() {
        listener.onBookingCancelledByCoach(coachCancelEvent(null, true));

        verify(packRefundAllocationService, never()).allocateSessionRefund(any(), any(), any());
        verify(refundOutboxSupport).enqueueBookingRefund(
            eq(PARENT_ID), eq(SESSION_PRICE), eq(BOOKING_ID), anyString());
    }

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

    /** C6: an ACCEPTED booking took no money — a coach cancelling it must not mint wallet credit. */
    @Test
    void onBookingCancelledByCoach_notCaptured_neverEnqueues() {
        listener.onBookingCancelledByCoach(coachCancelEvent(null, false, false));
        listener.onBookingCancelledByCoach(coachCancelEvent(UUID.randomUUID(), true, false));

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
    void onBookingCancelledByAdmin_capturedCreditBased_enqueuesTheAmountPaid() {
        BookingCancelledByAdminEvent event = adminCancelEvent(null, true, false);

        listener.onBookingCancelledByAdmin(event);

        verify(refundOutboxSupport).enqueueBookingRefund(
            eq(PARENT_ID), eq(SESSION_PRICE), eq(BOOKING_ID), anyString());
    }

    /** C2: REQUESTED / ACCEPTED / PAYMENT_PENDING took no money — a refund here would mint free credit. */
    @Test
    void onBookingCancelledByAdmin_notCaptured_neverEnqueues() {
        listener.onBookingCancelledByAdmin(adminCancelEvent(null, false, false));
        listener.onBookingCancelledByAdmin(adminCancelEvent(UUID.randomUUID(), false, true));

        verify(refundOutboxSupport, never()).enqueueBookingRefund(any(), any(), any(), any());
    }

    @Test
    void onBookingCancelledByAdmin_capturedUnexpiredPack_neverEnqueues_theSessionIsRestoredInstead() {
        BookingCancelledByAdminEvent event = adminCancelEvent(UUID.randomUUID(), true, false);

        listener.onBookingCancelledByAdmin(event);

        assertThat(event.isPackRestore()).isTrue();
        verify(refundOutboxSupport, never()).enqueueBookingRefund(any(), any(), any(), any());
    }

    @Test
    void onBookingCancelledByAdmin_capturedExpiredPack_enqueuesAWalletRefund() {
        listener.onBookingCancelledByAdmin(adminCancelEvent(UUID.randomUUID(), true, true));

        verify(refundOutboxSupport).enqueueBookingRefund(
            eq(PARENT_ID), eq(SESSION_PRICE), eq(BOOKING_ID), anyString());
    }

    // ========== fix/payment-session-pack-integrity: pack restores go onto the outbox, BEFORE_COMMIT ==========

    @Test
    void packRestore_enqueuedOncePerEligibleEvent_withTheBookingAsIdempotencyKey() {
        UUID packId = UUID.randomUUID();

        listener.onBookingCancelledByParent(parentEvent(packId, true));
        listener.onBookingCancelledByCoach(coachCancelEvent(packId, false));
        listener.onCoachNoShow(noShowEvent(packId, false));
        listener.onBookingCancelledByAdmin(adminCancelEvent(packId, true, false));
        listener.onBookingCancelledDueToPause(pauseEvent(packId, true, false, BigDecimal.ZERO));

        verify(packRestoreOutboxSupport).enqueuePackRestore(packId, BOOKING_ID, "PARENT_CANCELLATION");
        verify(packRestoreOutboxSupport).enqueuePackRestore(packId, BOOKING_ID, "COACH_CANCELLATION");
        verify(packRestoreOutboxSupport).enqueuePackRestore(packId, BOOKING_ID, "COACH_NO_SHOW");
        verify(packRestoreOutboxSupport).enqueuePackRestore(packId, BOOKING_ID, "ADMIN_CANCELLATION");
        verify(packRestoreOutboxSupport).enqueuePackRestore(packId, BOOKING_ID, "PACK_PAUSE");
        verify(refundOutboxSupport, never()).enqueueBookingRefund(any(), any(), any(), any());
    }

    @Test
    void packRestore_neverForForfeitedUncapturedOrExpired() {
        UUID packId = UUID.randomUUID();

        listener.onBookingCancelledByParent(parentEvent(packId, false));
        listener.onBookingCancelledByCoach(coachCancelEvent(packId, false, false));
        listener.onBookingCancelledByCoach(coachCancelEvent(packId, true));
        listener.onCoachNoShow(noShowEvent(packId, true));
        listener.onBookingCancelledByAdmin(adminCancelEvent(packId, false, false));
        listener.onBookingCancelledDueToPause(pauseEvent(packId, false, false, BigDecimal.ZERO));
        listener.onBookingCancelledDueToPause(pauseEvent(packId, true, true, SESSION_PRICE));

        verify(packRestoreOutboxSupport, never()).enqueuePackRestore(any(), any(), any());
    }

    // ========== fix/payment-session-pack-integrity: pause cancellations ==========

    @Test
    void onBookingCancelledDueToPause_capturedCardBooking_refundsTheAmountPaid() {
        listener.onBookingCancelledDueToPause(pauseEvent(null, true, false, new BigDecimal("40.00")));

        verify(refundOutboxSupport).enqueueBookingRefund(
            eq(PARENT_ID), eq(new BigDecimal("40.00")), eq(BOOKING_ID), eq("Pack pause — full refund"));
    }

    /** fix/audit3-wallet-cashout-integrity: the parent paused — an expired-pack refund is spend-only. */
    @Test
    void onBookingCancelledDueToPause_capturedExpiredPack_refundsTheShareAsSpendOnly() {
        listener.onBookingCancelledDueToPause(pauseEvent(UUID.randomUUID(), true, true, SESSION_PRICE));

        verify(refundOutboxSupport).enqueueSpendOnlyBookingRefund(
            eq(PARENT_ID), eq(SESSION_PRICE), eq(BOOKING_ID), eq("Pack pause — expired pack refund (booking credit only)"));
        verify(refundOutboxSupport, never()).enqueueBookingRefund(any(), any(), any(), any());
    }

    @Test
    void onBookingCancelledDueToPause_notCaptured_returnsNothing() {
        listener.onBookingCancelledDueToPause(pauseEvent(null, false, false, BigDecimal.ZERO));

        verify(refundOutboxSupport, never()).enqueueBookingRefund(any(), any(), any(), any());
        verify(packRestoreOutboxSupport, never()).enqueuePackRestore(any(), any(), any());
    }

    private BookingCancelledDueToPauseEvent pauseEvent(UUID packId, boolean captured, boolean packExpired,
                                                       BigDecimal refundAmount) {
        return new BookingCancelledDueToPauseEvent(
            this, BOOKING_ID, PARENT_ID, COACH_ID, "coach@test.com", "Coach", "Parent",
            Instant.now(), "UTC", packId, captured, packExpired, refundAmount);
    }

    @Test
    void onBookingCancelledByParent_refundEligibleOnAnExpiredPack_refundsTheWallet_throughThePackLedger() {
        UUID packId = UUID.randomUUID();
        when(packRefundAllocationService.allocateSessionRefund(packId, BOOKING_ID, SESSION_PRICE))
            .thenReturn(new BigDecimal("50.01"));

        listener.onBookingCancelledByParent(new BookingCancelledByParentEvent(
            this, BOOKING_ID, PARENT_ID, COACH_ID, packId, 25, true, SESSION_PRICE, "parent@test.com",
            "coach@test.com", Instant.now(), "UTC", true, true));

        // fix/payment-wallet-dispute-integrity (owner decision): SPEND-ONLY credit — bookable, never cashed out.
        verify(refundOutboxSupport).enqueueSpendOnlyBookingRefund(eq(PARENT_ID), eq(new BigDecimal("50.01")),
            eq(BOOKING_ID), eq("Parent cancellation >24h — expired pack refund (booking credit only)"));
        verify(refundOutboxSupport, never()).enqueueBookingRefund(any(), any(), any(), any());
        verify(packRestoreOutboxSupport, never()).enqueuePackRestore(any(), any(), any());
    }

    @Test
    void onBookingCancelledByParent_lateCancelOnAnExpiredPack_isAForfeit_nothingRefunded() {
        UUID packId = UUID.randomUUID();
        listener.onBookingCancelledByParent(new BookingCancelledByParentEvent(
            this, BOOKING_ID, PARENT_ID, COACH_ID, packId, 3, false, SESSION_PRICE, "parent@test.com",
            "coach@test.com", Instant.now(), "UTC", true, true));

        verify(refundOutboxSupport, never()).enqueueBookingRefund(any(), any(), any(), any());
        verify(packRestoreOutboxSupport, never()).enqueuePackRestore(any(), any(), any());
        verify(packRefundAllocationService, never()).allocateSessionRefund(any(), any(), any());
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
        return coachCancelEvent(packId, packExpired, true);
    }

    private BookingCancelledByCoachEvent coachCancelEvent(UUID packId, boolean packExpired, boolean captured) {
        return coachCancelEvent(packId, packExpired, captured, "OTHER_UNEXCUSED");
    }

    private BookingCancelledByCoachEvent coachCancelEvent(UUID packId, boolean packExpired, boolean captured,
                                                          String reason) {
        return new BookingCancelledByCoachEvent(
            this, BOOKING_ID, PARENT_ID, COACH_ID,
            reason, packId, SESSION_PRICE, packExpired, captured,
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

    /**
     * fix/payment-wallet-dispute-integrity: a captured admin cancel whose amount is unknown (0.00) used to
     * enqueue a 0.00 refund that failed on every drain forever. Nothing is enqueued; an admin alert is raised.
     */
    @Test
    void onBookingCancelledByAdmin_capturedButAmountUnknown_enqueuesNothing_andAlerts() {
        listener.onBookingCancelledByAdmin(new BookingCancelledByAdminEvent(
            this, BOOKING_ID, PARENT_ID, COACH_ID, null, BigDecimal.ZERO, true, false,
            "parent@test.com", "coach@test.com", "Coach", BookingCancelledByAdminEvent.REASON_COACH_UNAVAILABLE,
            Instant.now(), "UTC"));

        verify(refundOutboxSupport, never()).enqueueBookingRefund(any(), any(), any(), any());
        var alert = org.mockito.ArgumentCaptor.forClass(
            com.softropic.skillars.platform.payment.contract.event.PaymentIntegrityAlertEvent.class);
        verify(eventPublisher).publishEvent(alert.capture());
        assertThat(alert.getValue().getKind()).isEqualTo(
            com.softropic.skillars.platform.payment.contract.event.PaymentIntegrityAlertEvent.Kind.BOOKING_ACTION_REQUIRED);
        assertThat(alert.getValue().getReferenceId()).isEqualTo(BOOKING_ID.toString());
    }

    /** A coach-side expired-pack refund stays cashable (only the parent's own >24h cancel is spend-only). */
    @Test
    void onBookingCancelledByCoach_expiredPack_refundIsCashable() {
        listener.onBookingCancelledByCoach(coachCancelEvent(UUID.randomUUID(), true));

        verify(refundOutboxSupport, never()).enqueueSpendOnlyBookingRefund(any(), any(), any(), any());
    }

    // ========== fix/audit3-wallet-cashout-integrity: excused coach cancels (owner decision) ==========

    /** Any excused reason (the strike logic's classification): an expired-pack refund is spend-only. */
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"MUTUAL_AGREEMENT", "WEATHER", "HEALTH_MEDICAL",
        "FAMILY_EMERGENCY"})
    void onBookingCancelledByCoach_excusedReason_expiredPack_refundIsSpendOnly(String reason) {
        listener.onBookingCancelledByCoach(coachCancelEvent(UUID.randomUUID(), true, true, reason));

        verify(refundOutboxSupport).enqueueSpendOnlyBookingRefund(eq(PARENT_ID), eq(SESSION_PRICE), eq(BOOKING_ID),
            anyString());
        verify(refundOutboxSupport, never()).enqueueBookingRefund(any(), any(), any(), any());
    }

    /** An unexcused coach cancel is the coach's fault: the expired-pack refund stays cashable. */
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"OTHER_UNEXCUSED", "SCHEDULING_PREFERENCE"})
    void onBookingCancelledByCoach_unexcusedReason_expiredPack_refundIsCashable(String reason) {
        listener.onBookingCancelledByCoach(coachCancelEvent(UUID.randomUUID(), true, true, reason));

        verify(refundOutboxSupport).enqueueBookingRefund(eq(PARENT_ID), eq(SESSION_PRICE), eq(BOOKING_ID), anyString());
        verify(refundOutboxSupport, never()).enqueueSpendOnlyBookingRefund(any(), any(), any(), any());
    }

    /** A card booking's excused-cancel refund is not forced spend-only (the outbox handler splits it). */
    @Test
    void onBookingCancelledByCoach_excused_cardBooking_notForcedSpendOnly() {
        listener.onBookingCancelledByCoach(coachCancelEvent(null, false, true, "MUTUAL_AGREEMENT"));

        verify(refundOutboxSupport).enqueueBookingRefund(eq(PARENT_ID), eq(SESSION_PRICE), eq(BOOKING_ID), anyString());
        verify(refundOutboxSupport, never()).enqueueSpendOnlyBookingRefund(any(), any(), any(), any());
    }

    /** Excused cancel on an unexpired pack: the restore carries the reason, so an expiry meanwhile is spend-only. */
    @Test
    void onBookingCancelledByCoach_excused_packRestore_carriesTheExcusedReason() {
        UUID packId = UUID.randomUUID();
        listener.onBookingCancelledByCoach(coachCancelEvent(packId, false, true, "WEATHER"));

        verify(packRestoreOutboxSupport).enqueuePackRestore(packId, BOOKING_ID,
            PackSessionService.RESTORE_REASON_COACH_EXCUSED_CANCELLATION);
    }

    /**
     * fix/audit3-completion-dispute-integrity: an excused reason picked when cancelling a never-started booking at or
     * after its start is a coach no-show in disguise — the expired-pack refund stays cashable, as a no-show's does.
     */
    @Test
    void onBookingCancelledByCoach_excusedReasonAfterTheStart_expiredPack_refundIsCashable() {
        listener.onBookingCancelledByCoach(new BookingCancelledByCoachEvent(
            this, BOOKING_ID, PARENT_ID, COACH_ID, "WEATHER", UUID.randomUUID(), SESSION_PRICE, true, true,
            "parent@test.com", Instant.now(), "UTC", true));

        verify(refundOutboxSupport).enqueueBookingRefund(eq(PARENT_ID), eq(SESSION_PRICE), eq(BOOKING_ID), anyString());
        verify(refundOutboxSupport, never()).enqueueSpendOnlyBookingRefund(any(), any(), any(), any());
    }

    private BookingCancelledByAdminEvent adminCancelEvent(UUID packId, boolean captured, boolean packExpired) {
        return new BookingCancelledByAdminEvent(
            this, BOOKING_ID, PARENT_ID, COACH_ID, packId, SESSION_PRICE, captured, packExpired,
            "parent@test.com", "coach@test.com", "Coach", BookingCancelledByAdminEvent.REASON_COACH_UNAVAILABLE, Instant.now(), "UTC"
        );
    }
}
