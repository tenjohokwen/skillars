package com.softropic.skillars.platform.marketplace.contract;

import java.math.BigDecimal;
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
    double aggregateRating,
    int reviewCount,
    int reliabilityStrikeCount,
    List<String> capabilityBadges  // empty at this stage; wired in Story 2.3
) {}
