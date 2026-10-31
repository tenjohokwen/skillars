package com.softropic.skillars.platform.marketplace.contract;

import com.softropic.skillars.platform.security.contract.AgeTier;
import com.softropic.skillars.infrastructure.validation.NoContactDetails;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;

public record ProfileBuilderStep2Request(
    @NotEmpty @Size(max = 20) List<@NotBlank @Size(max = 50) @NoContactDetails String> specialties,
    @NotEmpty List<AgeTier> ageGroups
) {}
