package com.softropic.skillars.platform.payment.contract;

import java.math.BigDecimal;

/**
 * skillars-deferred-106 AC12: coach revenue as seen by an admin. Figures reflect RELEASED coach
 * payouts (see {@link RevenueSummaryDto}); {@code pendingReleaseAmount} / {@code pendingReleaseCount}
 * are the completed-but-not-yet-released sessions. fix/payment-money-integrity: {@code netPayout} is the
 * amount actually transferred (no Stripe fee deducted — the platform pays it); the always-zero
 * {@code stripeFees} / {@code outstandingDisputeCount} fields were removed rather than shown as
 * figures they are not.
 */
public record CoachRevenueAdminDto(
    BigDecimal grossEarnings,
    BigDecimal commissionDeducted,
    BigDecimal netPayout,
    long sessionCount,
    BigDecimal refundsIssued,
    String currency,
    int reliabilityStrikeCount,
    BigDecimal pendingReleaseAmount,
    long pendingReleaseCount,
    BigDecimal heldAmount,
    long heldCount
) {}
