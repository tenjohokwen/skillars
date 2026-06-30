package com.softropic.skillars.platform.admin.contract;

import java.time.Instant;
import java.util.UUID;

public record AdminAlertDto(
    UUID alertId,
    String type,
    String referenceId,
    String referenceType,
    String status,
    Instant createdAt,
    String summary) {}
