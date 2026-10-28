package com.softropic.skillars.platform.payment.service;

import com.softropic.skillars.platform.booking.contract.BookingCancelledByAdminEvent;
import com.softropic.skillars.platform.booking.contract.BookingCancelledByCoachEvent;
import com.softropic.skillars.platform.booking.contract.BookingCancelledByParentEvent;
import com.softropic.skillars.platform.booking.contract.CoachNoShowEvent;
import com.softropic.skillars.platform.booking.contract.PlayerNoShowEvent;
import com.softropic.skillars.platform.config.service.ConfigBounds;
import com.softropic.skillars.platform.config.service.ConfigService;
import com.softropic.skillars.platform.payment.repo.CoachCancellationHistory;
import com.softropic.skillars.platform.payment.repo.CoachCancellationHistoryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class CancellationRefundService {

    private static final Set<String> EXCUSED_REASONS =
        Set.of("MUTUAL_AGREEMENT", "HEALTH_MEDICAL", "FAMILY_EMERGENCY", "WEATHER");

    private final PackSessionService packSessionService;
    private final CoachCancellationHistoryRepository cancellationHistoryRepository;
    private final ReliabilityStrikeService reliabilityStrikeService;
    private final ConfigService configService;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onBookingCancelledByParent(BookingCancelledByParentEvent event) {
        // skillars-deferred-101 AC4: refund enqueue now handled by RefundEnqueueListener (BEFORE_COMMIT)
        if (event.getSessionPackPurchaseId() != null) {
            if (event.isRefundEligible()) {
                packSessionService.restoreSession(event.getSessionPackPurchaseId());
                log.info("Pack session restored for parent cancellation >24h: bookingId={}", event.getBookingId());
            }
            // else: forfeited, no action — session consumed
        }
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onBookingCancelledByCoach(BookingCancelledByCoachEvent event) {
        // skillars-deferred-101 AC4: refund enqueue now handled by RefundEnqueueListener (BEFORE_COMMIT).
        // C6: only a captured booking's pack session was ever deducted (at settle), so an ACCEPTED one
        // has nothing to restore — restoring it would hand the parent a free session.
        if (event.isPackRestore()) {
            packSessionService.restoreSession(event.getSessionPackPurchaseId());
        }

        // Always record cancellation history — ALL coach cancellations (excused AND unexcused)
        String reason = event.getCancelReason() != null ? event.getCancelReason() : "OTHER_UNEXCUSED";
        saveCancellationHistory(event.getCoachId(), event.getBookingId(), reason);

        if (!EXCUSED_REASONS.contains(reason)) {
            issueStrikeSafely(event.getCoachId(), event.getBookingId(), "COACH_CANCELLATION_UNEXCUSED");
        } else if (exceedsExcusedCancellationCap(event.getCoachId())) {
            issueStrikeSafely(event.getCoachId(), event.getBookingId(), "COACH_CANCELLATION_EXCUSED_LIMIT");
        }

        log.info("Coach cancellation processed: bookingId={} reason={}", event.getBookingId(), reason);
    }

    /**
     * fix/booking-cancel-policy-loopholes: excused reasons are self-declared, so without a cap a coach
     * could avoid strikes forever by always picking one. More than {@code maxPerWindow} excused
     * cancellations in the rolling window — counting the one just saved above (same transaction; the
     * derived count query auto-flushes it) — and this one strikes too. Admins can dismiss it.
     */
    private boolean exceedsExcusedCancellationCap(UUID coachId) {
        long maxPerWindow = configService.getBoundedLong(ConfigBounds.COACH_EXCUSED_CANCELLATIONS_MAX_PER_WINDOW);
        long windowDays = configService.getBoundedLong(ConfigBounds.COACH_EXCUSED_CANCELLATIONS_WINDOW_DAYS);
        long excusedInWindow = cancellationHistoryRepository.countByCoachIdAndCancelReasonInAndCreatedAtAfter(
            coachId, EXCUSED_REASONS, Instant.now().minus(windowDays, ChronoUnit.DAYS));
        return excusedInWindow > maxPerWindow;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onCoachNoShow(CoachNoShowEvent event) {
        // skillars-deferred-101 AC4: refund enqueue now handled by RefundEnqueueListener (BEFORE_COMMIT)
        if (event.isPackRestore()) {
            packSessionService.restoreSession(event.getSessionPackPurchaseId());
        }

        issueStrikeSafely(event.getCoachId(), event.getBookingId(), "COACH_NO_SHOW");
        log.info("Coach no-show processed: bookingId={}", event.getBookingId());
    }

    /**
     * skillars-deferred-100 code review (2026-09-08): the refund enqueue must survive strike failures.
     * skillars-deferred-101 AC4: refund enqueue is now a sibling {@link RefundEnqueueListener}
     * (BEFORE_COMMIT), committed atomically with the booking state. {@code ReliabilityStrikeService.issue}
     * is {@code REQUIRES_NEW}, so a strike failure rolls back only the strike; here we additionally
     * swallow {@link PessimisticLockingFailureException} (bounded-retry exhaustion under sustained
     * coach-row contention) so it cannot propagate out of the listener and roll back the refund.
     * A dropped strike-escalation is the deliberately-accepted cost (see the {@code issue()}
     * Javadoc); it is far less harmful than the refund this listener exists to protect.
     */
    private void issueStrikeSafely(UUID coachId, UUID bookingId, String reason) {
        try {
            reliabilityStrikeService.issue(coachId, bookingId, reason);
        } catch (PessimisticLockingFailureException e) {
            log.warn("Strike issuance skipped for coachId={} bookingId={} reason={} — coach-row lock "
                + "retry exhausted; the refund is unaffected", coachId, bookingId, reason, e);
        }
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onBookingCancelledByAdmin(BookingCancelledByAdminEvent event) {
        // skillars-deferred-101 AC4: refund enqueue now handled by RefundEnqueueListener (BEFORE_COMMIT).
        // C2: only a captured booking's pack session was ever deducted (PaymentLifecycleService deducts
        // at settle, not at request), so only that one is restored. Deliberately no cancellation
        // history and no reliability strike — the coach did not cancel; an admin/system action did.
        if (event.isPackRestore()) {
            packSessionService.restoreSession(event.getSessionPackPurchaseId());
            log.info("Pack session restored for admin cancellation: bookingId={}", event.getBookingId());
        }
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onPlayerNoShow(PlayerNoShowEvent event) {
        // No credit action — session fee forfeited, coach earnings unaffected (AC 3)
        log.info("Player no-show recorded: bookingId={}", event.getBookingId());
    }

    private void saveCancellationHistory(UUID coachId, UUID bookingId, String reason) {
        CoachCancellationHistory history = new CoachCancellationHistory();
        history.setCoachId(coachId);
        history.setBookingId(bookingId);
        history.setCancelReason(reason);
        cancellationHistoryRepository.save(history);
    }
}
