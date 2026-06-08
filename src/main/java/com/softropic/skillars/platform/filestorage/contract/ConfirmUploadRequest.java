package com.softropic.skillars.platform.filestorage.contract;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;

import java.util.Map;

public record ConfirmUploadRequest(
    @NotBlank String contentType,
    @Positive long fileSizeBytes,
    String originalFilename,
    String checksum,
    Map<String, String> tags
) {
}
