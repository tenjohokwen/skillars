package com.softropic.skillars.platform.admin.contract;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** {@code outcome} must be UPHELD or OVERTURNED — OPEN is rejected by the service. */
public record DecideAppealRequest(
    @NotNull AppealStatus outcome,
    @NotBlank @Size(max = 1000) String note
) {}
