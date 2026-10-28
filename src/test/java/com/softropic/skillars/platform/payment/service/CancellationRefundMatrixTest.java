package com.softropic.skillars.platform.payment.service;

import com.softropic.skillars.platform.booking.contract.BookingCancelledByCoachEvent;
import com.softropic.skillars.platform.booking.contract.CoachNoShowEvent;
import com.softropic.skillars.platform.config.service.ConfigBounds;
import com.softropic.skillars.platform.config.service.ConfigService;
import com.softropic.skillars.platform.payment.repo.CoachCancellationHistoryRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Collection;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CancellationRefundMatrixTest {

    @Mock CoachCancellationHistoryRepository cancellationHistoryRepository;
    @Mock ReliabilityStrikeService reliabilityStrikeService;
    @Mock ConfigService configService;

    @InjectMocks CancellationRefundService service;

    private static final Long PARENT_ID = 1001L;
    private static final UUID COACH_ID = UUID.randomUUID();
    private static final UUID BOOKING_ID = UUID.randomUUID();
    private static final BigDecimal SESSION_PRICE = new BigDecimal("50.00");

    // --- Coach cancel excused (MUTUAL_AGREEMENT), credit-based ---
    @Test
    void coachCancel_excused_creditBased_writesRefund_savesHistory_noStrike() {
        BookingCancelledByCoachEvent event = coachEvent(null, "MUTUAL_AGREEMENT", false);

        service.onBookingCancelledByCoach(event);

        // Refund enqueue is now a BEFORE_COMMIT listener (RefundEnqueueListener); this test verifies history/strike
        verify(cancellationHistoryRepository).save(any());
        verify(reliabilityStrikeService, never()).issue(any(), any(), anyString());
    }

    // --- fix/booking-cancel-policy-loopholes: excused-cancellation cap (default 3 per 90 days) ---
    @Test
    void coachCancel_thirdExcusedInWindow_noStrike() {
        stubExcusedCap(3L);
        service.onBookingCancelledByCoach(coachEvent(null, "WEATHER", false));

        verify(cancellationHistoryRepository).save(any());
        verify(reliabilityStrikeService, never()).issue(any(), any(), anyString());
    }

    @Test
    void coachCancel_fourthExcusedInWindow_issuesExcusedLimitStrike_afterHistorySaved() {
        stubExcusedCap(4L);
        service.onBookingCancelledByCoach(coachEvent(null, "HEALTH_MEDICAL", false));

        var order = inOrder(cancellationHistoryRepository, reliabilityStrikeService);
        order.verify(cancellationHistoryRepository).save(any());
        order.verify(reliabilityStrikeService).issue(COACH_ID, BOOKING_ID, "COACH_CANCELLATION_EXCUSED_LIMIT");
    }

    @Test
    void coachCancel_excusedCap_countsOnlyExcusedReasonsInsideTheRollingWindow() {
        stubExcusedCap(0L);
        Instant before = Instant.now();
        service.onBookingCancelledByCoach(coachEvent(null, "FAMILY_EMERGENCY", false));

        verify(cancellationHistoryRepository).countByCoachIdAndCancelReasonInAndCreatedAtAfter(
            eq(COACH_ID),
            argThat((Collection<String> reasons) -> Set.copyOf(reasons).equals(
                Set.of("MUTUAL_AGREEMENT", "HEALTH_MEDICAL", "FAMILY_EMERGENCY", "WEATHER"))),
            argThat(after -> {
                Instant expected = before.minus(90, ChronoUnit.DAYS);
                return !after.isBefore(expected) && after.isBefore(expected.plusSeconds(5));
            }));
    }

    @Test
    void coachCancel_unexcused_doesNotConsultTheExcusedCap() {
        service.onBookingCancelledByCoach(coachEvent(null, "SCHEDULING_PREFERENCE", false));

        verify(cancellationHistoryRepository, never())
            .countByCoachIdAndCancelReasonInAndCreatedAtAfter(any(), anyCollection(), any());
        verify(reliabilityStrikeService, never()).issue(any(), any(), eq("COACH_CANCELLATION_EXCUSED_LIMIT"));
    }

    @Test
    void excusedCapConfigDefaults() {
        assertThat(ConfigBounds.COACH_EXCUSED_CANCELLATIONS_MAX_PER_WINDOW.defaultValue()).isEqualTo(3L);
        assertThat(ConfigBounds.COACH_EXCUSED_CANCELLATIONS_WINDOW_DAYS.defaultValue()).isEqualTo(90L);
    }

    private void stubExcusedCap(long excusedInWindow) {
        when(configService.getBoundedLong(ConfigBounds.COACH_EXCUSED_CANCELLATIONS_MAX_PER_WINDOW)).thenReturn(3L);
        when(configService.getBoundedLong(ConfigBounds.COACH_EXCUSED_CANCELLATIONS_WINDOW_DAYS)).thenReturn(90L);
        when(cancellationHistoryRepository.countByCoachIdAndCancelReasonInAndCreatedAtAfter(
            eq(COACH_ID), anyCollection(), any())).thenReturn(excusedInWindow);
    }

    // --- Coach cancel unexcused (SCHEDULING_PREFERENCE), credit-based ---
    @Test
    void coachCancel_unexcused_creditBased_writesRefund_savesHistory_issuesStrike() {
        BookingCancelledByCoachEvent event = coachEvent(null, "SCHEDULING_PREFERENCE", false);

        service.onBookingCancelledByCoach(event);

        // Refund enqueue is now a BEFORE_COMMIT listener (RefundEnqueueListener); this test verifies history/strike
        verify(cancellationHistoryRepository).save(any());
        verify(reliabilityStrikeService).issue(COACH_ID, BOOKING_ID, "COACH_CANCELLATION_UNEXCUSED");
    }

    // --- Coach cancel null reason treated as OTHER_UNEXCUSED ---
    @Test
    void coachCancel_nullReason_treatedAsUnexcused_issuesStrike() {
        BookingCancelledByCoachEvent event = coachEvent(null, null, false);

        service.onBookingCancelledByCoach(event);

        verify(reliabilityStrikeService).issue(COACH_ID, BOOKING_ID, "COACH_CANCELLATION_UNEXCUSED");
    }

    // Pack restores and refunds are enqueued by RefundEnqueueListener (fix/payment-session-pack-integrity) —
    // see RefundEnqueueListenerTest. This class covers only cancellation history and strikes.

    // --- C6: coach cancels an ACCEPTED pack booking: the session was never deducted ---
    @Test
    void coachCancel_packNotCaptured_restoresNothing_butStillStrikes() {
        UUID packId = UUID.randomUUID();
        BookingCancelledByCoachEvent event = new BookingCancelledByCoachEvent(
            this, BOOKING_ID, PARENT_ID, COACH_ID, "SCHEDULING_PREFERENCE", packId, java.math.BigDecimal.ZERO,
            false, false, "parent@test.com", Instant.now(), "UTC");

        service.onBookingCancelledByCoach(event);

        verify(cancellationHistoryRepository).save(any());
        verify(reliabilityStrikeService).issue(COACH_ID, BOOKING_ID, "COACH_CANCELLATION_UNEXCUSED");
    }

    // --- Coach no-show, credit-based ---
    @Test
    void coachNoShow_creditBased_writesRefund_issuesStrike() {
        CoachNoShowEvent event = noShowEvent(null, false);

        service.onCoachNoShow(event);

        // Refund enqueue is now a BEFORE_COMMIT listener (RefundEnqueueListener); this test verifies strike
        verify(reliabilityStrikeService).issue(COACH_ID, BOOKING_ID, "COACH_NO_SHOW");
    }

    // --- Coach no-show, pack active ---
    @Test
    void coachNoShow_packActive_restoresSession_issuesStrike() {
        UUID packId = UUID.randomUUID();
        CoachNoShowEvent event = noShowEvent(packId, false);

        service.onCoachNoShow(event);

        verify(reliabilityStrikeService).issue(COACH_ID, BOOKING_ID, "COACH_NO_SHOW");
    }

    // --- Coach no-show, pack expired ---
    @Test
    void coachNoShow_packExpired_writesRefund_issuesStrike() {
        UUID packId = UUID.randomUUID();
        CoachNoShowEvent event = noShowEvent(packId, true);

        service.onCoachNoShow(event);

        // Refund enqueue is now a BEFORE_COMMIT listener (RefundEnqueueListener); this test verifies strike
        verify(reliabilityStrikeService).issue(COACH_ID, BOOKING_ID, "COACH_NO_SHOW");
    }

    private BookingCancelledByCoachEvent coachEvent(UUID packId, String reason, boolean packExpired) {
        return new BookingCancelledByCoachEvent(
            this, BOOKING_ID, PARENT_ID, COACH_ID,
            reason, packId, SESSION_PRICE, packExpired, true,
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
}
