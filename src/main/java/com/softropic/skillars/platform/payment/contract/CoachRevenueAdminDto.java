package com.softropic.skillars.platform.payment.contract;

import java.math.BigDecimal;

/**
 * skillars-deferred-106 AC12: coach revenue as seen by an admin. Figures reflect RELEASED coach
 * payouts (see {@link RevenueSummaryDto}); {@code pendingReleaseAmount} / {@code pendingReleaseCount}
 * are the completed-but-not-yet-released sessions.
 */
public record CoachRevenueAdminDto(
    BigDecimal grossEarnings,
    BigDecimal commissionDeducted,
    BigDecimal stripeFees,
    BigDecimal netPayout,
    long sessionCount,
    BigDecimal refundsIssued,
    String currency,
    int reliabilityStrikeCount,
    int outstandingDisputeCount,
    BigDecimal pendingReleaseAmount,
    long pendingReleaseCount
) {}
