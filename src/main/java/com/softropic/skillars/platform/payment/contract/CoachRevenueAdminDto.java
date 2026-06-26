package com.softropic.skillars.platform.payment.contract;

import java.math.BigDecimal;

public record CoachRevenueAdminDto(
    BigDecimal grossEarnings,
    BigDecimal commissionDeducted,
    BigDecimal stripeFees,
    BigDecimal netPayout,
    long sessionCount,
    BigDecimal refundsIssued,
    String currency,
    int reliabilityStrikeCount,
    int outstandingDisputeCount
) {}
