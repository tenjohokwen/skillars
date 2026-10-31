package com.softropic.skillars.platform.marketplace.contract;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record CoachCardDto(
    UUID id,
    String displayName,
    String city,
    String district,
    String photoUrl,
    String verificationTier,
    List<String> topSpecialties,
    BigDecimal perSessionPrice,
    Double averageRating,
    int reviewCount,
    int reliabilityStrikeCount,
    List<String> capabilityBadges,  // empty at this stage; wired in Story 2.3
    // S-09: optional safeguarding check. A boolean + valid-until date only; never who inspected or what the
    // certificate said. The date is null exactly when no valid check is on record.
    boolean safeguardingCheckOnRecord,
    LocalDate safeguardingValidUntil
) {}
