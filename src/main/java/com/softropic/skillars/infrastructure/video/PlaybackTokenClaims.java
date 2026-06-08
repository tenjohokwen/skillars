package com.softropic.skillars.infrastructure.video;

import java.time.Instant;

public record PlaybackTokenClaims(String viewerId, Instant expiresAt) {}
