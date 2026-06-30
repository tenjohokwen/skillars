package com.softropic.skillars.platform.admin.contract;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

public record AdminResolveDisputeRequest(
    @NotBlank String resolution,
    BigDecimal creditAmount,
    @NotBlank @Size(max = 1000) String resolutionNote) {}
