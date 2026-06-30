package com.softropic.skillars.platform.admin.contract;

import java.util.List;
import java.util.UUID;

public record CoachEnforcementProfileDto(
    UUID coachId,
    String coachName,
    String currentStatus,
    long activeStrikes,
    List<CoachStrikeHistoryDto> strikeHistory,
    List<CoachCancellationHistoryEntryDto> cancellationHistory,
    long openAlerts) {}
