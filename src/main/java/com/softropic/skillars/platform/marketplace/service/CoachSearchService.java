package com.softropic.skillars.platform.marketplace.service;

import com.softropic.skillars.platform.marketplace.contract.CoachCardDto;
import com.softropic.skillars.platform.marketplace.contract.CoachSearchParams;
import com.softropic.skillars.platform.marketplace.contract.CoachSearchResponse;
import com.softropic.skillars.platform.marketplace.repo.CoachPricingRepository;
import com.softropic.skillars.platform.marketplace.repo.CoachProfile;
import com.softropic.skillars.platform.marketplace.repo.CoachProfileRepository;
import com.softropic.skillars.platform.marketplace.repo.CoachReliabilityStrikeRepository;
import com.softropic.skillars.platform.marketplace.repo.CoachSpecialtyRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
@Slf4j
public class CoachSearchService {

    private final CoachProfileRepository coachProfileRepository;
    private final CoachSpecialtyRepository coachSpecialtyRepository;
    private final CoachPricingRepository coachPricingRepository;
    private final CoachReliabilityStrikeRepository strikeRepository;
    private final CoachCapabilityService coachCapabilityService;

    public CoachSearchResponse searchCoaches(CoachSearchParams params, int page, int size) {
        // 1. Build DB-level specification — status=ACTIVE and city filter always applied
        Specification<CoachProfile> spec = CoachSearchSpecification.build(params);

        // 2. Build sort + pageable (price sort applied post-fetch on current page)
        Sort sort = buildSort(params.sortBy());
        Pageable pageable = PageRequest.of(page, size, sort);

        // 3. DB query — only the current page rows are loaded into memory
        Page<CoachProfile> profilePage = coachProfileRepository.findAll(spec, pageable);

        if (profilePage.isEmpty()) {
            return new CoachSearchResponse(List.of(), page, size, 0, 0, false);
        }

        // 4. Apply language filter post-fetch if requested (PostgreSQL array ANY() not portable in JPA Criteria)
        List<CoachProfile> profiles = applyLanguageFilter(profilePage.getContent(), params.language(), params.city());

        // 5. Batch-load enrichment data for THIS PAGE's IDs only (not all coaches)
        List<UUID> pageIds = profiles.stream().map(CoachProfile::getId).toList();
        Map<UUID, List<String>> specialtiesByCoach = loadSpecialties(pageIds);
        Map<UUID, BigDecimal>   priceByCoach       = loadPrices(pageIds);
        Map<UUID, Integer>      strikesByCoach     = loadReliabilityStrikes(pageIds);
        Map<UUID, List<String>> capabilityBadgesByCoach = coachCapabilityService.getActiveBadgesBatch(pageIds);

        // 6. Assemble DTOs
        List<CoachCardDto> dtos = profiles.stream().map(p -> new CoachCardDto(
            p.getId(),
            p.getDisplayName(),
            p.getCity(),
            p.getDistrict(),
            p.getPhotoUrl(),
            p.getVerificationTier(),
            specialtiesByCoach.getOrDefault(p.getId(), List.of()).stream().limit(2).toList(),
            priceByCoach.getOrDefault(p.getId(), BigDecimal.ZERO),
            0.0,      // aggregateRating — wired in Epic 9
            0,        // reviewCount     — wired in Epic 9
            strikesByCoach.getOrDefault(p.getId(), 0),
            capabilityBadgesByCoach.getOrDefault(p.getId(), List.of())
        )).toList();

        // 7. Apply price sort within the current page (cross-page price sort deferred to future migration)
        List<CoachCardDto> sorted = sortPage(dtos, params.sortBy());

        // 8. Compute accurate pagination metadata — when language filter is active, run a COUNT query
        //    scoped to city+language (P8); otherwise use JPA page metadata directly
        long totalElements;
        int  totalPages;
        boolean hasNext;
        if (StringUtils.hasText(params.language())) {
            totalElements = coachProfileRepository.countByLanguageAndCity(params.language(), params.city());
            totalPages    = totalElements == 0 ? 0 : (int) Math.ceil((double) totalElements / size);
            hasNext       = (long) (page + 1) * size < totalElements;
        } else {
            totalElements = profilePage.getTotalElements();
            totalPages    = profilePage.getTotalPages();
            hasNext       = profilePage.hasNext();
        }

        return new CoachSearchResponse(
            sorted,
            profilePage.getNumber(),
            profilePage.getSize(),
            totalElements,
            totalPages,
            hasNext
        );
    }

    private Sort buildSort(String sortBy) {
        // price sort is applied in Java after enrichment (perSessionPrice lives in a separate table)
        return switch (StringUtils.hasText(sortBy) ? sortBy : "displayName") {
            case "price", "rating" -> Sort.by(Sort.Direction.ASC, "displayName");
            default                -> Sort.by(Sort.Direction.ASC, "displayName");
        };
    }

    private List<CoachCardDto> sortPage(List<CoachCardDto> dtos, String sortBy) {
        if ("price".equals(sortBy)) {
            return dtos.stream()
                .sorted(Comparator.comparing(d -> d.perSessionPrice() != null ? d.perSessionPrice() : BigDecimal.ZERO))
                .toList();
        }
        return dtos; // displayName sort was applied at DB level via Pageable
    }

    private List<CoachProfile> applyLanguageFilter(List<CoachProfile> profiles, String language, String city) {
        if (!StringUtils.hasText(language)) {
            return profiles;
        }
        // Native query returns IDs of coaches in this city speaking this language (city-scoped, not global)
        Set<UUID> matchingIds = coachProfileRepository.findIdsByLanguage(language, city)
            .stream().collect(Collectors.toSet());
        return profiles.stream()
            .filter(p -> matchingIds.contains(p.getId()))
            .toList();
    }

    private Map<UUID, List<String>> loadSpecialties(List<UUID> ids) {
        return coachSpecialtyRepository.findByCoachIdIn(ids).stream()
            .collect(Collectors.groupingBy(
                s -> s.getCoachId(),
                Collectors.mapping(s -> s.getSkill(), Collectors.toList())
            ));
    }

    private Map<UUID, BigDecimal> loadPrices(List<UUID> ids) {
        return coachPricingRepository.findByCoachIdIn(ids).stream()
            .collect(Collectors.toMap(
                p -> p.getCoachId(),
                p -> p.getPerSessionPrice(),
                (a, b) -> a.compareTo(b) <= 0 ? a : b  // keep lowest price on duplicate coachId rows
            ));
    }

    private Map<UUID, Integer> loadReliabilityStrikes(List<UUID> ids) {
        OffsetDateTime since = OffsetDateTime.now().minusDays(90);
        return strikeRepository.countByCoachIdInAndCreatedAtAfter(ids, since).stream()
            .collect(Collectors.toMap(
                row -> (UUID) row[0],
                row -> ((Long) row[1]).intValue()
            ));
    }
}
