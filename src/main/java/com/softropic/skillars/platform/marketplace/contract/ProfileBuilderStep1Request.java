package com.softropic.skillars.platform.marketplace.contract;

import com.softropic.skillars.infrastructure.validation.IanaTimezone;
import com.softropic.skillars.infrastructure.validation.NoContactDetails;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;

public record ProfileBuilderStep1Request(
    @NotBlank @Size(max = 120) @NoContactDetails String displayName,
    @Size(max = 2000) String bio,
    @Size(max = 100) String city,
    @Size(max = 100) String district,
    @NotEmpty @Size(max = 10) List<@NotBlank @Size(max = 50) @NoContactDetails String> languages,
    @NotBlank @IanaTimezone String canonicalTimezone
) {}
