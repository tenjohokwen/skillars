package com.softropic.skillars.platform.session.contract;

import java.time.Instant;
import java.util.UUID;

public record DrillUploadInitiateResponse(
    UUID videoId,
    UUID uploadSessionId,
    String signedUploadUrl,
    Instant expiresAt
) {}
