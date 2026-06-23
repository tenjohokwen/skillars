package com.softropic.skillars.platform.video.contract;

import jakarta.annotation.Nullable;

import java.time.Instant;

public record PlaybackResponse(
    String signedHlsUrl,
    Instant expiresAt,
    @Nullable String downloadUrl
) {}
