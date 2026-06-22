package com.softropic.skillars.platform.video.contract;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

public record VideoUploadInitiateRequest(
    @NotBlank String fileName,
    @Min(1) long fileSizeBytes,
    @Pattern(regexp = "video/.+", message = "mimeType must be a video/* MIME type")
    @NotBlank String mimeType,
    @NotNull VideoType videoType
) {}
