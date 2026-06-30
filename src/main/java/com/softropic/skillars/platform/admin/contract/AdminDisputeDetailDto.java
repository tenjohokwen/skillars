package com.softropic.skillars.platform.admin.contract;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record AdminDisputeDetailDto(
    UUID disputeId,
    UUID bookingId,
    Long raisedBy,
    String raisedByRole,
    String reason,
    String details,
    String status,
    String resolution,
    String resolutionNote,
    BigDecimal creditAmount,
    Instant createdAt,
    Instant resolvedAt,
    Long resolvedBy,
    UUID coachId,
    String coachName,
    Instant sessionDate,
    String bookingStatus,
    BigDecimal creditDebited,
    BigDecimal stripeCharged,
    BigDecimal sessionPrice,
    List<CoachCancellationHistoryEntryDto> cancellationHistory) {}
