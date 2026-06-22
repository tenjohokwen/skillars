package com.softropic.skillars.infrastructure.video;

import java.time.Instant;

public record WebhookEvent(long videoLibraryId, String eventType, String providerAssetId, Instant timestamp) {}
