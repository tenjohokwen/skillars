package com.softropic.skillars.platform.admin.service;

import com.softropic.skillars.infrastructure.exception.ResourceNotFoundException;
import com.softropic.skillars.platform.admin.contract.AdminActionType;
import com.softropic.skillars.platform.admin.contract.AdminBookingLookupDto;
import com.softropic.skillars.platform.admin.repo.AdminActionLog;
import com.softropic.skillars.platform.admin.repo.AdminActionLogRepository;
import com.softropic.skillars.platform.booking.contract.ActorRole;
import com.softropic.skillars.platform.booking.contract.ForceCompleteOutcome;
import com.softropic.skillars.platform.booking.repo.Booking;
import com.softropic.skillars.platform.booking.repo.BookingRepository;
import com.softropic.skillars.platform.booking.repo.SessionCompletionData;
import com.softropic.skillars.platform.booking.repo.SessionCompletionDataRepository;
import com.softropic.skillars.platform.booking.service.BookingCompletionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AdminBookingCompletionServiceTest {

    @Mock private BookingCompletionService bookingCompletionService;
    @Mock private AdminActionLogRepository adminActionLogRepository;
    @Mock private BookingRepository bookingRepository;
    @Mock private SessionCompletionDataRepository completionDataRepository;

    private AdminBookingCompletionService service;

    private static final UUID BOOKING_ID = UUID.randomUUID();
    private static final Long ADMIN_ID = 555L;

    @BeforeEach
    void setUp() {
        service = new AdminBookingCompletionService(bookingCompletionService, adminActionLogRepository,
            bookingRepository, completionDataRepository);
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

    @Test
    void lookup_stuckBookingWithoutCompletionRow_isForceCompletable() {
        when(bookingRepository.findById(BOOKING_ID)).thenReturn(Optional.of(booking("COMPLETED_PENDING_CONFIRMATION")));
        when(completionDataRepository.findByBookingId(BOOKING_ID)).thenReturn(Optional.empty());

        AdminBookingLookupDto dto = service.lookup(BOOKING_ID);

        assertThat(dto.bookingId()).isEqualTo(BOOKING_ID);
        assertThat(dto.status()).isEqualTo("COMPLETED_PENDING_CONFIRMATION");
        assertThat(dto.playerId()).isEqualTo(42L);
        assertThat(dto.completionRecorded()).isFalse();
        assertThat(dto.forceCompletable()).isTrue();
    }

    @Test
    void lookup_pendingConfirmationWithCompletionRow_isNotForceCompletable() {
        when(bookingRepository.findById(BOOKING_ID)).thenReturn(Optional.of(booking("COMPLETED_PENDING_CONFIRMATION")));
        when(completionDataRepository.findByBookingId(BOOKING_ID)).thenReturn(Optional.of(new SessionCompletionData()));

        AdminBookingLookupDto dto = service.lookup(BOOKING_ID);

        assertThat(dto.completionRecorded()).isTrue();
        assertThat(dto.forceCompletable()).isFalse();
    }

    @Test
    void lookup_otherStatus_isNotForceCompletable() {
        when(bookingRepository.findById(BOOKING_ID)).thenReturn(Optional.of(booking("UPCOMING")));
        when(completionDataRepository.findByBookingId(BOOKING_ID)).thenReturn(Optional.empty());

        assertThat(service.lookup(BOOKING_ID).forceCompletable()).isFalse();
    }

    @Test
    void lookup_unknownBooking_throwsNotFound() {
        when(bookingRepository.findById(BOOKING_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.lookup(BOOKING_ID)).isInstanceOf(ResourceNotFoundException.class);
    }

    private static Booking booking(String status) {
        Booking b = new Booking();
        b.setId(BOOKING_ID);
        b.setStatus(status);
        b.setPlayerId(42L);
        b.setParentId(7L);
        b.setCoachId(UUID.randomUUID());
        return b;
    }
}
