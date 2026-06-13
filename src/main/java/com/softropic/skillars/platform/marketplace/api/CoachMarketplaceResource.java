package com.softropic.skillars.platform.marketplace.api;

import com.softropic.skillars.infrastructure.security.SecurityConstants;
import com.softropic.skillars.platform.marketplace.contract.CoachSearchParams;
import com.softropic.skillars.platform.marketplace.contract.CoachSearchResponse;
import com.softropic.skillars.platform.marketplace.service.CoachSearchService;
import com.softropic.skillars.platform.security.contract.AgeTier;
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
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;

@Validated
@Observed(name = "marketplace.search")
@RestController
@RequestMapping("/api/marketplace/coaches")
@RequiredArgsConstructor
@Slf4j
public class CoachMarketplaceResource {

    private final CoachSearchService coachSearchService;

    @GetMapping
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
}
