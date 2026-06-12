package com.softropic.skillars.platform.marketplace.contract;

import com.softropic.skillars.platform.security.contract.AgeTier;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;

public record ProfileBuilderStep2Request(
    @NotEmpty @Size(max = 20) List<@NotBlank String> specialties,
    @NotEmpty List<AgeTier> ageGroups
) {}
