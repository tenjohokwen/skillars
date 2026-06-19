package com.softropic.skillars.platform.development.contract;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record RadarAssessmentEntryResponse(
    UUID assessmentGroupId,
    String skillCode,
    int score,
    AssessmentType assessmentType,
    LocalDate assessmentDate,
    String notes,
    Instant createdAt
) {}
