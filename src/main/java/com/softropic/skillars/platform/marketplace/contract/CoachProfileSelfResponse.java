package com.softropic.skillars.platform.marketplace.contract;

import com.softropic.skillars.platform.security.contract.AgeTier;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * The coach's own full profile, for "My Profile" edit-dialog prefill (AC1). Unlike
 * {@link CoachProfileDto} (the public marketplace view), this works for a {@code DRAFT} profile
 * and carries every field the builder steps collect, including fields the public view omits
 * ({@code canonicalTimezone}, {@code sessionDurationMinutes}, availability windows).
 */
public record CoachProfileSelfResponse(
    UUID coachId,
    CoachProfileStatus status,
    String displayName,
    String bio,
    String city,
    String district,
    List<String> languages,
    String canonicalTimezone,
    List<String> specialties,
    List<AgeTier> ageGroups,
    BigDecimal perSessionPrice,
    Integer sessionDurationMinutes,
    String currency,
    List<SessionPackDto> sessionPacks,
    List<CoachAvailabilityWindowDto> availabilityWindows,
    String photoUrl
) {}
