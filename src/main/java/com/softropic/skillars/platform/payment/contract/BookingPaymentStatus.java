package com.softropic.skillars.platform.payment.contract;

/**
 * The four values permitted by {@code chk_bp_status} on {@code payment.booking_payments}
 * (V62, widened by V94). Kept here rather than beside the writing service because
 * {@code platform.booking} reads the reservation status too — {@code BookingService}'s cancel
 * interlock — and four independent copies of a string literal guarding money-safety logic is one
 * typo away from a silent hole.
 *
 * <p>Not an enum: the column is a {@code VARCHAR(16)} compared as a string in JPQL, native queries
 * and fixture SQL, and an enum would only add conversions at every boundary.
 *
 * <p><strong>Adding a value here means a migration widening the CHECK constraint</strong>, and
 * {@link #isTerminal} must be revisited — a status that is neither terminal nor CAPTURE_PENDING
 * would be read as "settled" by the settle guards.
 */
public final class BookingPaymentStatus {

    /**
     * UAT.3 AC1: reserved before the Stripe call, so that "no row" provably means "no charge was
     * attempted". <strong>Not terminal</strong> — a row resting here means an attempt died
     * mid-capture and needs manual reconciliation.
     */
    public static final String CAPTURE_PENDING = "CAPTURE_PENDING";

    /** Money moved and was recorded. The only status any revenue query counts. */
    public static final String CAPTURED = "CAPTURED";

    /** The charge failed, or the booking was declined before one was attempted. */
    public static final String CHARGE_FAILED = "CHARGE_FAILED";

    /** Funds held pending dispute resolution. */
    public static final String FROZEN = "FROZEN";

    /**
     * skillars-deferred-91 AC5 Part A: a {@link #CAPTURE_PENDING} row that stayed stuck past
     * {@code booking.payment_pending.capture_pending_max_hours}. Terminal — the booking is released
     * from {@code PAYMENT_PENDING} so the coach's slot frees and the parent can cancel. Distinct
     * from {@link #CHARGE_FAILED}: {@code CHARGE_FAILED} asserts "no money moved"; this asserts "we
     * stopped waiting; the Stripe side is unknown and an operator must reconcile it"
     * ({@code stripe_charged} is kept as-is, not zeroed). See
     * {@code docs/deployment/runbook.md} § CAPTURE_ABANDONED.
     */
    public static final String CAPTURE_ABANDONED = "CAPTURE_ABANDONED";

    private BookingPaymentStatus() {
    }

    /**
     * True when the status proves the payment reached a final outcome. Only
     * {@link #CAPTURE_PENDING} is non-terminal, which is exactly what the settle-path idempotency
     * guards need: a row alone no longer means "settled".
     */
    public static boolean isTerminal(String status) {
        return status != null && !CAPTURE_PENDING.equals(status);
    }
}
