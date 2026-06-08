package com.softropic.skillars.platform.video.contract;

public record InitializeUploadRequest(
    String ownerId,
    String fileName,
    long fileSizeBytes,
    String mimeType
) {}
