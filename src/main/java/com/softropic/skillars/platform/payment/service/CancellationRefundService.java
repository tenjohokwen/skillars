package com.softropic.skillars.platform.payment.service;

import com.softropic.skillars.platform.booking.contract.BookingCancelledByAdminEvent;
import com.softropic.skillars.platform.booking.contract.BookingCancelledByCoachEvent;
import com.softropic.skillars.platform.booking.contract.BookingCancelledByParentEvent;
import com.softropic.skillars.platform.booking.contract.CoachNoShowEvent;
import com.softropic.skillars.platform.booking.contract.PlayerNoShowEvent;
import com.softropic.skillars.platform.payment.repo.CoachCancellationHistory;
import com.softropic.skillars.platform.payment.repo.CoachCancellationHistoryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.math.BigDecimal;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class CancellationRefundService {

    private static final Set<String> EXCUSED_REASONS =
        Set.of("MUTUAL_AGREEMENT", "HEALTH_MEDICAL", "FAMILY_EMERGENCY", "WEATHER");

    private final CreditWalletService creditWalletService;
    private final PackSessionService packSessionService;
    private final CoachCancellationHistoryRepository cancellationHistoryRepository;
    private final ReliabilityStrikeService reliabilityStrikeService;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onBookingCancelledByParent(BookingCancelledByParentEvent event) {
        if (event.getSessionPackPurchaseId() != null) {
            if (event.isRefundEligible()) {
                packSessionService.restoreSession(event.getSessionPackPurchaseId());
                log.info("Pack session restored for parent cancellation >24h: bookingId={}", event.getBookingId());
            }
            // else: forfeited, no action — session consumed
        } else {
            if (event.isRefundEligible()) {
                creditWalletService.writeLedgerEntry(
                    event.getParentId(), event.getSessionPrice(),
                    "BOOKING_REFUND", event.getBookingId(),
                    "Parent cancellation >24h — full refund"
                );
                log.info("BOOKING_REFUND issued for parent cancellation: bookingId={}", event.getBookingId());
            }
            // else: forfeited, no action
        }
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onBookingCancelledByCoach(BookingCancelledByCoachEvent event) {
        if (event.getSessionPackPurchaseId() != null) {
            if (event.isPackExpiredAtCancellation()) {
                creditWalletService.writeLedgerEntry(
                    event.getParentId(), event.getSessionPrice(),
                    "BOOKING_REFUND", event.getBookingId(),
                    "Coach cancellation — expired pack refund"
                );
            } else {
                packSessionService.restoreSession(event.getSessionPackPurchaseId());
            }
        } else {
            creditWalletService.writeLedgerEntry(
                event.getParentId(), event.getSessionPrice(),
                "BOOKING_REFUND", event.getBookingId(),
                "Coach cancellation — full refund"
            );
        }

        // Always record cancellation history — ALL coach cancellations (excused AND unexcused)
        String reason = event.getCancelReason() != null ? event.getCancelReason() : "OTHER_UNEXCUSED";
        saveCancellationHistory(event.getCoachId(), event.getBookingId(), reason);

        if (!EXCUSED_REASONS.contains(reason)) {
            reliabilityStrikeService.issue(event.getCoachId(), event.getBookingId(), "COACH_CANCELLATION_UNEXCUSED");
        }

        log.info("Coach cancellation processed: bookingId={} reason={}", event.getBookingId(), reason);
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onCoachNoShow(CoachNoShowEvent event) {
        if (event.getSessionPackPurchaseId() != null) {
            if (event.isPackExpiredAtCancellation()) {
                creditWalletService.writeLedgerEntry(
                    event.getParentId(), event.getSessionPrice(),
                    "BOOKING_REFUND", event.getBookingId(),
                    "Coach no-show — expired pack refund"
                );
            } else {
                packSessionService.restoreSession(event.getSessionPackPurchaseId());
            }
        } else {
            creditWalletService.writeLedgerEntry(
                event.getParentId(), event.getSessionPrice(),
                "BOOKING_REFUND", event.getBookingId(),
                "Coach no-show — full refund"
            );
        }

        reliabilityStrikeService.issue(event.getCoachId(), event.getBookingId(), "COACH_NO_SHOW");
        log.info("Coach no-show processed: bookingId={}", event.getBookingId());
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onBookingCancelledByAdmin(BookingCancelledByAdminEvent event) {
        if (event.getSessionPackPurchaseId() != null) {
            packSessionService.restoreSession(event.getSessionPackPurchaseId());
            log.info("Pack session restored for admin suspension: bookingId={}", event.getBookingId());
        } else {
            creditWalletService.writeLedgerEntry(
                event.getParentId(), event.getSessionPrice(),
                "BOOKING_REFUND", event.getBookingId(),
                "Admin coach suspension — full refund"
            );
            log.info("BOOKING_REFUND issued for admin suspension: bookingId={}", event.getBookingId());
        }
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onPlayerNoShow(PlayerNoShowEvent event) {
        // No credit action — session fee forfeited, coach earnings unaffected (AC 3)
        log.info("Player no-show recorded: bookingId={}", event.getBookingId());
    }

    @Transactional
    public void processAdminRefund(UUID bookingId, BigDecimal amount, Long parentId) {
        creditWalletService.writeLedgerEntry(
            parentId, amount, "BOOKING_REFUND", bookingId, "Admin-approved refund"
        );
        log.info("Admin refund issued: bookingId={} amount={}", bookingId, amount);
    }

    private void saveCancellationHistory(UUID coachId, UUID bookingId, String reason) {
        CoachCancellationHistory history = new CoachCancellationHistory();
        history.setCoachId(coachId);
        history.setBookingId(bookingId);
        history.setCancelReason(reason);
        cancellationHistoryRepository.save(history);
    }
}
