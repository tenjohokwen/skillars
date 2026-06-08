package com.softropic.skillars.platform.filestorage.contract;

import java.time.Instant;

public record SignUploadResponse(
    String key,
    String uploadUrl,
    Instant expiresAt
) {
}
