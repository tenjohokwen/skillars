package com.softropic.skillars.platform.video.contract;

public record UploadValidationRequest(
    String fileName,
    long fileSizeBytes,
    String mimeType,
    String containerFormat
) {}
