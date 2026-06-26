package com.softropic.skillars.platform.payment.contract;

import java.math.BigDecimal;
import java.util.Map;

public record AdminFinanceOverviewDto(
    BigDecimal totalGrossVolume,
    BigDecimal totalCommissionCollected,
    BigDecimal totalRefundCredit,
    BigDecimal totalCashOuts,
    BigDecimal totalStripeFees,
    Map<String, Long> activeCoachSubscriptions,
    Map<String, Long> activePlayerSubscriptions,
    BigDecimal subscriptionRevenue
) {}
