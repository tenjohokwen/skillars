package com.softropic.skillars.platform.payment.contract;

import java.math.BigDecimal;

/**
 * skillars-deferred-106 AC12: {@code grossEarnings} / {@code commissionDeducted} / {@code netPayout}
 * / {@code sessionCount} now reflect coach payouts actually <strong>RELEASED</strong> to the coach's
 * Stripe account (dated by {@code released_at}), not merely captured from the parent.
 * {@code pendingReleaseAmount} / {@code pendingReleaseCount} are completed-but-not-yet-released
 * sessions (inside the 48h hold or awaiting a drain) — a separate line, never folded into released
 * revenue. fix/payment-money-integrity: {@code netPayout} is the ledger net actually transferred
 * (gross − commission); the coach pays no Stripe fee under separate charges &amp; transfers, so the
 * former estimated {@code stripeFees} line (subtracted from the net) was removed.
 *
 * <p>fix/payment-payout-recovery: {@code heldAmount} / {@code heldCount} are completed sessions whose payout
 * is FROZEN (a dispute or safety hold) or HOLD (the Stripe account cannot receive yet) — money the coach has
 * earned but cannot see moving, previously invisible. {@code refundsIssued} is every refund on the coach's
 * bookings in the period (fully refunded sessions included), and {@code currency} the platform currency.
 *
 * <p>fix/audit3-wallet-cashout-integrity: {@code payoutHold} is the coach's payout hold rule as it applies to
 * them now — the normal hold, the longer hold for a new coach's first payouts, and how many of those first
 * payouts are still to come — so the revenue page can say why a payout is not released yet. Not period-bound.
 */
public record RevenueSummaryDto(
    BigDecimal grossEarnings,
    BigDecimal commissionDeducted,
    BigDecimal netPayout,
    long sessionCount,
    BigDecimal refundsIssued,
    String currency,
    BigDecimal pendingReleaseAmount,
    long pendingReleaseCount,
    BigDecimal heldAmount,
    long heldCount,
    PayoutHoldDto payoutHold
) {
    /**
     * fix/audit3-wallet-cashout-integrity: the coach's payout hold. {@code newCoach} is true while their payouts
     * are held {@code newCoachHoldHours} instead of {@code normalHoldHours}; {@code newCoachPayoutsRemaining} of
     * their first {@code newCoachPayoutCount} payouts are still to be transferred — only payouts of at least
     * {@code minReleasedNet} (platform currency) count toward it.
     */
    public record PayoutHoldDto(long normalHoldHours, long newCoachHoldHours, long newCoachPayoutCount,
                                long newCoachPayoutsRemaining, boolean newCoach, BigDecimal minReleasedNet) {
    }
}
