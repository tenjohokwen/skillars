package com.softropic.skillars.platform.development.contract;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

public record CoachRadarPreferenceRequest(
    @NotNull @Size(min = 0, max = 15) List<@jakarta.validation.constraints.NotBlank @Size(max = 10) String> selectedSkillCodes
) {}
