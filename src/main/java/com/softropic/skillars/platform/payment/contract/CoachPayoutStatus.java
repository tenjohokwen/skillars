package com.softropic.skillars.platform.payment.contract;

/**
 * The eight values permitted by {@code chk_coach_payouts_status} on {@code payment.coach_payouts}
 * (V133; {@link #FROZEN} added by V186). skillars-deferred-106: the coach's share of a session fee
 * is no longer moved at capture
 * (as a Stripe destination charge did); it is transferred to the coach's connected account only
 * after {@code BookingCompletedEvent}, through the durable outbox. This class names the states of
 * that payout.
 *
 * <p>Not an enum, by the same reasoning as {@link BookingPaymentStatus}: the column is a
 * {@code VARCHAR(20)} compared as a string in JPQL, native queries and fixtures, and an enum would
 * only add conversions at every boundary.
 *
 * <p><strong>Row lifecycle</strong> (the {@code CoachPayoutEnqueueListener} creates the row, the
 * outbox handlers transition it):
 * <pre>
 *   (row created at completion-enqueue) --> PENDING_RELEASE
 *   PENDING_RELEASE --Transfer.create ok--------> RELEASED        (stamps stripe_transfer_id, released_at)
 *   PENDING_RELEASE --non-retryable transfer err-> HOLD           (operator owns it)
 *   PENDING_RELEASE --whole net refunded by a dispute-> CANCELLED  (no money moved)
 *   PENDING_RELEASE --in-app / card dispute opened--> FROZEN      (dispute_frozen / chargeback_frozen)
 *   RELEASED        --reversal of the whole net----> REVERSED     (a partial one stays RELEASED, reversed_amount)
 *   RELEASED        --non-retryable reversal err-> RELEASED / REVERSED + coach debt (V205, coach_payout_debts)
 *   RELEASED        --no stripe_transfer_id to reverse-> REVERSAL_FAILED
 *   RELEASED / REVERSED --UPHOLD_COACH top-up transfer-> RELEASED (net restored, COACH_PAYOUT_TOPUP)
 *   HOLD            --account can receive again / admin re-drive-> PENDING_RELEASE or FROZEN
 *                     (CoachPayoutHoldRecoveryService; re-enqueues one outbox message)
 *   HOLD / REVERSAL_FAILED --explicit operator SQL-> FAILED_PERMANENT (unrecoverable; runbook only)
 *   PENDING_RELEASE --coach under CSAM safety hold-> FROZEN       (CoachPayoutFreezeService / transfer handler)
 *   FROZEN          --last freeze reason lifted----> PENDING_RELEASE (re-enqueues one outbox message)
 *   FROZEN          --whole net refunded by dispute-> CANCELLED
 * </pre>
 * fix/payment-payouts-disputes-webhooks: a partial refund (in-app credit or lost card dispute) never
 * cancels — it cuts a pending net pro rata ({@code CoachPayoutAdjustmentService}), or reverses that share
 * of a released one; FROZEN is lifted only when no reason (safety hold, in-app dispute, card dispute)
 * remains.
 * <pre>
 * </pre>
 *
 * <p>There is <strong>no</strong> automatic/scheduled transition into {@link #FAILED_PERMANENT} —
 * that would contradict the outbox "never drop, retry forever, {@code [OUTBOX_STUCK]} after 10"
 * model (AC8.4).
 */
public final class CoachPayoutStatus {

    /**
     * The row exists and the transfer has not fired. Written by {@code CoachPayoutEnqueueListener}
     * atomically with the booking's completion write. The <strong>only</strong> status
     * {@code CoachPayoutTransferHandler} will act on (AC7.1).
     */
    public static final String PENDING_RELEASE = "PENDING_RELEASE";

    /** {@code Transfer.create} succeeded — the net is on its way to the coach's connected account. */
    public static final String RELEASED = "RELEASED";

    /** A dispute upheld after payout reversed the transfer ({@code Transfer.createReversal} ok). */
    public static final String REVERSED = "REVERSED";

    /**
     * {@code Transfer.createReversal} failed non-retryably (coach withdrew the balance, account
     * closed). Operator follow-up per the {@code COACH_PAYOUT_REVERSAL} runbook entry.
     */
    public static final String REVERSAL_FAILED = "REVERSAL_FAILED";

    /**
     * A dispute resolved inside the hold window before the transfer fired, so the pending payout was
     * cancelled outright — no money ever moved. Also the audit case of a completed booking nothing was
     * paid for (reason {@code NOTHING_CHARGED}), and a pack session the pack's payout allocation could
     * not cover ({@code PACK_FULLY_ALLOCATED} / {@code PACK_AMOUNT_UNKNOWN}).
     */
    public static final String CANCELLED = "CANCELLED";

    /**
     * {@code Transfer.create} failed non-retryably — the destination connected account is missing,
     * disconnected, restricted, or lacks the {@code transfers} capability. The operator owns
     * reconciliation (runbook: {@code COACH_PAYOUT_HELD}); "coach reconnected" flips it back to
     * {@link #PENDING_RELEASE} and re-enqueues one message.
     */
    public static final String HOLD = "HOLD";

    /**
     * Terminal, operator-written only: a {@link #HOLD} / {@link #REVERSAL_FAILED} case judged
     * unrecoverable (coach permanently gone). A re-driven outbox row for a {@code FAILED_PERMANENT}
     * booking is a no-op.
     */
    public static final String FAILED_PERMANENT = "FAILED_PERMANENT";

    /**
     * feat/coach-payouts-pack-and-credit (DECISIONS.md item 2): the coach is under a CSAM safety hold
     * ({@code payment.coach_payout_freezes}), so the transfer must not fire while the hold stands. Not
     * payable; an admin reinstatement flips it back to {@link #PENDING_RELEASE} and re-enqueues one
     * transfer message. A dispute resolved meanwhile cancels it like a pending payout. Normal (non
     * safety) suspensions never freeze payouts.
     */
    public static final String FROZEN = "FROZEN";

    private CoachPayoutStatus() {
    }

    /**
     * True when the payout has reached a state nothing will move it from without an operator.
     * {@link #RELEASED} is deliberately <strong>not</strong> terminal — a dispute can still reverse
     * it inside the 14-day window. Terminal = {@link #REVERSED} / {@link #CANCELLED} /
     * {@link #FAILED_PERMANENT}.
     */
    public static boolean isTerminal(String status) {
        return REVERSED.equals(status) || CANCELLED.equals(status) || FAILED_PERMANENT.equals(status);
    }

    /**
     * True only for {@link #PENDING_RELEASE}. {@code CoachPayoutTransferHandler} gates on this: for
     * any other status — or a missing row — a re-driven {@code COACH_PAYOUT_TRANSFER} message
     * returns without calling Stripe (AC7.1, review finding #2).
     */
    public static boolean isPayable(String status) {
        return PENDING_RELEASE.equals(status);
    }
}
