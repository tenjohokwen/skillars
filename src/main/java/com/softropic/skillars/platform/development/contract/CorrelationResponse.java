package com.softropic.skillars.platform.development.contract;

import java.util.List;

public record CorrelationResponse(
    boolean insufficientData,          // true when session count < minSessionCount
    long minimumSessionCount,          // value from ConfigService (for UI messaging)
    List<CorrelationInsight> insights, // empty when insufficientData = true
    int excludedSkillCount             // skills with SLU data but no radar composite
) {}
