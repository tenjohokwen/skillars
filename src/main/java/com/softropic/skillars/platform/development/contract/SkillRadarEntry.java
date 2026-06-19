package com.softropic.skillars.platform.development.contract;

import java.math.BigDecimal;
import java.time.Instant;

public record SkillRadarEntry(
    String skillCode,
    String displayName,            // human-readable name from SkillDefinition; null if not fetched
    BigDecimal compositeScore,     // null if no assessments yet
    BigDecimal baselineScore,      // null if no baseline recorded yet
    Integer entryCount,            // total assessment rows; null if no assessments
    Instant lastUpdatedAt          // null if no assessments
) {}
