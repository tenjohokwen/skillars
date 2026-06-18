package com.softropic.skillars.platform.session.contract;

import java.time.Instant;
import java.util.UUID;

public record SessionTemplateResponse(
    UUID id,
    UUID coachId,
    String name,
    int drillCount,
    SessionDnaScore sessionDna,
    Instant lastDeployedAt,
    int deployCount,
    Instant createdAt
) {}
