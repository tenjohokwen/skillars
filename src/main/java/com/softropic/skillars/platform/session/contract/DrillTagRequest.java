package com.softropic.skillars.platform.session.contract;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record DrillTagRequest(@NotBlank @Size(max = 50) String tag) {}
