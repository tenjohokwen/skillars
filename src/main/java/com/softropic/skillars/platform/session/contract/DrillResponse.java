package com.softropic.skillars.platform.session.contract;

import java.time.Instant;
import java.util.UUID;

public record DrillResponse(
    UUID id,
    String name,
    String description,
    String libraryType,
    UUID ownerCoachId,
    String status,
    DrillMetadata metadata,
    boolean hasVideo,
    String transKey,
    Instant createdAt
) {}
