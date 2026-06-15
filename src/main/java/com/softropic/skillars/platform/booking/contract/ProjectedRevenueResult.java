package com.softropic.skillars.platform.booking.contract;

import java.math.BigDecimal;

public record ProjectedRevenueResult(
    BigDecimal grossRevenue,
    BigDecimal commissionDeduction,
    BigDecimal netRevenue,
    BigDecimal commissionRate
) {}
