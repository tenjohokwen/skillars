package com.softropic.skillars.platform.development.contract;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record CoachBrandingRequest(
    @Size(max = 500) String logoKey,     // storage key returned after logo upload; null to clear
    @Pattern(regexp = "^#[0-9A-Fa-f]{6}$") String brandColour  // null to clear
) {}
