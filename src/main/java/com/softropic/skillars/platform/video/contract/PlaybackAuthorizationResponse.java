package com.softropic.skillars.platform.video.contract;

import java.time.Instant;

public record PlaybackAuthorizationResponse(String token, String playbackUrl, Instant expiresAt) {}
