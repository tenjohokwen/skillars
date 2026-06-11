package com.softropic.skillars.platform.security.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record VerifyPhoneRequest(
    @NotNull Long userId,
    @NotBlank @Size(min = 6, max = 6) String otp
) {}
