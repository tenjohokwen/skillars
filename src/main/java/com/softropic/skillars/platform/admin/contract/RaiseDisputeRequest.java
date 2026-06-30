package com.softropic.skillars.platform.admin.contract;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.UUID;

public record RaiseDisputeRequest(
    @NotNull UUID bookingId,
    @NotBlank String reason,
    @NotBlank @Size(max = 2000) String details) {}
