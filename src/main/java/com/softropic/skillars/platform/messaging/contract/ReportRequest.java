package com.softropic.skillars.platform.messaging.contract;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record ReportRequest(
    @NotNull MessageReportReason reason,
    @Size(max = 500) String details) {}
