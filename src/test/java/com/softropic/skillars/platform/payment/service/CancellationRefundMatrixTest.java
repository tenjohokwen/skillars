package com.softropic.skillars.platform.payment.service;

import com.softropic.skillars.platform.booking.contract.BookingCancelledByCoachEvent;
import com.softropic.skillars.platform.booking.contract.BookingCancelledByParentEvent;
import com.softropic.skillars.platform.booking.contract.CoachNoShowEvent;
import com.softropic.skillars.platform.payment.repo.CoachCancellationHistoryRepository;
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

@ExtendWith(MockitoExtension.class)
class CancellationRefundMatrixTest {

    @Mock CreditWalletService creditWalletService;
    @Mock PackSessionService packSessionService;
    @Mock CoachCancellationHistoryRepository cancellationHistoryRepository;
    @Mock ReliabilityStrikeService reliabilityStrikeService;

    @InjectMocks CancellationRefundService service;

    private static final Long PARENT_ID = 1001L;
    private static final UUID COACH_ID = UUID.randomUUID();
    private static final UUID BOOKING_ID = UUID.randomUUID();
    private static final BigDecimal SESSION_PRICE = new BigDecimal("50.00");

    // --- Parent cancel > 24h, credit-based ---
    @Test
    void parentCancel_gt24h_creditBased_writesRefund() {
        BookingCancelledByParentEvent event = parentEvent(null, 25);

        service.onBookingCancelledByParent(event);

        verify(creditWalletService).writeLedgerEntry(
            eq(PARENT_ID), eq(SESSION_PRICE), eq("BOOKING_REFUND"), eq(BOOKING_ID), anyString());
        verify(packSessionService, never()).restoreSession(any());
    }

    // --- Parent cancel > 24h, pack-based ---
    @Test
    void parentCancel_gt24h_packBased_restoresSession_noCredit() {
        UUID packId = UUID.randomUUID();
        BookingCancelledByParentEvent event = parentEvent(packId, 25);

        service.onBookingCancelledByParent(event);

        verify(packSessionService).restoreSession(packId);
        verify(creditWalletService, never()).writeLedgerEntry(any(), any(), any(), any(), any());
    }

    // --- Parent cancel <= 24h, credit-based ---
    @Test
    void parentCancel_lte24h_creditBased_noCreditNoRestore() {
        BookingCancelledByParentEvent event = parentEvent(null, 24);

        service.onBookingCancelledByParent(event);

        verify(creditWalletService, never()).writeLedgerEntry(any(), any(), any(), any(), any());
        verify(packSessionService, never()).restoreSession(any());
    }

    // --- Parent cancel <= 24h, pack-based ---
    @Test
    void parentCancel_lte24h_packBased_noRestoreNoCredit() {
        UUID packId = UUID.randomUUID();
        BookingCancelledByParentEvent event = parentEvent(packId, 0);

        service.onBookingCancelledByParent(event);

        verify(packSessionService, never()).restoreSession(any());
        verify(creditWalletService, never()).writeLedgerEntry(any(), any(), any(), any(), any());
    }

    // --- Coach cancel excused (MUTUAL_AGREEMENT), credit-based ---
    @Test
    void coachCancel_excused_creditBased_writesRefund_savesHistory_noStrike() {
        BookingCancelledByCoachEvent event = coachEvent(null, "MUTUAL_AGREEMENT", false);

        service.onBookingCancelledByCoach(event);

        verify(creditWalletService).writeLedgerEntry(
            eq(PARENT_ID), eq(SESSION_PRICE), eq("BOOKING_REFUND"), eq(BOOKING_ID), anyString());
        verify(cancellationHistoryRepository).save(any());
        verify(reliabilityStrikeService, never()).issue(any(), any(), anyString());
    }

    // --- Coach cancel unexcused (SCHEDULING_PREFERENCE), credit-based ---
    @Test
    void coachCancel_unexcused_creditBased_writesRefund_savesHistory_issuesStrike() {
        BookingCancelledByCoachEvent event = coachEvent(null, "SCHEDULING_PREFERENCE", false);

        service.onBookingCancelledByCoach(event);

        verify(creditWalletService).writeLedgerEntry(
            eq(PARENT_ID), eq(SESSION_PRICE), eq("BOOKING_REFUND"), eq(BOOKING_ID), anyString());
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

    // --- Coach cancel, pack not expired ---
    @Test
    void coachCancel_packNotExpired_restoresSession_noCredit() {
        UUID packId = UUID.randomUUID();
        BookingCancelledByCoachEvent event = coachEvent(packId, "MUTUAL_AGREEMENT", false);

        service.onBookingCancelledByCoach(event);

        verify(packSessionService).restoreSession(packId);
        verify(creditWalletService, never()).writeLedgerEntry(any(), any(), any(), any(), any());
    }

    // --- Coach cancel, pack expired ---
    @Test
    void coachCancel_packExpired_writesRefund_noRestore() {
        UUID packId = UUID.randomUUID();
        BookingCancelledByCoachEvent event = coachEvent(packId, "MUTUAL_AGREEMENT", true);

        service.onBookingCancelledByCoach(event);

        verify(creditWalletService).writeLedgerEntry(
            eq(PARENT_ID), eq(SESSION_PRICE), eq("BOOKING_REFUND"), eq(BOOKING_ID), anyString());
        verify(packSessionService, never()).restoreSession(any());
    }

    // --- Coach no-show, credit-based ---
    @Test
    void coachNoShow_creditBased_writesRefund_issuesStrike() {
        CoachNoShowEvent event = noShowEvent(null, false);

        service.onCoachNoShow(event);

        verify(creditWalletService).writeLedgerEntry(
            eq(PARENT_ID), eq(SESSION_PRICE), eq("BOOKING_REFUND"), eq(BOOKING_ID), anyString());
        verify(reliabilityStrikeService).issue(COACH_ID, BOOKING_ID, "COACH_NO_SHOW");
    }

    // --- Coach no-show, pack active ---
    @Test
    void coachNoShow_packActive_restoresSession_issuesStrike() {
        UUID packId = UUID.randomUUID();
        CoachNoShowEvent event = noShowEvent(packId, false);

        service.onCoachNoShow(event);

        verify(packSessionService).restoreSession(packId);
        verify(reliabilityStrikeService).issue(COACH_ID, BOOKING_ID, "COACH_NO_SHOW");
        verify(creditWalletService, never()).writeLedgerEntry(any(), any(), any(), any(), any());
    }

    // --- Coach no-show, pack expired ---
    @Test
    void coachNoShow_packExpired_writesRefund_issuesStrike() {
        UUID packId = UUID.randomUUID();
        CoachNoShowEvent event = noShowEvent(packId, true);

        service.onCoachNoShow(event);

        verify(creditWalletService).writeLedgerEntry(
            eq(PARENT_ID), eq(SESSION_PRICE), eq("BOOKING_REFUND"), eq(BOOKING_ID), anyString());
        verify(reliabilityStrikeService).issue(COACH_ID, BOOKING_ID, "COACH_NO_SHOW");
    }

    private BookingCancelledByParentEvent parentEvent(UUID packId, long hours) {
        return new BookingCancelledByParentEvent(
            this, BOOKING_ID, PARENT_ID, COACH_ID,
            packId, hours, hours > 24,
            SESSION_PRICE, "parent@test.com", "coach@test.com",
            Instant.now(), "UTC"
        );
    }

    private BookingCancelledByCoachEvent coachEvent(UUID packId, String reason, boolean packExpired) {
        return new BookingCancelledByCoachEvent(
            this, BOOKING_ID, PARENT_ID, COACH_ID,
            reason, packId, SESSION_PRICE, packExpired,
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
