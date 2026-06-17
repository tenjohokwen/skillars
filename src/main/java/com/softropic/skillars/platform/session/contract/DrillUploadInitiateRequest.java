package com.softropic.skillars.platform.session.contract;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record DrillUploadInitiateRequest(
    @NotBlank @Size(max = 255) String fileName,
    @Min(1) long fileSizeBytes,
    @NotBlank String mimeType,
    @Min(0) int durationSeconds
) {}
