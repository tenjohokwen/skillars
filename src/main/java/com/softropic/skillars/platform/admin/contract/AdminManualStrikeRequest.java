package com.softropic.skillars.platform.admin.contract;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

public record AdminManualStrikeRequest(
    @NotNull UUID bookingId,
    @NotBlank String reason) {}
