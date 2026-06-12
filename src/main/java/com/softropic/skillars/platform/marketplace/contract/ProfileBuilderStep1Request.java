package com.softropic.skillars.platform.marketplace.contract;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;

public record ProfileBuilderStep1Request(
    @NotBlank @Size(max = 120) String displayName,
    @Size(max = 2000) String bio,
    @Size(max = 100) String city,
    @Size(max = 100) String district,
    @NotEmpty List<@NotBlank String> languages,
    @NotBlank String canonicalTimezone
) {}
