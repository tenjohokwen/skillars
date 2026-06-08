package com.softropic.skillars.platform.filestorage.contract;

import java.time.Instant;

public record ConfirmUploadResponse(
    Long id,
    String key,
    long sizeBytes,
    String contentType,
    String checksum,
    Instant uploadedAt
) {
}
