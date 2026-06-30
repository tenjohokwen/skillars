package com.softropic.skillars.platform.admin.contract;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record AdminReinstateCoachRequest(
    @NotBlank @Size(max = 500) String reason) {}
