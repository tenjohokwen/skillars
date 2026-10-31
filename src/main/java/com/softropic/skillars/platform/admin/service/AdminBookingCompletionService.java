package com.softropic.skillars.platform.admin.service;

import com.softropic.skillars.infrastructure.security.AdminTierGuard;
import com.softropic.skillars.infrastructure.exception.ResourceNotFoundException;
import com.softropic.skillars.platform.admin.contract.AdminActionType;
import com.softropic.skillars.platform.admin.contract.AdminBookingLookupDto;
import com.softropic.skillars.platform.admin.repo.AdminActionLog;
import com.softropic.skillars.platform.admin.repo.AdminActionLogRepository;
import com.softropic.skillars.platform.booking.contract.ActorRole;
import com.softropic.skillars.platform.booking.contract.BookingStatus;
import com.softropic.skillars.platform.booking.contract.ForceCompleteOutcome;
import com.softropic.skillars.platform.booking.repo.Booking;
import com.softropic.skillars.platform.booking.repo.BookingRepository;
import com.softropic.skillars.platform.booking.repo.SessionCompletionDataRepository;
import com.softropic.skillars.platform.booking.service.BookingCompletionService;
import com.softropic.skillars.platform.booking.service.BookingService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * skillars-deferred-153 AC1: the manual half of the wrap-up abandonment remediation — an admin
 * acting on a known support request/ticket for a {@code bookingId} directly (no "stuck bookings
 * queue" UI; out of scope, see the story's "Explicitly NOT picked up" section). Shares
 * {@link BookingCompletionService#forceCompleteWithPlaceholderData} with
 * {@code StuckCompletionSweeperService}; follows {@code DisputeService}'s exact pattern for
 * admin-action logging.
 */
@Service
@RequiredArgsConstructor
public class AdminBookingCompletionService {

    private final BookingCompletionService bookingCompletionService;
    private final AdminActionLogRepository adminActionLogRepository;
    private final BookingRepository bookingRepository;
    private final SessionCompletionDataRepository completionDataRepository;

    /**
     * Read-only lookup backing the admin force-complete page: lets the admin confirm the booking id
     * from a support ticket resolves to the booking they expect before acting. No lock — nothing is
     * written, and {@link #forceComplete} re-runs its own pre-checks against the live row.
     */
    @Transactional(readOnly = true)
    public AdminBookingLookupDto lookup(UUID bookingId) {
        Booking booking = bookingRepository.findById(bookingId)
            .orElseThrow(() -> new ResourceNotFoundException("Booking not found", "booking"));
        boolean completionRecorded = completionDataRepository.findByBookingId(bookingId).isPresent();
        boolean pendingConfirmation =
            BookingStatus.COMPLETED_PENDING_CONFIRMATION.name().equals(booking.getStatus());
        return new AdminBookingLookupDto(
            booking.getId(), booking.getStatus(), booking.getCoachId(), booking.getPlayerId(),
            booking.getParentId(), booking.getRequestedStartTime(), booking.getRequestedEndTime(),
            booking.getUpdatedAt(), completionRecorded, pendingConfirmation && !completionRecorded,
            BookingService.ADMIN_CANCELLABLE_STATUSES.contains(booking.getStatus()),
            BookingService.LIVE_SESSION_STATUSES.contains(booking.getStatus()));
    }

    @Transactional
    public ForceCompleteOutcome forceComplete(UUID bookingId, Long adminId, String note) {
        AdminTierGuard.requireFullAdmin(); // S-28: not for ROLE_LTD_ADMIN
        ForceCompleteOutcome outcome =
            bookingCompletionService.forceCompleteWithPlaceholderData(bookingId, ActorRole.ADMIN, adminId);
        logAdminAction(adminId, bookingId, note, outcome);
        return outcome;
    }

    /**
     * skillars-deferred-153 code review (2026-10-17): the outcome is recorded in the audit row.
     *
     * <p>The row is still written on every invocation — an admin did take an action, and "admin
     * invoked force-complete and it was already resolved" is exactly the fact a later support audit
     * needs. What changed is that it no longer claims a completion that did not happen: previously
     * this ran unconditionally after a {@code void} call with two silent no-op paths, so the trail
     * asserted a force-complete for bookings nothing had been done to. Completion releases the
     * coach's payout, so the distinction is a money-trail distinction, not a cosmetic one.
     */
    private void logAdminAction(Long adminId, UUID bookingId, String note, ForceCompleteOutcome outcome) {
        AdminActionLog log = new AdminActionLog();
        log.setAdminId(adminId);
        log.setActionType(AdminActionType.BOOKING_FORCE_COMPLETE);
        log.setReferenceId(bookingId.toString());
        log.setReason(note + " [outcome: " + outcome + "]");
        adminActionLogRepository.save(log);
    }
}
