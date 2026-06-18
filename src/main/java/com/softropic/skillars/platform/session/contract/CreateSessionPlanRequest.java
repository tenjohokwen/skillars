package com.softropic.skillars.platform.session.contract;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.UUID;

public record CreateSessionPlanRequest(
    @NotNull UUID bookingId,
    @NotNull @Size(min = 1, max = 4) List<@Valid SessionBlockRequest> blocks,
    @NotEmpty List<@NotBlank String> developmentFocus
) {}
