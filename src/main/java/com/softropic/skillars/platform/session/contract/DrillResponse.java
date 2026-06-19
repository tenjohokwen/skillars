package com.softropic.skillars.platform.session.contract;

import java.time.Instant;
import java.util.List;
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
    String videoUrl,
    String transKey,
    Instant createdAt,
    List<String> tags,
    Boolean isClonedByMe,
    UUID cloneId,
    boolean addressesNeglectedSkill
) {}
