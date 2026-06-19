package com.softropic.skillars.platform.development.contract;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record TimelineEventResponse(
    UUID id,
    PlayerTimelineEventType eventType,
    UUID referenceId,
    String referenceModule,
    Instant occurredAt,
    Map<String, Object> metadata   // raw JSONB — frontend renders based on eventType
) {}
