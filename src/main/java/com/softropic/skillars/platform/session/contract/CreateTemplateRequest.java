package com.softropic.skillars.platform.session.contract;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.UUID;

public record CreateTemplateRequest(
    @NotNull UUID sessionId,
    @NotBlank @Size(max = 200) String name
) {}
