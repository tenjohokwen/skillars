package com.softropic.skillars.platform.admin.contract;

import java.time.Instant;

public record AdminMessageReportDto(
    String reason,
    String details,
    Instant createdAt) {}
