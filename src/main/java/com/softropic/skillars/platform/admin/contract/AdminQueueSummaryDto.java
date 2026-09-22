package com.softropic.skillars.platform.admin.contract;

public record AdminQueueSummaryDto(
    long messageReports,
    long conversationReports,
    long reviewFlags,
    long strikeAlerts,
    long disputes,
    long moderationHolds,
    // skillars-deferred-128 story review (Decision 2): without this, one open GDPR_ERASURE_DEADLINE
    // alert made `total` strictly exceed the sum of this record's own reported buckets — see
    // AdminQueueService.getSummary's own comment for the full rationale.
    long gdprErasureDeadlines,
    long total) {}
