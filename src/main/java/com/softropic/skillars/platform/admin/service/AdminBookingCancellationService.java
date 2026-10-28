package com.softropic.skillars.platform.admin.service;

import com.softropic.skillars.platform.admin.contract.AdminActionType;
import com.softropic.skillars.platform.admin.contract.AdminAlertType;
import com.softropic.skillars.platform.admin.repo.AdminActionLog;
import com.softropic.skillars.platform.admin.repo.AdminActionLogRepository;
import com.softropic.skillars.platform.admin.repo.AdminAlertRepository;
import com.softropic.skillars.platform.booking.contract.AdminBookingCancellationOutcome;
import com.softropic.skillars.platform.booking.contract.BookingCancelledByAdminEvent;
import com.softropic.skillars.platform.booking.service.BookingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * C6: an admin cancels one booking — the support tool, and the follow-up for a booking a child-safety
 * hold could not cancel (a session that was live, or a capture that was in flight, at hold time).
 *
 * <p>Reuses {@link BookingService#cancelBookingAsAdmin} (C2) unchanged, so the semantics are exactly
 * those of a suspension sweep: decided under the booking row lock, {@code CANCEL_ADMIN} through the state
 * machine, a full refund only of money actually captured (pack session restored, or a wallet
 * {@code BOOKING_REFUND} of what was paid), no coach strike or cancellation history, and a neutral parent
 * notice — worded "cancelled by the Skillars team" ({@code cancel_reason} {@code ADMIN_SUPPORT}). Live
 * (IN_PROGRESS / PAUSED) and in-flight-capture bookings are refused, not cancelled.
 *
 * <p>Every invocation is audited ({@code BOOKING_ADMIN_CANCEL}, outcome appended), the force-complete
 * precedent: "an admin tried and it was refused" is as much a support fact as a cancellation. A
 * cancellation also resolves the booking's OPEN {@code SAFETY_HOLD_BOOKING} alert, if any — in the same
 * transaction, under the booking lock, so the alert cannot close on a cancellation that rolled back.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AdminBookingCancellationService {

    private final BookingService bookingService;
    private final AdminActionLogRepository adminActionLogRepository;
    private final AdminAlertRepository adminAlertRepository;

    @Transactional
    public AdminBookingCancellationOutcome cancel(UUID bookingId, Long adminId, String note) {
        AdminBookingCancellationOutcome outcome = bookingService.cancelBookingAsAdmin(
            bookingId, adminId, BookingCancelledByAdminEvent.REASON_ADMIN_SUPPORT);
        if (outcome == AdminBookingCancellationOutcome.CANCELLED) {
            int resolved = adminAlertRepository.resolveOpenAlertsForReference(
                bookingId.toString(), AdminAlertType.SAFETY_HOLD_BOOKING.name(), adminId);
            if (resolved > 0) {
                log.info("Safety-hold booking alert resolved by admin cancellation: bookingId={} adminId={}",
                    bookingId, adminId);
            }
        }
        AdminActionLog actionLog = new AdminActionLog();
        actionLog.setAdminId(adminId);
        actionLog.setActionType(AdminActionType.BOOKING_ADMIN_CANCEL);
        actionLog.setReferenceId(bookingId.toString());
        actionLog.setReason(note + " [outcome: " + outcome + "]");
        adminActionLogRepository.save(actionLog);
        log.info("Admin booking cancellation: bookingId={} adminId={} outcome={}", bookingId, adminId, outcome);
        return outcome;
    }
}
