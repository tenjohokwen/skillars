package com.softropic.skillars.platform.filestorage.contract;

import java.time.Instant;

public record SignDownloadResponse(
    String key,
    String downloadUrl,
    Instant expiresAt
) {
}
