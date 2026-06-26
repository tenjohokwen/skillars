package com.softropic.skillars.platform.payment.contract;

import java.math.BigDecimal;

public record RevenueSummaryDto(
    BigDecimal grossEarnings,
    BigDecimal commissionDeducted,
    BigDecimal stripeFees,
    BigDecimal netPayout,
    long sessionCount,
    BigDecimal refundsIssued,
    String currency
) {}
