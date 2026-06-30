package com.softropic.skillars.platform.admin.contract;

public record AdminQueueSummaryDto(
    long messageReports,
    long conversationReports,
    long reviewFlags,
    long strikeAlerts,
    long disputes,
    long total) {}
