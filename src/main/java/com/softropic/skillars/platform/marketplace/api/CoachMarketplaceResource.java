package com.softropic.skillars.platform.marketplace.api;

import com.softropic.skillars.infrastructure.security.SecurityConstants;
import com.softropic.skillars.platform.marketplace.contract.CoachProfileDto;
import com.softropic.skillars.platform.marketplace.contract.CoachSearchParams;
import com.softropic.skillars.platform.marketplace.contract.CoachSearchResponse;
import com.softropic.skillars.platform.marketplace.contract.CoachSubscriptionTier;
import com.softropic.skillars.platform.marketplace.service.CoachProfileService;
import com.softropic.skillars.platform.marketplace.service.CoachSearchService;
import com.softropic.skillars.platform.reviews.contract.ReviewListResponse;
import com.softropic.skillars.platform.reviews.service.ReviewQueryService;
import com.softropic.skillars.platform.security.contract.AgeTier;
import com.softropic.skillars.platform.security.service.SecurityUtil;
import io.micrometer.observation.annotation.Observed;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

@Validated
@RestController
@RequestMapping("/api/marketplace/coaches")
@RequiredArgsConstructor
@Slf4j
public class CoachMarketplaceResource {

    private final CoachSearchService coachSearchService;
    private final CoachProfileService coachProfileService;
    private final ReviewQueryService reviewQueryService;
    private final SecurityUtil securityUtil;

    @GetMapping
    @Observed(name = "marketplace.search")
    @PreAuthorize(SecurityConstants.IS_PERMIT_ALL)
    public ResponseEntity<CoachSearchResponse> searchCoaches(
            @RequestParam @NotBlank String city,
            @RequestParam(required = false) String district,
            @RequestParam(required = false) String language,
            @RequestParam(required = false) BigDecimal minPrice,
            @RequestParam(required = false) BigDecimal maxPrice,
            @RequestParam(required = false) AgeTier ageGroup,
            @RequestParam(required = false) String skill,
            @RequestParam(required = false) Double minRating,
            @RequestParam(required = false) String sortBy,
            @RequestParam(defaultValue = "0")  @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(50) int size) {

        CoachSearchParams params = new CoachSearchParams(
            city, district, language, minPrice, maxPrice, ageGroup, skill, minRating, sortBy
        );
        return ResponseEntity.ok(coachSearchService.searchCoaches(params, page, size));
    }

    @GetMapping("/{coachId}")
    @Observed(name = "marketplace.profile")
    @PreAuthorize(SecurityConstants.IS_PERMIT_ALL)
    public ResponseEntity<CoachProfileDto> getCoachProfile(@PathVariable UUID coachId) {
        CoachProfileDto base = coachProfileService.getPublicProfile(coachId);
        ReviewListResponse reviews = reviewQueryService.getFirstPageForCoach(coachId);
        return ResponseEntity.ok(new CoachProfileDto(
            base.id(), base.displayName(), base.photoUrl(), base.verificationTier(),
            base.capabilityBadges(), base.averageRating(), base.reviewCount(),
            base.bio(), base.languages(), base.city(), base.district(),
            base.specialties(), base.ageGroupsCoached(), base.perSessionPrice(),
            base.currency(), base.sessionPacks(), base.available(),
            base.reliabilityStrikeCount(), base.mediaGallery(),
            reviews));
    }

    @GetMapping("/me/tier")
    @Observed(name = "marketplace.coach.tier")
    @PreAuthorize(SecurityConstants.HAS_COACH_ROLE)
    public ResponseEntity<Map<String, String>> getMyTier() {
        UUID coachId = coachProfileService.getCoachIdByUserId(securityUtil.getCurrentCoachUserId());
        CoachSubscriptionTier tier = coachProfileService.getCoachSubscriptionTier(coachId);
        return ResponseEntity.ok(Map.of("tier", tier.name()));
    }
}
