package com.softropic.skillars.platform.admin.service;

import com.softropic.skillars.platform.admin.contract.AdminActionType;
import com.softropic.skillars.platform.admin.repo.AdminActionLog;
import com.softropic.skillars.platform.admin.repo.AdminActionLogRepository;
import com.softropic.skillars.platform.booking.contract.ActorRole;
import com.softropic.skillars.platform.booking.contract.ForceCompleteOutcome;
import com.softropic.skillars.platform.booking.service.BookingCompletionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AdminBookingCompletionServiceTest {

    @Mock private BookingCompletionService bookingCompletionService;
    @Mock private AdminActionLogRepository adminActionLogRepository;

    private AdminBookingCompletionService service;

    private static final UUID BOOKING_ID = UUID.randomUUID();
    private static final Long ADMIN_ID = 555L;

    @BeforeEach
    void setUp() {
        service = new AdminBookingCompletionService(bookingCompletionService, adminActionLogRepository);
    }

    @Test
    void forceComplete_delegatesToSharedMethodWithAdminActorAndLogsAction() {
        when(bookingCompletionService.forceCompleteWithPlaceholderData(BOOKING_ID, ActorRole.ADMIN, ADMIN_ID))
            .thenReturn(ForceCompleteOutcome.FORCE_COMPLETED);

        ForceCompleteOutcome outcome = service.forceComplete(BOOKING_ID, ADMIN_ID, "support ticket #123");

        verify(bookingCompletionService)
            .forceCompleteWithPlaceholderData(BOOKING_ID, ActorRole.ADMIN, ADMIN_ID);

        // skillars-deferred-153 code review (2026-10-17): the outcome is returned to the caller
        // (AdminBookingResource wraps it in AdminForceCompleteBookingResponse) instead of being
        // swallowed behind a void/bare-200.
        assertThat(outcome).isEqualTo(ForceCompleteOutcome.FORCE_COMPLETED);

        ArgumentCaptor<AdminActionLog> captor = ArgumentCaptor.forClass(AdminActionLog.class);
        verify(adminActionLogRepository).save(captor.capture());
        AdminActionLog logged = captor.getValue();
        assertThat(logged.getAdminId()).isEqualTo(ADMIN_ID);
        assertThat(logged.getActionType()).isEqualTo(AdminActionType.BOOKING_FORCE_COMPLETE);
        assertThat(logged.getReferenceId()).isEqualTo(BOOKING_ID.toString());
        // skillars-deferred-153 code review (2026-10-17): the audit reason now carries the outcome.
        assertThat(logged.getReason()).isEqualTo("support ticket #123 [outcome: FORCE_COMPLETED]");
    }

    // skillars-deferred-153 code review (2026-10-17): the row is still written on a no-op path — an
    // admin DID take an action — but it must no longer claim a completion that did not happen.
    // Completion releases the coach's payout, so this is a money-trail distinction.
    @Test
    void forceComplete_alreadyResolved_stillLogsButRecordsTheNoOpOutcome() {
        when(bookingCompletionService.forceCompleteWithPlaceholderData(BOOKING_ID, ActorRole.ADMIN, ADMIN_ID))
            .thenReturn(ForceCompleteOutcome.ALREADY_RESOLVED);

        ForceCompleteOutcome outcome = service.forceComplete(BOOKING_ID, ADMIN_ID, "already resolved by coach");

        assertThat(outcome).isEqualTo(ForceCompleteOutcome.ALREADY_RESOLVED);

        ArgumentCaptor<AdminActionLog> captor = ArgumentCaptor.forClass(AdminActionLog.class);
        verify(adminActionLogRepository).save(captor.capture());
        assertThat(captor.getValue().getReason())
            .isEqualTo("already resolved by coach [outcome: ALREADY_RESOLVED]");
    }

    @Test
    void forceComplete_notStuck_stillLogsButRecordsTheNoOpOutcome() {
        when(bookingCompletionService.forceCompleteWithPlaceholderData(BOOKING_ID, ActorRole.ADMIN, ADMIN_ID))
            .thenReturn(ForceCompleteOutcome.NOT_STUCK);

        ForceCompleteOutcome outcome = service.forceComplete(BOOKING_ID, ADMIN_ID, "misdirected ticket");

        assertThat(outcome).isEqualTo(ForceCompleteOutcome.NOT_STUCK);

        ArgumentCaptor<AdminActionLog> captor = ArgumentCaptor.forClass(AdminActionLog.class);
        verify(adminActionLogRepository).save(captor.capture());
        assertThat(captor.getValue().getReason())
            .isEqualTo("misdirected ticket [outcome: NOT_STUCK]");
    }
}
