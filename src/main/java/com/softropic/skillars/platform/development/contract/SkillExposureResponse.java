package com.softropic.skillars.platform.development.contract;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

public record SkillExposureResponse(
    Map<String, BigDecimal> currentWeek,
    List<WeeklySkillTotals> trend,
    List<String> neglectedSkillCodes,
    // skillars-deferred-156 AC5: lets CoachPlayerDetailPage.vue read the player's name off this
    // response instead of the route's own query string, which previously put a minor's name in
    // browser history/bookmarks/referrers.
    String playerName
) {}
