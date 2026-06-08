package com.softropic.skillars.infrastructure.video;

import java.time.Instant;

public record SignedPlaybackUrl(String url, Instant expiresAt) {}
