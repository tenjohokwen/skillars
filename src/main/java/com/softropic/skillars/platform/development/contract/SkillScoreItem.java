package com.softropic.skillars.platform.development.contract;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record SkillScoreItem(
    @NotBlank @Size(max = 10) String skillCode,
    @NotNull @Min(1) @Max(100) Integer score,
    @Size(max = 500) String notes
) {}
