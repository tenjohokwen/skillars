package com.softropic.skillars.platform.marketplace.contract;

import com.softropic.skillars.platform.reviews.contract.ReviewListResponse;

import java.math.BigDecimal;
import java.time.LocalDate;
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
    boolean available,
    int reliabilityStrikeCount,
    List<CoachMediaItemDto> mediaGallery,
    ReviewListResponse reviews,
    // S-09: optional safeguarding check (see CoachCardDto). Boolean + valid-until date only.
    boolean safeguardingCheckOnRecord,
    LocalDate safeguardingValidUntil
) {}
