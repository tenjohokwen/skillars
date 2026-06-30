package com.softropic.skillars.platform.admin.contract;

import java.time.Instant;

public record AdminConversationReportDto(
    String reason,
    String details,
    Instant createdAt) {}
