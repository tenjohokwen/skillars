package com.softropic.skillars.platform.development.contract;

import java.math.BigDecimal;

public record CoachContributionDto(
    String coachDisplayName,
    String skillCode,
    BigDecimal percentageContribution  // 0-100, one decimal place
) {}
