package com.softropic.skillars.infrastructure.video;

import jakarta.annotation.Nullable;

import java.time.Instant;

public record PlaybackTokenClaims(String viewerId, Instant expiresAt, @Nullable String clientIp) {

    public PlaybackTokenClaims(String viewerId, Instant expiresAt) {
        this(viewerId, expiresAt, null);
    }
}
