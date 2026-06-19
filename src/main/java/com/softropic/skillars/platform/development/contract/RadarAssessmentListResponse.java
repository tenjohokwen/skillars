package com.softropic.skillars.platform.development.contract;

import java.util.List;
import java.util.Map;

public record RadarAssessmentListResponse(
    List<RadarAssessmentEntryResponse> entries,
    Map<String, Long> otherCoachCounts
) {}
