package com.softropic.skillars.platform.development.contract;

import java.math.BigDecimal;

/**
 * One skill's weekly SLU trend over the requested window. skillars-deferred-98 AC1b.
 *
 * @param skillCode      {@code development.skill_definitions.code}
 * @param direction      improving / flat / declining, or insufficient data
 * @param slopePerWeek   ordinary-least-squares slope of weekly {@code total_slu} against the week
 *                       index; {@code 0} when {@code direction == INSUFFICIENT_DATA}
 * @param weeksObserved  number of weeks in the window that actually have a snapshot row for the skill
 */
public record SkillTrend(
    String skillCode,
    SkillTrendDirection direction,
    BigDecimal slopePerWeek,
    int weeksObserved
) {}
