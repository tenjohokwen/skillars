package com.softropic.skillars.platform.payment.service;

import com.softropic.skillars.platform.payment.contract.CoachPayoutStatus;
import com.softropic.skillars.platform.payment.repo.CoachPayout;
import com.softropic.skillars.platform.payment.repo.CoachPayoutFreezeRepository;
import com.softropic.skillars.platform.payment.repo.CoachPayoutRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.UUID;

/**
 * fix/payment-payouts-disputes-webhooks: the one place a coach payout is frozen, unfrozen or cut because
 * money went back to the payer — an in-app dispute ({@code DisputeService}) or a Stripe card dispute
 * ({@code ChargeDisputeWebhookService}). Every method runs in the caller's transaction
 * ({@code MANDATORY}) and acts on a row the caller locked with {@link CoachPayoutRepository#findByIdForUpdate}
 * or {@link CoachPayoutRepository#findFundedByPaymentIntentForUpdate}.
 *
 * <p><strong>Freeze reasons do not clobber each other.</strong> A payout is {@code FROZEN} while any of
 * three reasons stands: the coach's safety hold ({@code coach_payout_freezes}), an open in-app dispute
 * ({@code dispute_frozen}) or an open card dispute ({@code chargeback_frozen}). Clearing one reason
 * releases the payout only when the other two are clear too ({@link #releaseIfUnfrozen}).
 *
 * <p><strong>Same outcome regardless of timing.</strong> A refund of {@code parentRefund} out of
 * {@code parentPrice} costs the coach the same proportional share of their net whether the payout has
 * been released (a reversal of that share) or not (the pending net is cut by that share and released).
 * Only a cut of the whole net cancels the payout.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CoachPayoutAdjustmentService {

    /** {@code coach_payouts.last_error} markers. */
    static final String REASON_DISPUTE_OPEN = "DISPUTE_OPEN";
    static final String REASON_CHARGEBACK_OPEN = "CHARGEBACK_OPEN";

    private final CoachPayoutRepository coachPayoutRepository;
    private final CoachPayoutFreezeRepository coachPayoutFreezeRepository;
    private final CoachPayoutOutboxSupport coachPayoutOutboxSupport;
    private final CoachPayoutDebtService coachPayoutDebtService;

    /** What {@link #applyRefund} did to the payout. */
    public enum RefundOutcome { NONE, REDUCED, CANCELLED, REVERSAL_ENQUEUED }

    /**
     * Marks the in-app dispute freeze on the booking's payout (locked here). A not-yet-released payout
     * stops releasing; a released one is left as is (its reversal, if any, comes with the resolution).
     *
     * @return the payout's status after the call, or null when the booking has no payout row
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public String freezeForDispute(UUID bookingId) {
        CoachPayout row = coachPayoutRepository.findByIdForUpdate(bookingId).orElse(null);
        if (row == null) {
            return null;
        }
        if (isUnreleased(row.getStatus())) {
            row.setDisputeFrozen(true);
            freeze(row, REASON_DISPUTE_OPEN);
            log.info("[COACH_PAYOUT_DISPUTE_FREEZE] booking={} — payout {} while the dispute is open",
                bookingId, row.getStatus());
        }
        return row.getStatus();
    }

    /** Clears the in-app dispute freeze (dispute resolved or dismissed) and releases the payout if nothing else holds it. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void clearDisputeFreeze(UUID bookingId) {
        coachPayoutRepository.findByIdForUpdate(bookingId).ifPresent(row -> {
            if (!row.isDisputeFrozen()) {
                return;
            }
            row.setDisputeFrozen(false);
            releaseIfUnfrozen(row);
        });
    }

    /** Card dispute opened: freeze a locked, not-yet-released payout. @return true when it was frozen. */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean freezeForChargeback(CoachPayout row) {
        if (!isUnreleased(row.getStatus())) {
            return false;
        }
        row.setChargebackFrozen(true);
        freeze(row, REASON_CHARGEBACK_OPEN);
        return true;
    }

    /** Card dispute closed (and no other one open on the charge): clear the freeze, release if nothing else holds it. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void clearChargebackFreeze(CoachPayout row) {
        if (!row.isChargebackFrozen()) {
            return;
        }
        row.setChargebackFrozen(false);
        releaseIfUnfrozen(row);
    }

    /**
     * The coach-side of {@code parentRefund} going back to the payer out of {@code parentPrice} paid for
     * the session. The coach bears {@code net * parentRefund / parentPrice}, where {@code net} is the payout
     * as first computed ({@link CoachPayout#getOriginalNet()}) and {@code parentPrice} the full price —
     * fix/payment-payout-recovery: a second partial refund on a pending payout used to take its share of
     * the already-cut net, so the coach kept more than when both refunds landed after release:
     * <ul>
     *   <li>RELEASED — a {@code COACH_PAYOUT_REVERSAL} for that share (capped at what is still with the
     *       coach) is enqueued;</li>
     *   <li>PENDING_RELEASE / HOLD / FROZEN — net, gross and commission are cut pro rata and the payout
     *       keeps its status (it still releases); a cut of the whole net cancels it;</li>
     *   <li>anything else (or a zero share) — nothing.</li>
     * </ul>
     * Freeze flags are not touched — the caller clears the reason that applies to it.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public RefundOutcome applyRefund(CoachPayout row, BigDecimal parentRefund, BigDecimal parentPrice, String tag) {
        if (parentRefund == null || parentRefund.signum() <= 0) {
            return RefundOutcome.NONE;
        }
        BigDecimal share = coachShareOfRefund(row.getOriginalNet(), parentRefund, parentPrice);
        if (share.signum() <= 0) {
            return RefundOutcome.NONE;
        }
        UUID bookingId = row.getBookingId();
        String status = row.getStatus();
        if (CoachPayoutStatus.RELEASED.equals(status)) {
            BigDecimal reverse = share.min(row.getEffectiveNetAmount());
            if (reverse.signum() <= 0) {
                return RefundOutcome.NONE;
            }
            coachPayoutOutboxSupport.enqueueReversal(bookingId, reverse);
            log.info("{} on booking {}: coach payout RELEASED — enqueued reversal of {}", tag, bookingId, reverse);
            return RefundOutcome.REVERSAL_ENQUEUED;
        }
        if (!isUnreleased(status)) {
            log.info("{} on booking {}: coach payout already {} — no coach-side action", tag, bookingId, status);
            return RefundOutcome.NONE;
        }
        if (share.compareTo(row.getNetAmount()) >= 0) {
            row.setStatus(CoachPayoutStatus.CANCELLED);
            row.setLastError(truncate(tag + " (was " + status + ")"));
            coachPayoutRepository.save(row);
            log.info("{} on booking {}: whole pending coach payout ({}) cancelled — no transfer", tag, bookingId, status);
            return RefundOutcome.CANCELLED;
        }
        BigDecimal oldNet = row.getNetAmount();
        BigDecimal newNet = cutNet(row, share);
        row.setLastError(truncate(tag + " -" + share + " (net was " + oldNet + ")"));
        coachPayoutRepository.save(row);
        log.info("{} on booking {}: pending coach payout ({}) cut by {} to net {}", tag, bookingId, status, share, newNet);
        return RefundOutcome.REDUCED;
    }

    /**
     * feat/payment-forfeit-payouts-and-clawback: an {@code UPHOLD_COACH} dispute resolution — the coach is
     * paid their full net for the booking, whatever was recovered from the payer (the platform absorbs the
     * gap). The caller must already have marked its dispute resolved and flushed it, so the new payout is not
     * frozen for that dispute.
     * <ul>
     *   <li>no row (a refunded booking never completed) — a new payout through
     *       {@link CoachPayoutOutboxSupport#enqueuePayout}, with its usual hold and freeze checks;</li>
     *   <li>CANCELLED with a net (a dispute refund cut it to nothing) — reinstated and released now, unless
     *       a safety hold or an open card dispute still freezes it;</li>
     *   <li>CANCELLED with nothing computed (a skip reason) — replaced by a freshly computed payout. A card /
     *       credit row is always recomputable (from {@code booking_payments}); a pack row only when its pack
     *       share was never allocated ({@link CoachPayoutOutboxSupport#isRecomputable}) — one that settled at
     *       0.00 stays as it is, so the session is never settled twice on the pack ledger;</li>
     *   <li>fix/payment-payout-recovery — PENDING_RELEASE / HOLD / FROZEN (or CANCELLED with a net) cut by an
     *       earlier refund — net, gross and commission restored to the payout as first computed before it is
     *       released (a HOLD keeps its status and pays the restored net once re-driven);</li>
     *   <li>fix/payment-payout-recovery — RELEASED / REVERSED short of the full net (an earlier refund reversed
     *       part or all of it, or it released after a pending cut) — the coach's debt for the booking (a
     *       reversal Stripe refused) is waived, and a {@code COACH_PAYOUT_TOPUP} transfer of what is still
     *       missing is enqueued ({@link CoachPayoutTopUpHandler});</li>
     *   <li>anything else (FAILED_PERMANENT, REVERSAL_FAILED with no transfer) — left as it is (only its
     *       dispute freeze is lifted).</li>
     * </ul>
     *
     * @return the coach's net now owed or paid for the booking (zero when nothing could be computed)
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public BigDecimal payInFull(UUID bookingId, UUID coachId) {
        CoachPayout row = coachPayoutRepository.findByIdForUpdate(bookingId).orElse(null);
        if (row != null && CoachPayoutStatus.CANCELLED.equals(row.getStatus())
                && (row.getNetAmount() == null || row.getNetAmount().signum() <= 0)) {
            if (!CoachPayoutOutboxSupport.isRecomputable(row)) {
                // fix/payment-pack-clawback-and-expired-cancel: a pack session whose share was allocated
                // (NOTHING_CHARGED: it settled at 0.00) is already settled on the pack ledger. Deleting the row
                // and enqueuing again would allocate the session a second time and short the pack's last
                // payout. There is nothing more to pay for it.
                row.setDisputeFrozen(false);
                coachPayoutRepository.save(row);
                log.info("[COACH_PAYOUT_UPHOLD] booking={} — pack session already settled at {} ({}), nothing to "
                    + "reinstate", bookingId, row.getGrossAmount(), row.getLastError());
                return BigDecimal.ZERO;
            }
            coachPayoutRepository.delete(row);
            coachPayoutRepository.flush();
            row = null;
        }
        if (row == null) {
            coachPayoutOutboxSupport.enqueuePayout(bookingId, coachId);
            return coachPayoutRepository.findById(bookingId)
                .filter(p -> !CoachPayoutStatus.CANCELLED.equals(p.getStatus()))
                .map(CoachPayout::getEffectiveNetAmount)
                .orElse(BigDecimal.ZERO);
        }
        String status = row.getStatus();
        // fix/audit3-completion-dispute-integrity: one open dispute per booking PER SIDE (V214) — the other side's
        // dispute may still be open, and it keeps a not-yet-released payout frozen. The caller flushed the
        // upheld dispute RESOLVED first, so it is not counted. (A released payout is never dispute-frozen.)
        row.setDisputeFrozen((isUnreleased(status) || CoachPayoutStatus.CANCELLED.equals(status))
            && coachPayoutRepository.existsOpenDisputeForBooking(bookingId));
        if (CoachPayoutStatus.REVERSAL_FAILED.equals(status) && row.getStripeTransferId() != null) {
            // A reversal Stripe refused before V205 left the money with the coach — which UPHOLD_COACH now says
            // is theirs. Back to RELEASED (reported as earned again).
            row.setStatus(CoachPayoutStatus.RELEASED);
            log.info("[COACH_PAYOUT_UPHOLD] booking={} — REVERSAL_FAILED payout back to RELEASED", bookingId);
            status = CoachPayoutStatus.RELEASED;
        }
        if (CoachPayoutStatus.RELEASED.equals(status) || CoachPayoutStatus.REVERSED.equals(status)) {
            return topUpReleased(row);
        }
        if (!isUnreleased(status) && !CoachPayoutStatus.CANCELLED.equals(status)) {
            coachPayoutRepository.save(row);
            log.warn("[COACH_PAYOUT_UPHOLD] booking={} — payout is {} ({}), not paid automatically; operator follow-up",
                bookingId, status, row.getLastError());
            return row.getEffectiveNetAmount();
        }
        // fix/payment-payout-recovery: an earlier refund cut this pending payout; the coach is owed the full net.
        if (restoreOriginal(row)) {
            log.info("[COACH_PAYOUT_UPHOLD] booking={} — pending payout ({}) restored to its full net {}", bookingId,
                status, row.getNetAmount());
        }
        if (CoachPayoutStatus.CANCELLED.equals(status)) {
            // Reinstate: FROZEN + releaseIfUnfrozen is the one path that re-checks every freeze reason and
            // enqueues exactly one transfer. The hold already ran when the row was first written.
            row.setStatus(CoachPayoutStatus.FROZEN);
            row.setReleaseAfter(Instant.now());
            log.info("[COACH_PAYOUT_UPHOLD] booking={} — cancelled payout (net {}) reinstated", bookingId,
                row.getNetAmount());
        }
        releaseIfUnfrozen(row);
        return row.getEffectiveNetAmount();
    }

    /**
     * fix/payment-payout-recovery: {@code UPHOLD_COACH} on a released payout. Its transfer was reversed in part
     * or whole by an earlier refund (or it released already cut), so the coach has less than the full net.
     * <ol>
     *   <li>the coach's outstanding debt for this booking (a reversal Stripe refused, so the coach still holds
     *       that money) is waived and comes off {@code reversed_amount};</li>
     *   <li>what is still missing is paid by a {@code COACH_PAYOUT_TOPUP} transfer — its own Stripe transfer,
     *       because the booking's {@code transfer-{bookingId}} key and transfer are spent.
     *       {@link CoachPayoutTopUpHandler} folds it back into the row when it succeeds.</li>
     * </ol>
     */
    private BigDecimal topUpReleased(CoachPayout row) {
        UUID bookingId = row.getBookingId();
        BigDecimal waived = coachPayoutDebtService.waiveForBooking(bookingId);
        if (waived.signum() > 0) {
            BigDecimal reversed = row.getReversedAmount() == null ? BigDecimal.ZERO : row.getReversedAmount();
            row.setReversedAmount(reversed.subtract(waived).max(BigDecimal.ZERO));
            if (CoachPayoutStatus.REVERSED.equals(row.getStatus()) && row.getEffectiveNetAmount().signum() > 0) {
                row.setStatus(CoachPayoutStatus.RELEASED);
            }
        }
        BigDecimal fullNet = row.getOriginalNet();
        BigDecimal owed = fullNet.subtract(row.getEffectiveNetAmount());
        coachPayoutRepository.save(row);
        if (owed.signum() > 0) {
            coachPayoutOutboxSupport.enqueueTopUp(bookingId, owed);
            log.info("[COACH_PAYOUT_UPHOLD] booking={} — payout {} kept {} of the full net {} (debt waived {}); "
                + "top-up transfer of {} enqueued", bookingId, row.getStatus(), row.getEffectiveNetAmount(), fullNet,
                waived, owed);
        } else {
            log.info("[COACH_PAYOUT_UPHOLD] booking={} — released payout already carries the full net {} (debt "
                + "waived {})", bookingId, fullNet, waived);
        }
        return fullNet;
    }

    /**
     * Puts a cut, not-yet-released payout back to its amounts as first computed.
     *
     * @return true when anything changed
     */
    static boolean restoreOriginal(CoachPayout row) {
        BigDecimal original = row.getOriginalNet();
        if (original == null || row.getNetAmount() == null || original.compareTo(row.getNetAmount()) <= 0) {
            return false;
        }
        row.setNetAmount(original);
        row.setGrossAmount(row.getOriginalGross());
        row.setCommissionAmount(row.getOriginalCommission());
        return true;
    }

    /**
     * A FROZEN payout with no freeze reason left goes back to PENDING_RELEASE and gets one transfer
     * message, not before its original {@code release_after}. Reads the safety-hold freeze row
     * {@code FOR SHARE} after the payout row lock — the {@code CoachPayoutTransferHandler} lock order.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void releaseIfUnfrozen(CoachPayout row) {
        if (!CoachPayoutStatus.FROZEN.equals(row.getStatus())) {
            coachPayoutRepository.save(row);
            return;
        }
        String remaining = remainingFreezeReason(row);
        if (remaining != null) {
            row.setLastError(remaining);
            coachPayoutRepository.save(row);
            return;
        }
        row.setStatus(CoachPayoutStatus.PENDING_RELEASE);
        row.setLastError(null);
        coachPayoutRepository.save(row);
        Instant now = Instant.now();
        Instant notBefore = row.getReleaseAfter() != null && row.getReleaseAfter().isAfter(now)
            ? row.getReleaseAfter() : now;
        coachPayoutOutboxSupport.enqueueTransfer(row.getBookingId(), notBefore);
        log.info("[COACH_PAYOUT_UNFREEZE] booking={} — no freeze reason left, re-enqueued for release",
            row.getBookingId());
    }

    /** The freeze reason still standing on the payout, or null. Safety hold is read FOR SHARE. */
    String remainingFreezeReason(CoachPayout row) {
        if (row.isDisputeFrozen()) {
            return REASON_DISPUTE_OPEN;
        }
        if (row.isChargebackFrozen()) {
            return REASON_CHARGEBACK_OPEN;
        }
        if (coachPayoutFreezeRepository.isFrozenForShare(row.getCoachId())) {
            return CoachPayoutFreezeService.REASON_SAFETY_HOLD;
        }
        return null;
    }

    /**
     * Cuts {@code share} (less than the net) off a not-yet-released payout: the net by {@code share}, the
     * gross by the same proportion (never below the new net), and the commission to the difference.
     *
     * @return the new net
     */
    static BigDecimal cutNet(CoachPayout row, BigDecimal share) {
        BigDecimal oldNet = row.getNetAmount();
        BigDecimal newNet = oldNet.subtract(share);
        BigDecimal newGross = row.getGrossAmount()
            .subtract(row.getGrossAmount().multiply(share).divide(oldNet, 2, RoundingMode.HALF_UP))
            .max(newNet);
        row.setNetAmount(newNet);
        row.setGrossAmount(newGross);
        row.setCommissionAmount(newGross.subtract(newNet));
        return newNet;
    }

    /** The proportional coach share of a refund; the whole net when the whole price is refunded. */
    public static BigDecimal coachShareOfRefund(BigDecimal coachNet, BigDecimal parentRefund, BigDecimal parentPrice) {
        if (coachNet == null || coachNet.signum() <= 0 || parentPrice == null || parentPrice.signum() <= 0
                || parentRefund == null || parentRefund.signum() <= 0) {
            return BigDecimal.ZERO;
        }
        if (parentRefund.compareTo(parentPrice) >= 0) {
            return coachNet;
        }
        return coachNet.multiply(parentRefund).divide(parentPrice, 2, RoundingMode.HALF_UP);
    }

    private void freeze(CoachPayout row, String reason) {
        if (CoachPayoutStatus.PENDING_RELEASE.equals(row.getStatus())) {
            row.setStatus(CoachPayoutStatus.FROZEN);
            row.setLastError(reason);
        }
        // HOLD stays HOLD (the flag stops the release once CoachPayoutHoldRecoveryService re-drives it) and
        // FROZEN keeps its first reason in last_error.
        coachPayoutRepository.save(row);
    }

    private static boolean isUnreleased(String status) {
        return CoachPayoutStatus.PENDING_RELEASE.equals(status) || CoachPayoutStatus.HOLD.equals(status)
            || CoachPayoutStatus.FROZEN.equals(status);
    }

    private static String truncate(String s) {
        return s != null && s.length() > 500 ? s.substring(0, 500) : s;
    }
}
