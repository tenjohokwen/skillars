package com.softropic.skillars.platform.session.contract;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

public record SessionBlockRequest(
    @NotBlank @Size(max = 50) String blockType,
    @NotBlank @Size(max = 100) String blockName,
    @Min(1) @Max(240) int durationMinutes,
    @NotNull List<@Valid SessionDrillRefRequest> drills
) {}
