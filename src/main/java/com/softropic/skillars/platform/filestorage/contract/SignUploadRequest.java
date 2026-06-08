package com.softropic.skillars.platform.filestorage.contract;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;

import java.util.Map;

public record SignUploadRequest(
    @NotBlank String entity,
    @NotBlank String entityId,
    @NotBlank String contentType,
    @NotBlank String extension,
    @Positive long fileSizeBytes,
    String checksum,
    Map<String, String> tags
) {
}
