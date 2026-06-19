package com.softropic.skillars.platform.development.contract;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PastOrPresent;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record RadarAssessmentRequest(
    @NotNull UUID assessmentGroupId,
    @NotNull @PastOrPresent LocalDate assessmentDate,
    @NotNull AssessmentType assessmentType,
    @NotEmpty @Size(max = 15) List<@Valid SkillScoreItem> entries
) {}
