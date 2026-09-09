package com.softropic.skillars.platform.payment.contract;

import java.math.BigDecimal;

/**
 * skillars-deferred-106 AC12: {@code grossEarnings} / {@code commissionDeducted} / {@code netPayout}
 * / {@code sessionCount} now reflect coach payouts actually <strong>RELEASED</strong> to the coach's
 * Stripe account (dated by {@code released_at}), not merely captured from the parent.
 * {@code pendingReleaseAmount} / {@code pendingReleaseCount} are completed-but-not-yet-released
 * sessions (inside the 48h hold or awaiting a drain) — a separate line, never folded into released
 * revenue.
 */
public record RevenueSummaryDto(
    BigDecimal grossEarnings,
    BigDecimal commissionDeducted,
    BigDecimal stripeFees,
    BigDecimal netPayout,
    long sessionCount,
    BigDecimal refundsIssued,
    String currency,
    BigDecimal pendingReleaseAmount,
    long pendingReleaseCount
) {}
