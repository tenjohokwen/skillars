package com.softropic.skillars.platform.video.contract;

import java.time.Instant;
import java.util.UUID;

public record InitializeUploadResponse(
    UUID videoId,
    UUID uploadSessionId,
    String providerUploadId,
    String signedUploadUrl,
    Instant expiresAt
) {}
