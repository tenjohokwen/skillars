package com.softropic.skillars.platform.development.contract;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

public record SkillExposureResponse(
    Map<String, BigDecimal> currentWeek,
    List<WeeklySkillTotals> trend,
    List<String> neglectedSkillCodes
) {}
