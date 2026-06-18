package com.softropic.skillars.platform.session.contract;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.List;

public record UpdateSessionPlanRequest(
    @NotNull @Size(min = 1, max = 4) List<@Valid SessionBlockRequest> blocks,
    @NotEmpty List<@NotBlank String> developmentFocus,
    @Pattern(regexp = "DRAFT|SAVED") String status
) {}
