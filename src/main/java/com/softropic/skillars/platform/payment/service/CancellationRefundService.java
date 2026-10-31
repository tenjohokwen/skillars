package com.softropic.skillars.platform.payment.service;

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
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Set;
import java.util.UUID;

/**
 * Coach-side consequences of a cancellation / no-show — cancellation history and reliability strikes. Strikes
 * run after the commit so their failure can never roll back the cancellation; since
 * fix/payment-wallet-dispute-integrity the coach-cancellation history row is written BEFORE_COMMIT, in the
 * cancellation's transaction, so it is never lost. Money (wallet refunds and, since
 * fix/payment-session-pack-integrity, pack session restores) is enqueued BEFORE_COMMIT by
 * {@link RefundEnqueueListener}; admin cancellations therefore need no listener here, and a parent cancellation
 * only for the coach no-show strike of a refund-eligible late cancel (fix/audit3-completion-dispute-integrity).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class CancellationRefundService {

    private static final Set<String> EXCUSED_REASONS =
        Set.of("MUTUAL_AGREEMENT", "HEALTH_MEDICAL", "FAMILY_EMERGENCY", "WEATHER");

    /**
     * fix/audit3-wallet-cashout-integrity: the one excused / unexcused classification of a coach cancel reason —
     * the strike logic here and {@code RefundEnqueueListener} (an excused cancel of an expired-pack booking
     * refunds spend-only credit; owner decision) both read it. A null reason is unexcused.
     */
    static boolean isExcusedReason(String cancelReason) {
        return cancelReason != null && EXCUSED_REASONS.contains(cancelReason);
    }

    /**
     * fix/audit3-completion-dispute-integrity: the classification of one coach cancellation — an excused reason AND
     * not a cancel at or after the start of a never-started booking (a no-show in disguise, whatever reason was
     * picked). Both the strike below and {@code RefundEnqueueListener}'s expired-pack refund read this, so an
     * after-start cancel is treated as the coach's fault on both sides, as a coach no-show is.
     */
    static boolean isExcusedCancellation(BookingCancelledByCoachEvent event) {
        return !event.isCancelledAfterStart() && isExcusedReason(event.getCancelReason());
    }

    private final CoachCancellationHistoryRepository cancellationHistoryRepository;
    private final ReliabilityStrikeService reliabilityStrikeService;
    private final ConfigService configService;

    /**
     * fix/payment-wallet-dispute-integrity: BEFORE_COMMIT, in the cancellation's own transaction — the
     * {@code coach_cancellation_history} row (what the excused-cancellation cap counts) commits exactly when the
     * cancellation does. It used to be written AFTER_COMMIT in a separate transaction, so a crash after the
     * booking committed, or any failure there, lost the row and the strike and weakened the cap. The strike
     * itself still runs after the commit ({@link #afterCommit}), in {@code ReliabilityStrikeService.issue}'s own
     * {@code REQUIRES_NEW} transaction, so a strike failure can never roll the cancellation back; the decision
     * whether to strike is taken here, under the per-coach lock, against the committed history.
     */
    @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT)
    // REQUIRED: joins the cancellation's transaction (BEFORE_COMMIT runs while it is still active), so the
    // pg_advisory_xact_lock, the history insert and the window count all sit in ONE transaction and the lock is
    // held until it commits — a concurrent cancel by the same coach counts this row only once it is committed.
    // Without it each repository call ran in its own transaction, the xact lock was released at once, and two
    // concurrent excused cancels both counted past the cap (two EXCUSED_LIMIT strikes). A direct call with no
    // transaction gets its own.
    @Transactional
    public void onBookingCancelledByCoach(BookingCancelledByCoachEvent event) {
        // skillars-deferred-101 AC4 / fix/payment-session-pack-integrity: the refund and the pack session
        // restore are both enqueued by RefundEnqueueListener (BEFORE_COMMIT, durable outbox).

        // Always record cancellation history — ALL coach cancellations (excused AND unexcused)
        String reason = event.getCancelReason() != null ? event.getCancelReason() : "OTHER_UNEXCUSED";
        // fix/booking-price-and-excused-race: before the history insert and the window count, so concurrent
        // cancels by one coach count and strike one at a time (see acquireCoachCancellationLock); held to the
        // cancellation's commit. Taken for unexcused reasons too: their history rows are not counted, but one
        // code path keeps the ordering obvious and the lock is held for milliseconds.
        cancellationHistoryRepository.acquireCoachCancellationLock(event.getCoachId().toString());
        saveCancellationHistory(event.getCoachId(), event.getBookingId(), reason, event.isCancelledAfterStart());

        String strikeReason = null;
        if (event.isCancelledAfterStart()) {
            // fix/audit3-completion-dispute-integrity: at or after the start the family is already waiting — a
            // no-show in disguise, whatever reason was picked. Always a no-show strike; its history row is
            // flagged after_start, so it never counts toward (or uses up) the excused-cancellation cap.
            strikeReason = "COACH_NO_SHOW";
        } else if (!isExcusedCancellation(event)) {
            strikeReason = "COACH_CANCELLATION_UNEXCUSED";
        } else if (exceedsExcusedCancellationCap(event.getCoachId())) {
            strikeReason = "COACH_CANCELLATION_EXCUSED_LIMIT";
        }
        if (strikeReason != null) {
            String strike = strikeReason;
            afterCommit(() -> issueStrikeSafely(event.getCoachId(), event.getBookingId(), strike));
        }

        log.info("Coach cancellation processed: bookingId={} reason={}", event.getBookingId(), reason);
    }

    /**
     * fix/booking-cancel-policy-loopholes: excused reasons are self-declared, so without a cap a coach
     * could avoid strikes forever by always picking one. More than {@code maxPerWindow} excused
     * cancellations in the rolling window — counting the one just saved above (same transaction; the
     * derived count query auto-flushes it) — and this one strikes too. Admins can dismiss it. Runs under
     * the per-coach advisory lock taken in {@link #onBookingCancelledByCoach}, so a concurrent excused
     * cancel by the same coach is counted only once it has committed.
     */
    private boolean exceedsExcusedCancellationCap(UUID coachId) {
        long maxPerWindow = configService.getBoundedLong(ConfigBounds.COACH_EXCUSED_CANCELLATIONS_MAX_PER_WINDOW);
        long windowDays = configService.getBoundedLong(ConfigBounds.COACH_EXCUSED_CANCELLATIONS_WINDOW_DAYS);
        // fix/audit3-completion-dispute-integrity: after-start cancels are struck on their own and not counted here.
        long excusedInWindow = cancellationHistoryRepository.countByCoachIdAndCancelReasonInAndAfterStartFalseAndCreatedAtAfter(
            coachId, EXCUSED_REASONS, Instant.now().minus(windowDays, ChronoUnit.DAYS));
        return excusedInWindow > maxPerWindow;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onCoachNoShow(CoachNoShowEvent event) {
        // skillars-deferred-101 AC4 / fix/payment-session-pack-integrity: refund and pack restore are enqueued
        // by RefundEnqueueListener (BEFORE_COMMIT, durable outbox).
        if (event.isCoachSplit()) {
            // S-07 conflict zone: the coach quick-completed with no live Start and the family reports no-show. Neither
            // side is established — the fee is split (CoachNoShowSplitListener) and nobody is struck.
            log.info("Coach no-show split (no strike): bookingId={}", event.getBookingId());
            return;
        }
        issueStrikeSafely(event.getCoachId(), event.getBookingId(), "COACH_NO_SHOW");
        log.info("Coach no-show processed: bookingId={}", event.getBookingId());
    }

    /**
     * fix/audit3-completion-dispute-integrity: a parent's refund-eligible late cancel — the session never started
     * and start + {@code booking.lateCancel.graceMinutes} has passed — is the coach's no-show, the same facts
     * {@link #onCoachNoShow} strikes on. It used to refund the family and record nothing against the coach. The
     * refund itself is enqueued by {@link RefundEnqueueListener}; this only strikes, once, after the commit.
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onBookingCancelledByParent(BookingCancelledByParentEvent event) {
        if (!event.isCoachNoShow()) {
            return;
        }
        issueStrikeSafely(event.getCoachId(), event.getBookingId(), "COACH_NO_SHOW");
        log.info("Parent late cancel after a coach no-show: bookingId={} — coach strike issued", event.getBookingId());
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
    public void onPlayerNoShow(PlayerNoShowEvent event) {
        // No credit action — the session fee is forfeited. feat/payment-forfeit-payouts-and-clawback: the coach
        // IS paid for it, by CoachPayoutEnqueueListener (BEFORE_COMMIT, atomic with the NO_SHOW_PLAYER write).
        log.info("Player no-show recorded: bookingId={}", event.getBookingId());
    }

    /**
     * Runs {@code action} once the current transaction has committed (never on rollback); immediately when no
     * transaction synchronization is active. Registering from a BEFORE_COMMIT listener is fine: the completion
     * callbacks are read after the before-commit ones have run. Runs in {@code afterCompletion}, like Spring's own
     * AFTER_COMMIT event listeners, and never throws back into the committed caller.
     */
    private static void afterCommit(Runnable action) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            action.run();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if (status != STATUS_COMMITTED) {
                    return;
                }
                try {
                    action.run();
                } catch (RuntimeException e) {
                    log.error("Post-commit coach strike failed — the cancellation and its history row stand", e);
                }
            }
        });
    }

    private void saveCancellationHistory(UUID coachId, UUID bookingId, String reason, boolean afterStart) {
        CoachCancellationHistory history = new CoachCancellationHistory();
        history.setCoachId(coachId);
        history.setBookingId(bookingId);
        history.setCancelReason(reason);
        history.setAfterStart(afterStart);
        cancellationHistoryRepository.save(history);
    }
}
