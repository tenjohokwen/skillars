package com.softropic.skillars.platform.video.contract;

import jakarta.annotation.Nullable;

import java.time.Instant;

public record PlaybackAuthorizationResponse(
    String token,
    String playbackUrl,
    Instant expiresAt,
    @Nullable String downloadUrl
) {
    public PlaybackAuthorizationResponse(String token, String playbackUrl, Instant expiresAt) {
        this(token, playbackUrl, expiresAt, null);
    }
}
