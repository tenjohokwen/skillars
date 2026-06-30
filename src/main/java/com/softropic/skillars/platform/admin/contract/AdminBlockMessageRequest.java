package com.softropic.skillars.platform.admin.contract;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record AdminBlockMessageRequest(
    @NotBlank @Size(max = 500) String reason) {}
