package com.softropic.skillars.platform.development.contract;

import java.util.List;

/**
 * Per-skill weekly SLU trend signal for a player. skillars-deferred-98 AC1b.
 *
 * @param trends one entry per skill that has at least one snapshot row in the window, ordered by
 *               {@code skillCode}
 */
public record SkillTrendResponse(List<SkillTrend> trends) {}
