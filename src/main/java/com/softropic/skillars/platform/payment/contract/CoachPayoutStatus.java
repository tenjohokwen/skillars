package com.softropic.skillars.platform.payment.contract;

/**
 * The seven values permitted by {@code chk_coach_payouts_status} on {@code payment.coach_payouts}
 * (V133). skillars-deferred-106: the coach's share of a session fee is no longer moved at capture
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
 *   PENDING_RELEASE --dispute inside hold window-> CANCELLED      (no money moved)
 *   RELEASED        --Transfer.createReversal ok-> REVERSED       (dispute upheld after payout)
 *   RELEASED        --non-retryable reversal err-> REVERSAL_FAILED
 *   HOLD            --operator "coach reconnected"-> PENDING_RELEASE (re-enqueues one outbox message)
 *   HOLD / REVERSAL_FAILED --explicit operator SQL-> FAILED_PERMANENT (unrecoverable; runbook only)
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
     * cancelled outright — no money ever moved. Also the {@code stripe_charged = 0} audit case
     * (reason {@code NO_STRIPE_CHARGE}).
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
