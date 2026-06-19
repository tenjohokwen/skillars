package com.softropic.skillars.platform.development.contract;

import java.math.BigDecimal;
import java.util.Map;

public record WeeklySkillTotals(
    short isoYear,
    short isoWeek,
    Map<String, BigDecimal> sluPerSkill
) {}
