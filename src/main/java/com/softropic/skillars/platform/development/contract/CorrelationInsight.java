package com.softropic.skillars.platform.development.contract;

import java.math.BigDecimal;

public record CorrelationInsight(
    String skillCode,
    BigDecimal cumulativeSlu,         // total SLU ever for this skill for this player
    BigDecimal compositeScore,        // current composite from player_radar_composites
    CorrelationInsightType insightType,
    String insightTextKey             // i18n key for the plain-English sentence
) {}
