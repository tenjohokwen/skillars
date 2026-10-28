package com.softropic.skillars.platform.admin.contract;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.UUID;

public record SubmitAppealRequest(
    @NotNull AppealSubjectType subjectType,
    @NotNull UUID subjectId,
    @NotBlank @Size(max = 2000) String reason
) {}
