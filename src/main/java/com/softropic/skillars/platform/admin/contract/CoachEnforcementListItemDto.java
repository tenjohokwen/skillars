package com.softropic.skillars.platform.admin.contract;

import java.time.Instant;
import java.util.UUID;

public record CoachEnforcementListItemDto(
    UUID coachId,
    String coachName,
    String status,
    String verificationTier,
    long activeStrikes,
    Instant statusChangedAt) {}
