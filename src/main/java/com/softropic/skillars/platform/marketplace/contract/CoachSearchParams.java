package com.softropic.skillars.platform.marketplace.contract;

import com.softropic.skillars.platform.security.contract.AgeTier;

import java.math.BigDecimal;

public record CoachSearchParams(
    String city,          // required — enforced via @RequestParam @NotBlank in CoachMarketplaceResource
    String district,
    String language,
    BigDecimal minPrice,
    BigDecimal maxPrice,
    AgeTier ageGroup,               // null = no filter; typed as AgeTier to avoid bad-enum 400
    String skill,
    Double minRating,               // null = no filter; stub: > 0 returns 0 results until Epic 9
    String sortBy                   // "price" | "rating" | "displayName" (default)
) {}
