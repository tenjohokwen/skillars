package com.softropic.skillars.platform.marketplace.contract;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

public record CoachProfileDto(
    UUID id,
    String displayName,
    String photoUrl,
    String verificationTier,
    List<String> capabilityBadges,
    Double averageRating,
    int reviewCount,
    String bio,
    List<String> languages,
    String city,
    String district,
    List<String> specialties,
    List<String> ageGroupsCoached,
    BigDecimal perSessionPrice,
    String currency,
    List<SessionPackDto> sessionPacks,
    boolean available,            // true if coach has ≥1 availability window
    int reliabilityStrikeCount,
    List<CoachMediaItemDto> mediaGallery
) {}
