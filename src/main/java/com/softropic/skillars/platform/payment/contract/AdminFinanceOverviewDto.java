package com.softropic.skillars.platform.payment.contract;

import java.math.BigDecimal;
import java.util.Map;

/**
 * Admin finance overview. {@code totalGrossVolume} = captured booking payments + session-pack
 * purchases; {@code totalCommissionCollected} = card-charged booking money × each row's stamped
 * commission rate; {@code totalStripeFees} is an ESTIMATE from config. See
 * {@code RevenueReportingService.getAdminOverview}. There is deliberately no subscription-revenue
 * field: no subscription price amounts are stored locally to compute it from.
 */
public record AdminFinanceOverviewDto(
    BigDecimal totalGrossVolume,
    BigDecimal totalCommissionCollected,
    BigDecimal totalRefundCredit,
    BigDecimal totalCashOuts,
    BigDecimal totalStripeFees,
    Map<String, Long> activeCoachSubscriptions,
    Map<String, Long> activePlayerSubscriptions
) {}
