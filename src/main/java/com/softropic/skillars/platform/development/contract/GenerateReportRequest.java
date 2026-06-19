package com.softropic.skillars.platform.development.contract;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record GenerateReportRequest(
    @NotBlank @Size(max = 500) String nextSteps
) {}
