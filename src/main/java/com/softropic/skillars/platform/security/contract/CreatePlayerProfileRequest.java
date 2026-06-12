package com.softropic.skillars.platform.security.contract;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Past;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;

public record CreatePlayerProfileRequest(
    @NotBlank @Size(max = 100) String name,
    @NotNull @Past LocalDate dateOfBirth,
    @NotNull PlayerPosition position,
    Boolean parentConsent,
    @Size(max = 10) String consentPolicyVersion
) {}
