package com.softropic.skillars.platform.security.api.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public record ResendOtpRequest(@NotNull @Positive Long userId) {}
